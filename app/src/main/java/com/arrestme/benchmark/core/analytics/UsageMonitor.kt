package com.arrestme.benchmark.core.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.arrestme.benchmark.core.blocking.BlockingDecision
import com.arrestme.benchmark.core.blocking.BlockingRulesEvaluator
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType
import com.arrestme.benchmark.data.local.DataConfidence
import com.arrestme.benchmark.data.local.EventSeverity
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.data.local.UsageTransitionEntity
import com.arrestme.benchmark.data.repository.ArrestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class UsageMonitor(
    context: Context,
    private val repository: ArrestRepository,
    private val permissionChecker: PermissionChecker,
    private val rulesEvaluator: BlockingRulesEvaluator,
    private val antiCheatingMonitor: AntiCheatingMonitor,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val metricsMutex = Mutex()

    fun recordRealtimeForegroundTransition(
        sessionId: String,
        packageName: String,
        wasBlocked: Boolean,
        overlayTriggered: Boolean
    ) {
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            repository.insertUsageTransition(
                UsageTransitionEntity(
                    sessionId = sessionId,
                    timestampMs = now,
                    packageName = packageName,
                    source = "ACCESSIBILITY_REALTIME",
                    wasBlockedRuleMatch = wasBlocked,
                    overlayTriggered = overlayTriggered
                )
            )
        }
    }

    fun recordBlockedAttemptResult(
        sessionId: String,
        packageName: String,
        overlaySucceeded: Boolean,
        activationLatencyMs: Double?,
        failureReason: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            metricsMutex.withLock {
                val current = repository.getSessionById(sessionId) ?: return@withLock
                val updated = if (overlaySucceeded) {
                    val latency = (activationLatencyMs ?: 0.0).coerceAtLeast(0.0)
                    current.copy(
                        blockedAttempts = current.blockedAttempts + 1,
                        successfulBlocks = current.successfulBlocks + 1,
                        overlayActivations = current.overlayActivations + 1,
                        totalOverlayLatencyMs = current.totalOverlayLatencyMs + latency,
                        minOverlayLatencyMs = current.minOverlayLatencyMs?.let { min(it, latency) } ?: latency,
                        maxOverlayLatencyMs = current.maxOverlayLatencyMs?.let { max(it, latency) } ?: latency,
                        lastOverlayLatencyMs = latency
                    )
                } else {
                    current.copy(
                        blockedAttempts = current.blockedAttempts + 1,
                        failedBlocks = current.failedBlocks + 1
                    )
                }
                repository.upsertSession(updated)
            }

            if (overlaySucceeded) {
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.OVERLAY_ACTIVATED,
                    packageName = packageName,
                    sessionId = sessionId,
                    severity = EventSeverity.INFO,
                    details = "Overlay displayed over $packageName in ${
                        String.format("%.2f", activationLatencyMs ?: 0.0)
                    } ms"
                )
            } else {
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.OVERLAY_FAILED,
                    packageName = packageName,
                    sessionId = sessionId,
                    severity = EventSeverity.HIGH,
                    details = "Failed to display blocking overlay for $packageName: ${failureReason ?: "unknown error"}"
                )
            }
        }
    }

    fun recordServiceInterruption(sessionId: String?, reason: String) {
        if (sessionId == null) return
        scope.launch(Dispatchers.IO) {
            metricsMutex.withLock {
                val current = repository.getSessionById(sessionId) ?: return@withLock
                repository.upsertSession(
                    current.copy(serviceInterruptions = current.serviceInterruptions + 1)
                )
            }
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SERVICE_INTERRUPTION,
                sessionId = sessionId,
                severity = EventSeverity.HIGH,
                details = reason
            )
        }
    }

    fun recordPermissionFailure(sessionId: String?, reason: String, eventType: MonitorEventType) {
        scope.launch(Dispatchers.IO) {
            if (sessionId != null) {
                metricsMutex.withLock {
                    val current = repository.getSessionById(sessionId)
                    if (current != null) {
                        repository.upsertSession(
                            current.copy(permissionFailures = current.permissionFailures + 1)
                        )
                    }
                }
            }
            antiCheatingMonitor.recordEvent(
                type = eventType,
                sessionId = sessionId,
                severity = EventSeverity.CRITICAL,
                details = reason
            )
        }
    }

    fun recordRapidAppSwitch(sessionId: String?, switchCount: Int, windowMs: Long, latestPackage: String) {
        scope.launch(Dispatchers.IO) {
            if (sessionId != null) {
                metricsMutex.withLock {
                    val current = repository.getSessionById(sessionId)
                    if (current != null) {
                        repository.upsertSession(
                            current.copy(rapidSwitchEvents = current.rapidSwitchEvents + 1)
                        )
                    }
                }
            }
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.RAPID_APP_SWITCHING,
                packageName = latestPackage,
                sessionId = sessionId,
                severity = EventSeverity.WARNING,
                details = "Detected rapid foreground switching: $switchCount package switches within ${windowMs}ms"
            )
        }
    }

    /**
     * Queries Android UsageStatsManager for the session's time window and reconciles
     * OS-recorded foreground transitions against real-time Accessibility blocking events.
     */
    suspend fun reconcileSessionWithUsageStats(sessionId: String) = withContext(Dispatchers.IO) {
        metricsMutex.withLock {
            val session = repository.getSessionById(sessionId) ?: return@withLock

            if (!permissionChecker.hasUsageAccessPermission()) {
                repository.upsertSession(
                    session.copy(
                        usageStatsConfirmedBlockedLaunches = null,
                        usageStatsTotalForegroundEvents = null,
                        usageStatsDiscrepancyCount = null,
                        usageDataConfidence = DataConfidence.UNAVAILABLE_PERMISSION_MISSING.name
                    )
                )
                return@withLock
            }

            val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return@withLock

            val endWindow = session.completedOrStoppedAtMs
                ?: min(System.currentTimeMillis(), session.endTimestampMs)

            if (endWindow <= session.startTimestampMs) return@withLock

            val blockedSet = repository.snapshotStore.getCachedBlockedPackages()
            val allowedSet = repository.snapshotStore.getCachedAllowedPackages()

            val syntheticActiveSnapshot = ActiveSessionSnapshot(
                sessionId = session.sessionId,
                sessionType = runCatching { SessionType.valueOf(session.sessionType) }
                    .getOrDefault(SessionType.SELF_ARREST),
                state = SessionState.ACTIVE,
                goal = session.goal,
                allowedCategories = session.allowedCategoriesCsv.split(",")
                    .mapNotNull { runCatching { AllowedCategory.valueOf(it.trim()) }.getOrNull() }
                    .toSet()
                    .ifEmpty { setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS) },
                ruleMode = runCatching { BlockingRuleMode.valueOf(session.ruleMode) }
                    .getOrDefault(BlockingRuleMode.BLOCK_SELECTED),
                overlayStatusBar = session.overlayStatusBar,
                disableEmergencyExit = session.disableEmergencyExit,
                configuredDurationMs = session.configuredDurationMs,
                startTimestampMs = session.startTimestampMs,
                endTimestampMs = max(System.currentTimeMillis(), endWindow) + 60_000L,
                pausedAtTimestampMs = null,
                accumulatedPausedMs = session.accumulatedPausedMs,
                startElapsedRealtimeMs = session.startElapsedRealtimeMs,
                bootCountAtStart = session.bootCountAtStart,
                lastUpdatedWallMs = endWindow
            )

            val foregroundEventCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                UsageEvents.Event.MOVE_TO_FOREGROUND
            }

            var totalForegroundResumes = 0
            var blockedResumesInUsageStats = 0
            var previousResumedPackage: String? = null

            val usageEvents = runCatching {
                usageStatsManager.queryEvents(session.startTimestampMs, endWindow)
            }.getOrNull()

            if (usageEvents != null) {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == foregroundEventCode) {
                        val pkg = event.packageName ?: continue
                        if (pkg == previousResumedPackage) continue
                        previousResumedPackage = pkg
                        totalForegroundResumes++

                        val decision = rulesEvaluator.evaluate(
                            packageName = pkg,
                            session = syntheticActiveSnapshot,
                            blockedPackages = blockedSet,
                            allowedStudyPackages = allowedSet
                        )
                        if (decision is BlockingDecision.BlockApp) {
                            blockedResumesInUsageStats++
                        }
                    }
                }
            }

            val discrepancy = (blockedResumesInUsageStats - session.blockedAttempts).coerceAtLeast(0)
            val confidence = if (permissionChecker.isAccessibilityEnabledInSettings()) {
                DataConfidence.CONFIRMED_REALTIME_AND_USAGESTATS
            } else {
                DataConfidence.CONFIRMED_USAGESTATS_ONLY
            }

            val reconciledFailedBlocks = max(session.failedBlocks, discrepancy)

            repository.upsertSession(
                session.copy(
                    failedBlocks = reconciledFailedBlocks,
                    usageStatsConfirmedBlockedLaunches = blockedResumesInUsageStats,
                    usageStatsTotalForegroundEvents = totalForegroundResumes,
                    usageStatsDiscrepancyCount = discrepancy,
                    usageDataConfidence = confidence.name
                )
            )

            if (discrepancy > 0) {
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.USAGE_RECONCILIATION,
                    sessionId = sessionId,
                    severity = EventSeverity.WARNING,
                    confidence = confidence,
                    details = "UsageStats recorded $blockedResumesInUsageStats blocked-app foreground transitions vs ${session.blockedAttempts} real-time Accessibility detections (unblocked discrepancy=$discrepancy)."
                )
            }
        }
    }
}
