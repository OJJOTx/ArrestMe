package com.arrestme.benchmark.ui.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.permissions.PermissionStatusSnapshot

@Composable
fun PermissionsScreen(
    status: PermissionStatusSnapshot,
    permissionChecker: PermissionChecker,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefresh()
    }

    fun launchSafely(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(permissionChecker.buildAppDetailsSettingsIntent()) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Permissions Dashboard",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onRefresh) {
                Text("Re-verify")
            }
        }

        Text(
            text = "Statuses below reflect live OS verification only. All permissions require explicit user action in Android Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.accessibilitySettingEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Android 13+ Sideloaded APK Note (Restricted Settings)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFDE047)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "If Accessibility shows 'Restricted setting' when installing a debug APK: " +
                            "1) Tap 'Open App Info' below -> 2) Tap the 3-dot menu (top-right) -> " +
                            "3) Tap 'Allow restricted settings' -> 4) Return and enable Accessibility.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { launchSafely(permissionChecker.buildAppDetailsSettingsIntent()) }) {
                        Text("Open App Info (For Restricted Settings)")
                    }
                }
            }
        }

        PermissionItemCard(
            title = "1. Overlay Permission (SYSTEM_ALERT_WINDOW)",
            statusText = if (status.overlayGranted) "Granted" else "Missing",
            isVerifiedPositive = status.overlayGranted,
            explanation = "Required to display the TYPE_APPLICATION_OVERLAY blocking screen above user-selected blocked apps during an active Self Arrest session.",
            buttonLabel = "Open Overlay Settings",
            onAction = { launchSafely(permissionChecker.buildOverlaySettingsIntent()) }
        )

        PermissionItemCard(
            title = "2. Accessibility Foreground Monitor",
            statusText = status.accessibilityDisplayLabel,
            isVerifiedPositive = status.accessibilitySettingEnabled && status.accessibilityRuntimeConnected,
            explanation = "Required to detect foreground package transitions (TYPE_WINDOW_STATE_CHANGED) with low latency. Screen content reading is disabled (canRetrieveWindowContent=false).",
            buttonLabel = "Open Accessibility Settings",
            onAction = { launchSafely(permissionChecker.buildAccessibilitySettingsIntent()) }
        )

        PermissionItemCard(
            title = "3. Usage Access (UsageStatsManager)",
            statusText = if (status.usageAccessGranted) "Granted" else "Missing",
            isVerifiedPositive = status.usageAccessGranted,
            explanation = "Used to reconcile OS-recorded foreground app launches against real-time Accessibility observations and audit missed blocks.",
            buttonLabel = "Open Usage Access Settings",
            onAction = { launchSafely(permissionChecker.buildUsageAccessSettingsIntent()) }
        )

        PermissionItemCard(
            title = "4. Notifications",
            statusText = if (status.notificationsGranted) "Granted" else "Missing",
            isVerifiedPositive = status.notificationsGranted,
            explanation = "Displays the ongoing active Self Arrest countdown notification (Foreground Service) and reboot recovery status.",
            buttonLabel = "Enable Notifications",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.notificationsGranted) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launchSafely(permissionChecker.buildNotificationSettingsIntent())
                }
            }
        )

        PermissionItemCard(
            title = "5. Battery Optimization",
            statusText = if (status.ignoringBatteryOptimizations) {
                "Unrestricted (Ignoring Optimizations)"
            } else {
                "Optimized (Restricted)"
            },
            isVerifiedPositive = status.ignoringBatteryOptimizations,
            explanation = "Setting battery usage to Unrestricted reduces premature killing of the Accessibility monitor and session Foreground Service.",
            buttonLabel = "Open Battery Optimization Settings",
            onAction = { launchSafely(permissionChecker.buildBatteryOptimizationSettingsIntent()) }
        )

        PermissionItemCard(
            title = "6. OEM Auto-Start",
            statusText = status.autoStartStatus.label +
                (status.detectedOemAutoStartLabel?.let { " — $it" } ?: ""),
            isVerifiedPositive = false,
            explanation = "On Xiaomi/ColorOS/Vivo/Huawei OEM ROMs, enabling Auto-Start helps Accessibility & BOOT_COMPLETED recover after reboots. Android provides no API to verify whether an OEM toggle is switched on, so status is never claimed as 'Configured'.",
            buttonLabel = if (status.detectedOemAutoStartLabel != null) {
                "Open ${status.detectedOemAutoStartLabel}"
            } else {
                "Open App Info"
            },
            onAction = { launchSafely(permissionChecker.buildAutoStartSettingsIntent()) }
        )
    }
}

@Composable
private fun PermissionItemCard(
    title: String,
    statusText: String,
    isVerifiedPositive: Boolean,
    explanation: String,
    buttonLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Status: $statusText",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isVerifiedPositive) Color(0xFF34D399) else Color(0xFFFBBF24)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onAction) {
                Text(buttonLabel)
            }
        }
    }
}
