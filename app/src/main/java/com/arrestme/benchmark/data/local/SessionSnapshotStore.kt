package com.arrestme.benchmark.data.local

import android.content.Context
import android.content.SharedPreferences
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType

/**
 * Synchronous, low-latency mirror of the active session and blocking rules.
 *
 * Used alongside Room so that BootRecoveryReceiver, AccessibilityService cold-binds,
 * and process-death inspection can immediately inspect the active session state
 * without waiting for database initialization.
 */
class SessionSnapshotStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveSnapshot(snapshot: ActiveSessionSnapshot?) {
        val editor = prefs.edit()
        if (snapshot == null || snapshot.state == SessionState.IDLE) {
            editor.putBoolean(KEY_HAS_SESSION, false)
            editor.putString(KEY_STATE, SessionState.IDLE.name)
        } else {
            editor.putBoolean(KEY_HAS_SESSION, true)
            editor.putString(KEY_SESSION_ID, snapshot.sessionId)
            editor.putString(KEY_SESSION_TYPE, snapshot.sessionType.name)
            editor.putString(KEY_STATE, snapshot.state.name)
            editor.putString(KEY_GOAL, snapshot.goal)
            editor.putString(
                KEY_ALLOWED_CATEGORIES,
                snapshot.allowedCategories.joinToString(",") { it.name }
            )
            editor.putString(KEY_RULE_MODE, snapshot.ruleMode.name)
            editor.putBoolean(KEY_OVERLAY_STATUS_BAR, snapshot.overlayStatusBar)
            editor.putBoolean(KEY_DISABLE_EMERGENCY_EXIT, snapshot.disableEmergencyExit)
            editor.putLong(KEY_CONFIGURED_DURATION_MS, snapshot.configuredDurationMs)
            editor.putLong(KEY_START_TS, snapshot.startTimestampMs)
            editor.putLong(KEY_END_TS, snapshot.endTimestampMs)
            editor.putLong(KEY_PAUSED_AT_TS, snapshot.pausedAtTimestampMs ?: -1L)
            editor.putLong(KEY_ACCUM_PAUSED_MS, snapshot.accumulatedPausedMs)
            editor.putLong(KEY_START_ELAPSED_MS, snapshot.startElapsedRealtimeMs)
            editor.putInt(KEY_BOOT_COUNT, snapshot.bootCountAtStart)
            editor.putLong(KEY_LAST_UPDATED_MS, snapshot.lastUpdatedWallMs)
            editor.putString(KEY_CLOUD_SESSION_ID, snapshot.cloudSessionId)
            editor.putString(KEY_AUTHORIZATION_ID, snapshot.authorizationId)
            editor.putString(KEY_CONTROLLING_PARTNER_ID, snapshot.controllingPartnerUserId)
            editor.putString(KEY_CONTROLLING_PARTNER_USERNAME, snapshot.controllingPartnerUsername)
        }
        editor.apply()
    }

    fun readSnapshot(): ActiveSessionSnapshot? {
        if (!prefs.getBoolean(KEY_HAS_SESSION, false)) return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val stateName = prefs.getString(KEY_STATE, SessionState.IDLE.name) ?: SessionState.IDLE.name
        val state = runCatching { SessionState.valueOf(stateName) }.getOrDefault(SessionState.IDLE)
        if (state == SessionState.IDLE) return null

        val typeName = prefs.getString(KEY_SESSION_TYPE, SessionType.SELF_ARREST.name)
            ?: SessionType.SELF_ARREST.name
        val sessionType = runCatching { SessionType.valueOf(typeName) }
            .getOrDefault(SessionType.SELF_ARREST)

        val categoriesCsv = prefs.getString(KEY_ALLOWED_CATEGORIES, "") ?: ""
        val categories = categoriesCsv.split(",")
            .mapNotNull { token ->
                token.trim().takeIf { it.isNotEmpty() }?.let {
                    runCatching { AllowedCategory.valueOf(it) }.getOrNull()
                }
            }
            .toSet()
            .ifEmpty { setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS) }

        val ruleModeName = prefs.getString(KEY_RULE_MODE, BlockingRuleMode.BLOCK_SELECTED.name)
            ?: BlockingRuleMode.BLOCK_SELECTED.name
        val ruleMode = runCatching { BlockingRuleMode.valueOf(ruleModeName) }
            .getOrDefault(BlockingRuleMode.BLOCK_SELECTED)

        val pausedRaw = prefs.getLong(KEY_PAUSED_AT_TS, -1L)

        return ActiveSessionSnapshot(
            sessionId = sessionId,
            sessionType = sessionType,
            state = state,
            goal = prefs.getString(KEY_GOAL, "Finish studying chapter 3") ?: "Finish studying chapter 3",
            allowedCategories = categories,
            ruleMode = ruleMode,
            overlayStatusBar = prefs.getBoolean(KEY_OVERLAY_STATUS_BAR, false),
            disableEmergencyExit = prefs.getBoolean(KEY_DISABLE_EMERGENCY_EXIT, false),
            configuredDurationMs = prefs.getLong(KEY_CONFIGURED_DURATION_MS, 15 * 60_000L),
            startTimestampMs = prefs.getLong(KEY_START_TS, 0L),
            endTimestampMs = prefs.getLong(KEY_END_TS, 0L),
            pausedAtTimestampMs = if (pausedRaw > 0L) pausedRaw else null,
            accumulatedPausedMs = prefs.getLong(KEY_ACCUM_PAUSED_MS, 0L),
            startElapsedRealtimeMs = prefs.getLong(KEY_START_ELAPSED_MS, 0L),
            bootCountAtStart = prefs.getInt(KEY_BOOT_COUNT, 0),
            lastUpdatedWallMs = prefs.getLong(KEY_LAST_UPDATED_MS, 0L),
            cloudSessionId = prefs.getString(KEY_CLOUD_SESSION_ID, null),
            authorizationId = prefs.getString(KEY_AUTHORIZATION_ID, null),
            controllingPartnerUserId = prefs.getString(KEY_CONTROLLING_PARTNER_ID, null),
            controllingPartnerUsername = prefs.getString(KEY_CONTROLLING_PARTNER_USERNAME, null)
        )
    }

    fun cacheBlockedAndAllowedPackages(blocked: Set<String>, allowed: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_BLOCKED_PACKAGES, blocked.toSet())
            .putStringSet(KEY_ALLOWED_PACKAGES, allowed.toSet())
            .apply()
    }

    fun getCachedBlockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun getCachedAllowedPackages(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun recordHeartbeat(nowWallMs: Long = System.currentTimeMillis(), cleanShutdown: Boolean = false) {
        prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT_MS, nowWallMs)
            .putBoolean(KEY_CLEAN_SHUTDOWN, cleanShutdown)
            .apply()
    }

    fun getLastHeartbeatMs(): Long = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)

    fun wasLastShutdownClean(): Boolean = prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true)

    fun getLastProcessedExitTimestampMs(): Long = prefs.getLong(KEY_LAST_EXIT_TS_MS, 0L)

    fun setLastProcessedExitTimestampMs(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_EXIT_TS_MS, timestampMs).apply()
    }

    fun saveCloudToLocalSessionMapping(cloudSessionId: String, localSessionId: String) {
        prefs.edit()
            .putString("$KEY_PREFIX_CLOUD_TO_LOCAL$cloudSessionId", localSessionId)
            .putString("$KEY_PREFIX_LOCAL_TO_CLOUD$localSessionId", cloudSessionId)
            .apply()
    }

    fun getLocalSessionIdForCloudSession(cloudSessionId: String): String? =
        prefs.getString("$KEY_PREFIX_CLOUD_TO_LOCAL$cloudSessionId", null)

    fun getCloudSessionIdForLocalSession(localSessionId: String): String? =
        prefs.getString("$KEY_PREFIX_LOCAL_TO_CLOUD$localSessionId", null)

    fun getLastCommandSequenceNo(deviceId: String): Long =
        prefs.getLong("$KEY_PREFIX_CMD_SEQ$deviceId", 0L)

    fun setLastCommandSequenceNo(deviceId: String, sequenceNo: Long) {
        val current = getLastCommandSequenceNo(deviceId)
        if (sequenceNo > current) {
            prefs.edit().putLong("$KEY_PREFIX_CMD_SEQ$deviceId", sequenceNo).apply()
        }
    }

    fun saveLocalRulesBackupIfNone(rulesJson: String) {
        if (!prefs.contains(KEY_LOCAL_RULES_BACKUP_JSON)) {
            prefs.edit().putString(KEY_LOCAL_RULES_BACKUP_JSON, rulesJson).apply()
        }
    }

    fun readLocalRulesBackupJson(): String? =
        prefs.getString(KEY_LOCAL_RULES_BACKUP_JSON, null)

    fun clearLocalRulesBackup() {
        prefs.edit().remove(KEY_LOCAL_RULES_BACKUP_JSON).apply()
    }

    fun saveCachedSchedulesJson(schedulesJson: String) {
        prefs.edit().putString(KEY_CACHED_SCHEDULES_JSON, schedulesJson).apply()
    }

    fun readCachedSchedulesJson(): String? =
        prefs.getString(KEY_CACHED_SCHEDULES_JSON, null)

    companion object {
        private const val PREFS_NAME = "arrest_me_snapshot_store"
        private const val KEY_HAS_SESSION = "has_session"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_TYPE = "session_type"
        private const val KEY_STATE = "state"
        private const val KEY_GOAL = "goal"
        private const val KEY_ALLOWED_CATEGORIES = "allowed_categories"
        private const val KEY_RULE_MODE = "rule_mode"
        private const val KEY_OVERLAY_STATUS_BAR = "overlay_status_bar"
        private const val KEY_DISABLE_EMERGENCY_EXIT = "disable_emergency_exit"
        private const val KEY_CONFIGURED_DURATION_MS = "configured_duration_ms"
        private const val KEY_START_TS = "start_ts"
        private const val KEY_END_TS = "end_ts"
        private const val KEY_PAUSED_AT_TS = "paused_at_ts"
        private const val KEY_ACCUM_PAUSED_MS = "accum_paused_ms"
        private const val KEY_START_ELAPSED_MS = "start_elapsed_ms"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_LAST_UPDATED_MS = "last_updated_ms"
        private const val KEY_CLOUD_SESSION_ID = "cloud_session_id"
        private const val KEY_AUTHORIZATION_ID = "authorization_id"
        private const val KEY_CONTROLLING_PARTNER_ID = "controlling_partner_id"
        private const val KEY_CONTROLLING_PARTNER_USERNAME = "controlling_partner_username"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
        private const val KEY_CLEAN_SHUTDOWN = "clean_shutdown"
        private const val KEY_LAST_EXIT_TS_MS = "last_exit_ts_ms"
        private const val KEY_PREFIX_CLOUD_TO_LOCAL = "map_cloud_to_local_"
        private const val KEY_PREFIX_LOCAL_TO_CLOUD = "map_local_to_cloud_"
        private const val KEY_PREFIX_CMD_SEQ = "cmd_seq_device_"
        private const val KEY_LOCAL_RULES_BACKUP_JSON = "local_self_arrest_rules_backup_json"
        private const val KEY_CACHED_SCHEDULES_JSON = "cached_upcoming_schedules_json"
    }
}
