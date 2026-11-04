package com.arrestme.benchmark.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class AutoStartStatus(val label: String) {
    AVAILABLE_UNVERIFIED("Available (OEM Screen Detected — Unverified by OS)"),
    NOT_APPLICABLE_OR_UNKNOWN("Unknown / Standard Android (No OEM Manager Detected)")
}

data class PermissionStatusSnapshot(
    val overlayGranted: Boolean,
    val accessibilitySettingEnabled: Boolean,
    val accessibilityRuntimeConnected: Boolean,
    val usageAccessGranted: Boolean,
    val notificationsGranted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val autoStartStatus: AutoStartStatus,
    val detectedOemAutoStartLabel: String?
) {
    val isCoreBlockingReady: Boolean
        get() = overlayGranted && accessibilitySettingEnabled && accessibilityRuntimeConnected

    val accessibilityDisplayLabel: String
        get() = when {
            accessibilitySettingEnabled && accessibilityRuntimeConnected -> "Enabled (Active)"
            accessibilitySettingEnabled && !accessibilityRuntimeConnected -> "Enabled in Settings (Service Disconnected)"
            else -> "Disabled"
        }

    fun toSummaryMap(): Map<String, String> = linkedMapOf(
        "overlay_permission" to if (overlayGranted) "Granted" else "Missing",
        "accessibility_service" to accessibilityDisplayLabel,
        "usage_access" to if (usageAccessGranted) "Granted" else "Missing",
        "notifications" to if (notificationsGranted) "Granted" else "Missing",
        "battery_optimization" to if (ignoringBatteryOptimizations) {
            "Unrestricted (Ignoring Optimizations)"
        } else {
            "Optimized (Standard Background Restrictions)"
        },
        "auto_start" to autoStartStatus.label
    )

    fun toCompactString(): String {
        return "overlay=${if (overlayGranted) "OK" else "MISSING"}," +
            "a11y=${if (accessibilitySettingEnabled && accessibilityRuntimeConnected) "OK" else if (accessibilitySettingEnabled) "UNBOUND" else "DISABLED"}," +
            "usage=${if (usageAccessGranted) "OK" else "MISSING"}," +
            "notif=${if (notificationsGranted) "OK" else "MISSING"}," +
            "battery=${if (ignoringBatteryOptimizations) "UNRESTRICTED" else "OPTIMIZED"}"
    }
}

class PermissionChecker(
    private val context: Context,
    private val accessibilityClassName: String,
    private val isAccessibilityConnectedProvider: () -> Boolean
) {
    private val appContext = context.applicationContext

    fun inspectAll(): PermissionStatusSnapshot {
        val overlay = hasOverlayPermission()
        val a11ySetting = isAccessibilityEnabledInSettings()
        val a11yConnected = isAccessibilityConnectedProvider()
        val usage = hasUsageAccessPermission()
        val notifs = hasNotificationPermission()
        val battery = isIgnoringBatteryOptimizations()
        val oemMatch = findOemAutoStartIntent()

        return PermissionStatusSnapshot(
            overlayGranted = overlay,
            accessibilitySettingEnabled = a11ySetting,
            accessibilityRuntimeConnected = a11yConnected,
            usageAccessGranted = usage,
            notificationsGranted = notifs,
            ignoringBatteryOptimizations = battery,
            autoStartStatus = if (oemMatch != null) {
                AutoStartStatus.AVAILABLE_UNVERIFIED
            } else {
                AutoStartStatus.NOT_APPLICABLE_OR_UNKNOWN
            },
            detectedOemAutoStartLabel = oemMatch?.first
        )
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    fun isAccessibilityEnabledInSettings(): Boolean {
        val expectedComponent = ComponentName(appContext.packageName, accessibilityClassName)
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedFull = expectedComponent.flattenToString()
        val expectedShort = expectedComponent.flattenToShortString()
        return enabledServices.split(':').any { entry ->
            entry.equals(expectedFull, ignoreCase = true) ||
                entry.equals(expectedShort, ignoreCase = true)
        }
    }

    fun hasUsageAccessPermission(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> {
                appContext.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            }
            else -> false
        }
    }

    fun hasNotificationPermission(): Boolean {
        val enabledInManager = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        if (!enabledInManager) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun buildOverlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildUsageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildNotificationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildAppDetailsSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildAutoStartSettingsIntent(): Intent {
        val oemMatch = findOemAutoStartIntent()
        return (oemMatch?.second ?: buildAppDetailsSettingsIntent())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun findOemAutoStartIntent(): Pair<String, Intent>? {
        val pm = appContext.packageManager
        val candidates = listOf(
            "Xiaomi / HyperOS Auto-Start" to ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            "ColorOS / Realme Startup Manager" to ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            "OPlus Startup Manager" to ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity"
            ),
            "Vivo Background Startup" to ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            "iQOO Background Startup" to ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            "Huawei App Launch Manager" to ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            "OnePlus Auto-Launch" to ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ),
            "ASUS Auto-Start Manager" to ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity"
            ),
            "Samsung Device Care Battery" to ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
        )

        for ((label, component) in candidates) {
            val intent = Intent().setComponent(component)
            val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved != null) {
                return label to intent
            }
        }
        return null
    }
}
