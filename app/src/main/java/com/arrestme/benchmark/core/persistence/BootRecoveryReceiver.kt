package com.arrestme.benchmark.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arrestme.benchmark.ArrestMeApp
import kotlinx.coroutines.launch

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val app = context.applicationContext as? ArrestMeApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                app.persistenceManager.handleSystemRecoveryBroadcast(action)
                app.cloudCommandCoordinator.evaluateDueSchedules()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
