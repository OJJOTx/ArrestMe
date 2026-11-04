package com.arrestme.benchmark.core.persistence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.arrestme.benchmark.ArrestMeApp
import com.arrestme.benchmark.MainActivity
import com.arrestme.benchmark.R
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class ArrestForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as? ArrestMeApp
        val snapshot = app?.sessionController?.activeSession?.value
            ?: app?.repository?.snapshotStore?.readSnapshot()

        if (snapshot == null || !snapshot.state.isInProgress) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(this, snapshot)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        startNotificationUpdater()
        return START_STICKY
    }

    private fun startNotificationUpdater() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            while (isActive) {
                val app = application as? ArrestMeApp ?: break
                app.sessionController.checkAndCompleteIfExpired()
                val snap = app.sessionController.activeSession.value
                if (snap == null || !snap.state.isInProgress) {
                    stopForegroundCompat()
                    stopSelf()
                    break
                }
                if (app.permissionChecker.hasNotificationPermission()) {
                    nm?.notify(NOTIFICATION_ID, buildNotification(this@ArrestForegroundService, snap))
                }
                delay(5_000L)
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID_SESSION = "arrest_active_session_channel"
        const val CHANNEL_ID_ALERTS = "arrest_recovery_alerts_channel"
        const val NOTIFICATION_ID = 4101
        const val RECOVERY_NOTIFICATION_ID = 4102
        const val ACTION_START_OR_SYNC = "com.arrestme.benchmark.action.START_OR_SYNC_FGS"
        const val ACTION_STOP_SERVICE = "com.arrestme.benchmark.action.STOP_FGS"

        fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val sessionChannel = NotificationChannel(
                CHANNEL_ID_SESSION,
                "Active Arrest Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows remaining duration and status while a Self Arrest session is active."
                setShowBadge(false)
            }
            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Arrest Recovery & Interruptions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a session recovers after reboot or experiences permission interruptions."
            }
            nm.createNotificationChannel(sessionChannel)
            nm.createNotificationChannel(alertsChannel)
        }

        fun buildNotification(context: Context, snapshot: ActiveSessionSnapshot): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val remainingMs = snapshot.computeRemainingMs()
            val totalSeconds = (remainingMs + 999L) / 1000L
            val formatted = String.format(
                Locale.US,
                "%02d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L
            )

            return NotificationCompat.Builder(context, CHANNEL_ID_SESSION)
                .setSmallIcon(R.drawable.ic_shield_notification)
                .setContentTitle("Arrest Session ${snapshot.state.name} • $formatted left")
                .setContentText("Goal: ${snapshot.goal}")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
