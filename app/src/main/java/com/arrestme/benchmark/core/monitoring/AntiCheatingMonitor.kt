package com.arrestme.benchmark.core.monitoring

import android.util.Log
import com.arrestme.benchmark.data.local.DataConfidence
import com.arrestme.benchmark.data.local.DiagnosticEventEntity
import com.arrestme.benchmark.data.local.EventSeverity
import com.arrestme.benchmark.data.repository.ArrestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class MonitorEventType {
    SESSION_PREPARING,
    SESSION_STARTED,
    SESSION_STATE_CHANGED,
    SESSION_COMPLETED,
    EMERGENCY_EXIT,
    BLOCKED_APP_ATTEMPT,
    OVERLAY_ACTIVATED,
    OVERLAY_REMOVED,
    OVERLAY_FAILED,
    RAPID_APP_SWITCHING,
    ACCESSIBILITY_CONNECTED,
    ACCESSIBILITY_DISCONNECTED,
    ACCESSIBILITY_PERMISSION_LOST,
    OVERLAY_PERMISSION_LOST,
    SERVICE_INTERRUPTION,
    SESSION_INTERRUPTION,
    APP_RESTART_AFTER_INTERRUPTION,
    FORCE_STOP_DETECTED,
    BOOT_RECOVERY_CHECK,
    CLOCK_ANOMALY_DETECTED,
    USAGE_RECONCILIATION
}

/**
 * Self-accountability and diagnostic monitor for Arrest Engine benchmarking.
 *
 * Records only transparent, observable lifecycle, permission, and foreground-package
 * events. Never collects screen contents, keystrokes, messages, or personal data,
 * and never obstructs user control over system settings or uninstallation.
 */
class AntiCheatingMonitor(
    private val repository: ArrestRepository,
    private val scope: CoroutineScope
) {
    fun recordEvent(
        type: MonitorEventType,
        details: String,
        severity: EventSeverity = EventSeverity.INFO,
        packageName: String? = null,
        sessionId: String? = repository.snapshotStore.readSnapshot()?.sessionId,
        confidence: DataConfidence = DataConfidence.CONFIRMED_REALTIME_ONLY
    ) {
        val now = System.currentTimeMillis()
        val logMsg = "[${type.name}] session=${sessionId ?: "none"} pkg=${packageName ?: "-"} | $details"
        when (severity) {
            EventSeverity.INFO -> Log.i(TAG, logMsg)
            EventSeverity.WARNING -> Log.w(TAG, logMsg)
            EventSeverity.HIGH, EventSeverity.CRITICAL -> Log.e(TAG, logMsg)
        }

        scope.launch(Dispatchers.IO) {
            repository.insertDiagnosticEvent(
                DiagnosticEventEntity(
                    timestampMs = now,
                    eventType = type.name,
                    packageName = packageName,
                    sessionId = sessionId,
                    details = details,
                    severity = severity.name,
                    sourceConfidence = confidence.name
                )
            )
        }
    }

    companion object {
        private const val TAG = "ArrestEngine"
    }
}
