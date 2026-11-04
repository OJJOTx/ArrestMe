package com.arrestme.benchmark.core.blocking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Immutable safety classification for system, emergency, dialer, launcher, and IME packages.
 *
 * Ensures Arrest Me never obstructs emergency calling, permission management,
 * system settings, or application uninstallation.
 */
class SystemSafetyAllowlist(context: Context) {
    private val appContext = context.applicationContext

    private val staticEssentialPackages = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.safetyhub"
    )

    private val transientSystemWindowPackages = setOf(
        "android",
        "com.android.systemui"
    )

    fun isOwnPackage(packageName: String): Boolean = packageName == appContext.packageName

    fun isTransientSystemOrImePackage(packageName: String): Boolean {
        if (packageName in transientSystemWindowPackages) return true
        return packageName in queryEnabledImePackages()
    }

    fun isHomeLauncher(packageName: String): Boolean {
        return packageName in queryHomeLauncherPackages()
    }

    fun isEssentialSafetyOrDialer(packageName: String): Boolean {
        if (packageName in staticEssentialPackages) return true
        if (packageName in queryDialerPackages()) return true
        return false
    }

    fun queryDialerPackages(): Set<String> {
        val result = mutableSetOf<String>()
        val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecom?.defaultDialerPackage?.takeIf { it.isNotBlank() }?.let { result.add(it) }

        val dialIntent = Intent(Intent.ACTION_DIAL)
        val resolved = appContext.packageManager.queryIntentActivities(
            dialIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        for (info in resolved) {
            info.activityInfo?.packageName?.let { result.add(it) }
        }
        return result
    }

    fun queryHomeLauncherPackages(): Set<String> {
        val result = mutableSetOf<String>()
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = appContext.packageManager.queryIntentActivities(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg != "com.android.settings") {
                result.add(pkg)
            }
        }
        return result
    }

    fun queryEnabledImePackages(): Set<String> {
        val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptySet()
        return runCatching {
            imm.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }
}
