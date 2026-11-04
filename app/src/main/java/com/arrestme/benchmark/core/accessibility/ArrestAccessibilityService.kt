package com.arrestme.benchmark.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.arrestme.benchmark.ArrestMeApp
import com.arrestme.benchmark.MainActivity
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.data.local.EventSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Minimal foreground-package monitor using TYPE_WINDOW_STATE_CHANGED.
 *
 * Privacy & Control Guarantees:
 * - canRetrieveWindowContent is explicitly false in XML config.
 * - Never inspects text, input fields, notifications, or personal content.
 * - Never blocks Settings, Permission Controller, or Package Uninstaller.
 */
class ArrestAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: Job? = null

    private var lastDispatchedPackage: String? = null
    private var lastDispatchedElapsedMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true

        val app = application as? ArrestMeApp ?: return
        app.antiCheatingMonitor.recordEvent(
            type = MonitorEventType.ACCESSIBILITY_CONNECTED,
            severity = EventSeverity.INFO,
            details = "ArrestAccessibilityService connected by Android OS"
        )

        serviceScope.launch {
            app.sessionController.restoreAndReconcile(
                unexpectedExitDetected = false,
                exitDetails = "AccessibilityService bound"
            )
            // If session was INTERRUPTED solely due to missing Accessibility and permissions are now ready, resume
            val current = app.sessionController.activeSession.value
            if (current?.state == SessionState.INTERRUPTED &&
                app.permissionChecker.inspectAll().isCoreBlockingReady
            ) {
                app.sessionController.resumeSession(
                    "Core blocking permissions restored on AccessibilityService connect"
                )
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val cls = event.className?.toString().orEmpty()

        // Ignore our own overlay window or internal dialogs; only pass MainActivity for our own package
        if (pkg == packageName && cls != MainActivity::class.java.name && !cls.endsWith(".MainActivity")) {
            return
        }

        // Ignore soft keyboard popup window class across OEMs
        if (cls == "android.inputmethodservice.SoftInputWindow") {
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()

        // Suppress duplicate window-state events for the same foreground package within debounce interval
        if (pkg == lastDispatchedPackage && (nowElapsedMs - lastDispatchedElapsedMs) < SAME_PACKAGE_SUPPRESS_MS) {
            return
        }

        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(BURST_DEBOUNCE_MS)
            lastDispatchedPackage = pkg
            lastDispatchedElapsedMs = SystemClock.elapsedRealtime()

            val app = application as? ArrestMeApp ?: return@launch
            val triggerStartNanos = SystemClock.elapsedRealtimeNanos()
            app.blockingEngine.onForegroundPackageChanged(
                packageName = pkg,
                detectionElapsedNanos = triggerStartNanos
            )
        }
    }

    override fun onInterrupt() {
        val app = application as? ArrestMeApp ?: return
        app.antiCheatingMonitor.recordEvent(
            type = MonitorEventType.SERVICE_INTERRUPTION,
            severity = EventSeverity.WARNING,
            details = "AccessibilityService received onInterrupt() callback"
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isConnected.value = false
        debounceJob?.cancel()

        val app = application as? ArrestMeApp
        if (app != null) {
            val enabledInSettings = app.permissionChecker.isAccessibilityEnabledInSettings()
            val activeSession = app.sessionController.activeSession.value

            if (!enabledInSettings) {
                app.usageMonitor.recordPermissionFailure(
                    sessionId = activeSession?.sessionId,
                    reason = "Accessibility Service disabled by user or OS in Settings",
                    eventType = MonitorEventType.ACCESSIBILITY_PERMISSION_LOST
                )
            } else {
                app.antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.ACCESSIBILITY_DISCONNECTED,
                    sessionId = activeSession?.sessionId,
                    severity = EventSeverity.HIGH,
                    details = "AccessibilityService unbound unexpectedly while still enabled in Settings"
                )
            }

            if (activeSession?.state == SessionState.ACTIVE) {
                app.overlayManager.removeOverlay("AccessibilityService unbound")
                app.appScope.launch {
                    app.sessionController.markInterrupted(
                        if (!enabledInSettings) {
                            "Accessibility permission revoked during active session"
                        } else {
                            "AccessibilityService unexpectedly unbound by system"
                        }
                    )
                }
            }
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isConnected.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SAME_PACKAGE_SUPPRESS_MS = 350L
        private const val BURST_DEBOUNCE_MS = 35L

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
