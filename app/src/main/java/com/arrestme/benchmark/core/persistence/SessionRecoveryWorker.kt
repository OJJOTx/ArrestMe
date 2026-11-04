package com.arrestme.benchmark.core.persistence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arrestme.benchmark.ArrestMeApp

/**
 * Deferred WorkManager task that verifies session expiration and UsageStats reconciliation
 * even if the UI is closed or Android background limits restricted the ForegroundService.
 */
class SessionRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ArrestMeApp ?: return Result.success()
        app.sessionController.restoreAndReconcile(
            unexpectedExitDetected = false,
            exitDetails = "WorkManager scheduled session reconciliation"
        )
        app.sessionController.checkAndCompleteIfExpired()
        return Result.success()
    }

    companion object {
        const val UNIQUE_EXPIRY_WORK_NAME = "arrest_session_expiry_reconcile"
    }
}
