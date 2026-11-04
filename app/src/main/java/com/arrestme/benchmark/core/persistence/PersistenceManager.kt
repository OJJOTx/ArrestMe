package com.arrestme.benchmark.core.persistence

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.arrestme.benchmark.MainActivity
import com.arrestme.benchmark.R
import com.arrestme.benchmark.core.analytics.UsageMonitor
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.SessionController
import com.arrestme.benchmark.core.session.SessionLifecycleObserver
import com.arrestme.benchmark.data.local.DataConfidence
import com.arrestme.benchmark.data.local.EventSeverity
import com.arrestme.benchmark.data.repository.ArrestRepository
import java.util.concurrent.TimeUnit

/**
 * Coordinates process-death inspection, Force-Stop diagnostics, Android 14/15
 * specialUse ForegroundService lifecycle, BOOT_COMPLETED recovery, and WorkManager
 * expiry reconciliation without infinite restart loops or unsupported hacks.
 */
class PersistenceManager(
    context: Context,
    private val repository: ArrestRepository,
    private val sessionController: SessionController,
    private val permissionChecker: PermissionChecker,
    private val usageMonitor: UsageMonitor,
    private val antiCheatingMonitor: AntiCheatingMonitor
) : SessionLifecycleObserver {

    private val appContext = context.applicationContext

    init {
        ArrestForegroundService.ensureNotificationChannel(appContext)
        sessionController.registerObserver(this)
    }

    override fun onSessionUpdated(snapshot: ActiveSessionSnapshot?) {
        if (snapshot != null && snapshot.state.isInProgress) {
            startSessionForegroundServiceSafely(
                reason = "Session state updated to ${snapshot.state}"
            )
            scheduleExpiryWork(snapshot)
        } else {
            stopSessionForegroundService()
            cancelExpiryWork()
        }
    }

    /**
     * Called once on cold Application startup to inspect whether a previous process instance
     * died unexpectedly or was Force-Stopped during an active session, then reconciles state.
     */
    suspend fun inspectProcessStartupAndRecover() {
        val persistedSnapshot = repository.snapshotStore.readSnapshot()
            ?: repository.getActiveOrInProgressSession()?.let {
                repository.snapshotStore.readSnapshot()
            }

        var unexpectedDeath = false
        var exitSummary: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val exits = runCatching {
                am?.getHistoricalProcessExitReasons(appContext.packageName, 0, 5).orEmpty()
            }.getOrDefault(emptyList())

            val latestExit = exits.maxByOrNull { it.timestamp }
            val lastProcessedExitTs = repository.snapshotStore.getLastProcessedExitTimestampMs()

            if (latestExit != null && latestExit.timestamp > lastProcessedExitTs) {
                repository.snapshotStore.setLastProcessedExitTimestampMs(latestExit.timestamp)
                val reasonLabel = mapExitReason(latestExit.reason)
                val desc = latestExit.description?.takeIf { it.isNotBlank() } ?: "no OS description"

                val activeDuringExit = persistedSnapshot != null &&
                    persistedSnapshot.state.isInProgress &&
                    latestExit.timestamp >= persistedSnapshot.startTimestampMs

                if (activeDuringExit && persistedSnapshot != null) {
                    if (latestExit.reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
                        unexpectedDeath = true
                        exitSummary = "Android Force-Stop / User-Requested Stop detected ($reasonLabel: $desc)"
                        antiCheatingMonitor.recordEvent(
                            type = MonitorEventType.FORCE_STOP_DETECTED,
                            sessionId = persistedSnapshot.sessionId,
                            severity = EventSeverity.CRITICAL,
                            confidence = DataConfidence.CONFIRMED_REALTIME_ONLY,
                            details = "$exitSummary. Platform limitation: while force-stopped, Android halts Accessibility, ForegroundService, and receivers until manual reopen."
                        )
                        usageMonitor.recordServiceInterruption(
                            sessionId = persistedSnapshot.sessionId,
                            reason = exitSummary
                        )
                    } else if (latestExit.reason != ApplicationExitInfo.REASON_EXIT_SELF) {
                        unexpectedDeath = true
                        exitSummary = "Unexpected process termination ($reasonLabel: $desc)"
                        usageMonitor.recordServiceInterruption(
                            sessionId = persistedSnapshot.sessionId,
                            reason = exitSummary
                        )
                    }
                }
            }
        } else if (persistedSnapshot != null &&
            persistedSnapshot.state.isInProgress &&
            !repository.snapshotStore.wasLastShutdownClean()
        ) {
            unexpectedDeath = true
            exitSummary = "Estimated ungraceful process termination or force-stop prior to reopen (API < 30)"
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SERVICE_INTERRUPTION,
                sessionId = persistedSnapshot.sessionId,
                severity = EventSeverity.HIGH,
                confidence = DataConfidence.ESTIMATED,
                details = exitSummary
            )
            usageMonitor.recordServiceInterruption(
                sessionId = persistedSnapshot.sessionId,
                reason = exitSummary
            )
        }

        sessionController.restoreAndReconcile(
            unexpectedExitDetected = unexpectedDeath,
            exitDetails = exitSummary
        )
    }

    suspend fun handleSystemRecoveryBroadcast(action: String) {
        val snapshot = repository.snapshotStore.readSnapshot()
        antiCheatingMonitor.recordEvent(
            type = MonitorEventType.BOOT_RECOVERY_CHECK,
            sessionId = snapshot?.sessionId,
            severity = EventSeverity.INFO,
            details = "Received $action; inProgressSession=${snapshot?.sessionId ?: "none"}"
        )

        sessionController.restoreAndReconcile(
            unexpectedExitDetected = snapshot?.state?.isInProgress == true,
            exitDetails = "Device reboot or package update ($action)"
        )

        val postReconcile = sessionController.activeSession.value
        if (postReconcile != null && postReconcile.state.isInProgress) {
            scheduleExpiryWork(postReconcile)
            postRecoveryNotificationIfPermitted(postReconcile, action)
        }
    }

    private fun startSessionForegroundServiceSafely(reason: String) {
        val intent = Intent(appContext, ArrestForegroundService::class.java).apply {
            action = ArrestForegroundService.ACTION_START_OR_SYNC
        }
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            // Handles Android 12–15 ForegroundServiceStartNotAllowedException when triggered from restricted background states (e.g., BOOT_COMPLETED on Android 15)
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SERVICE_INTERRUPTION,
                severity = EventSeverity.WARNING,
                details = "OS restricted starting specialUse ForegroundService ($reason): ${e.javaClass.simpleName}: ${e.message}. Relying on AccessibilityService + WorkManager."
            )
        }
    }

    private fun stopSessionForegroundService() {
        val stopIntent = Intent(appContext, ArrestForegroundService::class.java).apply {
            action = ArrestForegroundService.ACTION_STOP_SERVICE
        }
        runCatching {
            appContext.stopService(stopIntent)
        }
    }

    private fun scheduleExpiryWork(snapshot: ActiveSessionSnapshot) {
        val delayMs = (snapshot.computeRemainingMs() + 1_500L).coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<SessionRecoveryWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                SessionRecoveryWorker.UNIQUE_EXPIRY_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    private fun cancelExpiryWork() {
        runCatching {
            WorkManager.getInstance(appContext)
                .cancelUniqueWork(SessionRecoveryWorker.UNIQUE_EXPIRY_WORK_NAME)
        }
    }

    private fun postRecoveryNotificationIfPermitted(snapshot: ActiveSessionSnapshot, action: String) {
        if (!permissionChecker.hasNotificationPermission()) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            appContext,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remainingSec = snapshot.computeRemainingMs() / 1000L
        val notif = NotificationCompat.Builder(appContext, ArrestForegroundService.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentTitle("Arrest Session Recovered (${snapshot.state.name})")
            .setContentText("Restored after ${action.substringAfterLast('.')} • ${remainingSec}s remaining")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(ArrestForegroundService.RECOVERY_NOTIFICATION_ID, notif)
    }

    private fun mapExitReason(reason: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "UNKNOWN"
        return when (reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED (Force-Stop / User Stop)"
            ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            else -> "REASON_CODE_$reason"
        }
    }
}
