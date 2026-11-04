package com.arrestme.benchmark.data.repository

import android.content.Context
import android.os.Build
import com.arrestme.benchmark.BuildConfig
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.data.local.ArrestDatabase
import com.arrestme.benchmark.data.local.DiagnosticEventEntity
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.data.local.SessionSnapshotStore
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import com.arrestme.benchmark.data.local.UsageTransitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ArrestRepository(
    private val context: Context,
    private val database: ArrestDatabase,
    val snapshotStore: SessionSnapshotStore
) {
    private val sessionDao = database.sessionDao()
    private val appRuleDao = database.appRuleDao()
    private val diagnosticEventDao = database.diagnosticEventDao()
    private val usageTransitionDao = database.usageTransitionDao()

    fun observeAllSessions(): Flow<List<SessionEntity>> = sessionDao.observeAllSessions()

    fun observeRecentTransitions(): Flow<List<SessionTransitionEntity>> =
        sessionDao.observeRecentTransitions()

    fun observeAppRules(): Flow<List<AppRuleEntity>> = appRuleDao.observeAllRules()

    fun observeDiagnosticEvents(): Flow<List<DiagnosticEventEntity>> =
        diagnosticEventDao.observeRecentEvents()

    suspend fun getActiveOrInProgressSession(): SessionEntity? =
        sessionDao.getActiveOrInProgressSession()

    suspend fun getSessionById(sessionId: String): SessionEntity? =
        sessionDao.getSessionById(sessionId)

    suspend fun upsertSession(session: SessionEntity) {
        sessionDao.upsertSession(session)
    }

    suspend fun insertTransition(transition: SessionTransitionEntity) {
        sessionDao.insertTransition(transition)
    }

    suspend fun setAppRule(packageName: String, appLabel: String, ruleType: AppRuleType?) {
        if (ruleType == null) {
            appRuleDao.deleteRule(packageName)
        } else {
            appRuleDao.upsertRule(
                AppRuleEntity(
                    packageName = packageName,
                    appLabel = appLabel,
                    ruleType = ruleType.name,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
        syncRulesCache()
    }

    suspend fun getAllRulesSnapshot(): List<AppRuleEntity> = appRuleDao.getAllRulesSnapshot()

    suspend fun syncRulesCache() {
        val rules = appRuleDao.getAllRulesSnapshot()
        val blocked = rules.filter { it.ruleType == AppRuleType.BLOCKED.name }
            .map { it.packageName }
            .toSet()
        val allowed = rules.filter { it.ruleType == AppRuleType.ALLOWED_STUDY.name }
            .map { it.packageName }
            .toSet()
        snapshotStore.cacheBlockedAndAllowedPackages(blocked = blocked, allowed = allowed)
    }

    suspend fun insertDiagnosticEvent(event: DiagnosticEventEntity) {
        diagnosticEventDao.insertEvent(event)
    }

    suspend fun clearDiagnosticEvents() {
        diagnosticEventDao.clearAllEvents()
    }

    suspend fun insertUsageTransition(transition: UsageTransitionEntity) {
        usageTransitionDao.insertUsageTransition(transition)
    }

    suspend fun getUsageTransitionsForSession(sessionId: String): List<UsageTransitionEntity> =
        usageTransitionDao.getTransitionsForSession(sessionId)

    suspend fun buildBenchmarkReportJson(permissionsSummary: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val sessions = sessionDao.getAllSessionsSnapshot()
            val transitions = sessionDao.getAllTransitionsSnapshot()
            val rules = appRuleDao.getAllRulesSnapshot()
            val events = diagnosticEventDao.getRecentEventsSnapshot()

            val root = JSONObject()
            root.put("schema_version", "0.1.0")
            root.put("project", "Arrest Me — Android Arrest Engine Benchmark")
            root.put("generated_at_ms", now)
            root.put("generated_at_iso", formatIsoUtc(now))

            val deviceObj = JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("android_release", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("app_version", BuildConfig.VERSION_NAME)
            }
            root.put("device", deviceObj)

            val permsObj = JSONObject()
            permissionsSummary.forEach { (key, value) -> permsObj.put(key, value) }
            root.put("verified_permissions_at_export", permsObj)

            val totalBlockedAttempts = sessions.sumOf { it.blockedAttempts }
            val totalSuccessfulBlocks = sessions.sumOf { it.successfulBlocks }
            val totalFailedBlocks = sessions.sumOf { it.failedBlocks }
            val totalOverlayActivations = sessions.sumOf { it.overlayActivations }
            val totalLatency = sessions.sumOf { it.totalOverlayLatencyMs }
            val overallAvgLatency = if (totalOverlayActivations > 0) {
                totalLatency / totalOverlayActivations
            } else {
                null
            }

            val summaryObj = JSONObject().apply {
                put("total_sessions", sessions.size)
                put("completed_sessions", sessions.count { it.state == "COMPLETED" })
                put("emergency_stopped_sessions", sessions.count { it.state == "EMERGENCY_STOPPED" })
                put("interrupted_sessions", sessions.count { it.state == "INTERRUPTED" })
                put("total_blocked_attempts", totalBlockedAttempts)
                put("total_successful_blocks", totalSuccessfulBlocks)
                put("total_failed_blocks", totalFailedBlocks)
                put("total_overlay_activations", totalOverlayActivations)
                put("overall_avg_overlay_latency_ms", overallAvgLatency ?: JSONObject.NULL)
                put("total_service_interruptions", sessions.sumOf { it.serviceInterruptions })
                put("total_permission_failures", sessions.sumOf { it.permissionFailures })
                put("total_rapid_switch_events", sessions.sumOf { it.rapidSwitchEvents })
            }
            root.put("aggregate_metrics", summaryObj)

            val sessionsArr = JSONArray()
            for (s in sessions) {
                val sessionTransitions = transitions.filter { it.sessionId == s.sessionId }
                    .sortedBy { it.timestampMs }
                val transArr = JSONArray()
                for (t in sessionTransitions) {
                    transArr.put(
                        JSONObject().apply {
                            put("from_state", t.fromState)
                            put("to_state", t.toState)
                            put("timestamp_ms", t.timestampMs)
                            put("timestamp_iso", formatIsoUtc(t.timestampMs))
                            put("reason", t.reason)
                        }
                    )
                }

                sessionsArr.put(
                    JSONObject().apply {
                        put("session_id", s.sessionId)
                        put("session_type", s.sessionType)
                        put("state", s.state)
                        put("goal", s.goal)
                        put("allowed_categories", s.allowedCategoriesCsv)
                        put("rule_mode", s.ruleMode)
                        put("configured_duration_ms", s.configuredDurationMs)
                        put("start_timestamp_ms", s.startTimestampMs)
                        put("start_timestamp_iso", formatIsoUtc(s.startTimestampMs))
                        put("end_timestamp_ms", s.endTimestampMs)
                        put("end_timestamp_iso", formatIsoUtc(s.endTimestampMs))
                        put("completed_or_stopped_at_ms", s.completedOrStoppedAtMs ?: JSONObject.NULL)
                        put("accumulated_paused_ms", s.accumulatedPausedMs)
                        put("blocked_attempts", s.blockedAttempts)
                        put("successful_blocks", s.successfulBlocks)
                        put("failed_blocks", s.failedBlocks)
                        put("overlay_activations", s.overlayActivations)
                        put("avg_overlay_latency_ms", s.avgOverlayLatencyMs ?: JSONObject.NULL)
                        put("min_overlay_latency_ms", s.minOverlayLatencyMs ?: JSONObject.NULL)
                        put("max_overlay_latency_ms", s.maxOverlayLatencyMs ?: JSONObject.NULL)
                        put("last_overlay_latency_ms", s.lastOverlayLatencyMs ?: JSONObject.NULL)
                        put("service_interruptions", s.serviceInterruptions)
                        put("permission_failures", s.permissionFailures)
                        put("rapid_switch_events", s.rapidSwitchEvents)
                        put(
                            "usage_stats_confirmed_blocked_launches",
                            s.usageStatsConfirmedBlockedLaunches ?: JSONObject.NULL
                        )
                        put(
                            "usage_stats_total_foreground_events",
                            s.usageStatsTotalForegroundEvents ?: JSONObject.NULL
                        )
                        put(
                            "usage_stats_discrepancy_count",
                            s.usageStatsDiscrepancyCount ?: JSONObject.NULL
                        )
                        put("usage_data_confidence", s.usageDataConfidence)
                        put("permissions_snapshot_start", s.permissionsSnapshotStart)
                        put("permissions_snapshot_end", s.permissionsSnapshotEnd ?: JSONObject.NULL)
                        put("completion_reason", s.completionReason ?: JSONObject.NULL)
                        put("transitions", transArr)
                    }
                )
            }
            root.put("sessions", sessionsArr)

            val rulesArr = JSONArray()
            for (r in rules) {
                rulesArr.put(
                    JSONObject().apply {
                        put("package_name", r.packageName)
                        put("app_label", r.appLabel)
                        put("rule_type", r.ruleType)
                        put("updated_at_ms", r.updatedAtMs)
                    }
                )
            }
            root.put("configured_app_rules", rulesArr)

            val eventsArr = JSONArray()
            for (e in events) {
                eventsArr.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("timestamp_ms", e.timestampMs)
                        put("timestamp_iso", formatIsoUtc(e.timestampMs))
                        put("event_type", e.eventType)
                        put("package_name", e.packageName ?: JSONObject.NULL)
                        put("session_id", e.sessionId ?: JSONObject.NULL)
                        put("details", e.details)
                        put("severity", e.severity)
                        put("source_confidence", e.sourceConfidence)
                    }
                )
            }
            root.put("diagnostic_events", eventsArr)

            root.toString(2)
        }

    suspend fun writeBenchmarkExportFile(jsonContent: String): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "arrest_me_benchmark_${System.currentTimeMillis()}.json"
        File(exportDir, fileName).apply {
            writeText(jsonContent, Charsets.UTF_8)
        }
    }

    private fun formatIsoUtc(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }
}
