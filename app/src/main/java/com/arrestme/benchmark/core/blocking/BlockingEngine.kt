package com.arrestme.benchmark.core.blocking

import android.os.SystemClock
import com.arrestme.benchmark.core.analytics.UsageMonitor
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.overlay.OverlayManager
import com.arrestme.benchmark.core.overlay.OverlayRequest
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.session.SessionController
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.data.local.EventSeverity
import com.arrestme.benchmark.data.repository.ArrestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BlockingEngine(
    private val repository: ArrestRepository,
    private val sessionController: SessionController,
    private val rulesEvaluator: BlockingRulesEvaluator,
    private val overlayManager: OverlayManager,
    private val usageMonitor: UsageMonitor,
    private val permissionChecker: PermissionChecker,
    private val antiCheatingMonitor: AntiCheatingMonitor,
    private val scope: CoroutineScope
) {
    private val switchTimestampsMs = ArrayDeque<Long>()
    private var lastObservedDistinctPackage: String? = null
    private var lastRapidSwitchAlertMs: Long = 0L

    private val _lastForegroundPackage = MutableStateFlow<String?>(null)
    val lastForegroundPackage: StateFlow<String?> = _lastForegroundPackage.asStateFlow()

    @Synchronized
    fun onForegroundPackageChanged(
        packageName: String,
        detectionElapsedNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        if (packageName.isBlank()) return

        val session = sessionController.activeSession.value
            ?: repository.snapshotStore.readSnapshot()

        if (session == null || session.state != SessionState.ACTIVE) {
            if (overlayManager.isOverlayShowing.value) {
                overlayManager.removeOverlay("No ACTIVE session while observing $packageName")
            }
            _lastForegroundPackage.value = packageName
            return
        }

        val nowWall = System.currentTimeMillis()
        if (session.hasExpired(nowWall)) {
            overlayManager.removeOverlay("Session expired upon foreground transition")
            scope.launch {
                sessionController.checkAndCompleteIfExpired()
            }
            return
        }

        val blockedPackages = repository.snapshotStore.getCachedBlockedPackages()
        val allowedStudyPackages = repository.snapshotStore.getCachedAllowedPackages()

        val decision = rulesEvaluator.evaluate(
            packageName = packageName,
            session = session,
            blockedPackages = blockedPackages,
            allowedStudyPackages = allowedStudyPackages
        )

        if (decision is BlockingDecision.IgnoreTransientWindow) {
            return
        }

        _lastForegroundPackage.value = packageName
        trackRapidAppSwitching(packageName = packageName, nowWallMs = nowWall, sessionId = session.sessionId)

        when (decision) {
            is BlockingDecision.IgnoreTransientWindow -> Unit

            is BlockingDecision.AllowArrestMeApp,
            is BlockingDecision.AllowHomeLauncher,
            is BlockingDecision.AllowEssentialSystem,
            is BlockingDecision.AllowPermittedApp,
            is BlockingDecision.AllowUnlistedApp -> {
                if (overlayManager.isOverlayShowing.value) {
                    overlayManager.removeOverlay("Switched to permitted foreground package: $packageName")
                }
                usageMonitor.recordRealtimeForegroundTransition(
                    sessionId = session.sessionId,
                    packageName = packageName,
                    wasBlocked = false,
                    overlayTriggered = false
                )
            }

            is BlockingDecision.BlockApp -> {
                // Avoid duplicate overlay creation and duplicate counting if already covering this exact package
                if (overlayManager.isShowingForPackage(packageName)) {
                    return
                }

                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.BLOCKED_APP_ATTEMPT,
                    packageName = packageName,
                    sessionId = session.sessionId,
                    severity = EventSeverity.WARNING,
                    details = "Blocked foreground app detected: ${decision.appLabel} ($packageName)"
                )

                val allowedPairs = allowedStudyPackages
                    .sorted()
                    .map { pkg -> pkg to rulesEvaluator.resolveAppLabel(pkg) }

                val request = OverlayRequest(
                    session = session,
                    blockedPackageName = packageName,
                    blockedAppLabel = decision.appLabel,
                    blockReason = decision.reason,
                    allowedStudyApps = allowedPairs,
                    detectionElapsedNanos = detectionElapsedNanos
                )

                overlayManager.showOrUpdateOverlay(request) { succeeded, latencyMs, errorReason ->
                    usageMonitor.recordBlockedAttemptResult(
                        sessionId = session.sessionId,
                        packageName = packageName,
                        overlaySucceeded = succeeded,
                        activationLatencyMs = latencyMs,
                        failureReason = errorReason
                    )
                    usageMonitor.recordRealtimeForegroundTransition(
                        sessionId = session.sessionId,
                        packageName = packageName,
                        wasBlocked = true,
                        overlayTriggered = succeeded
                    )
                    if (!succeeded && !permissionChecker.hasOverlayPermission()) {
                        usageMonitor.recordPermissionFailure(
                            sessionId = session.sessionId,
                            reason = errorReason ?: "Missing SYSTEM_ALERT_WINDOW during block attempt",
                            eventType = MonitorEventType.OVERLAY_PERMISSION_LOST
                        )
                        scope.launch {
                            sessionController.markInterrupted(
                                "Overlay activation failed due to missing SYSTEM_ALERT_WINDOW permission"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun trackRapidAppSwitching(packageName: String, nowWallMs: Long, sessionId: String) {
        if (packageName == lastObservedDistinctPackage) return
        lastObservedDistinctPackage = packageName

        switchTimestampsMs.addLast(nowWallMs)
        while (switchTimestampsMs.isNotEmpty() && nowWallMs - switchTimestampsMs.first() > RAPID_SWITCH_WINDOW_MS) {
            switchTimestampsMs.removeFirst()
        }

        if (switchTimestampsMs.size >= RAPID_SWITCH_THRESHOLD &&
            nowWallMs - lastRapidSwitchAlertMs >= RAPID_SWITCH_COOLDOWN_MS
        ) {
            lastRapidSwitchAlertMs = nowWallMs
            usageMonitor.recordRapidAppSwitch(
                sessionId = sessionId,
                switchCount = switchTimestampsMs.size,
                windowMs = RAPID_SWITCH_WINDOW_MS,
                latestPackage = packageName
            )
        }
    }

    companion object {
        private const val RAPID_SWITCH_WINDOW_MS = 6_000L
        private const val RAPID_SWITCH_THRESHOLD = 5
        private const val RAPID_SWITCH_COOLDOWN_MS = 6_000L
    }
}
