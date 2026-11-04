package com.arrestme.benchmark.core.blocking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionState

sealed class BlockingDecision {
    data class IgnoreTransientWindow(val reason: String) : BlockingDecision()
    data object AllowArrestMeApp : BlockingDecision()
    data object AllowHomeLauncher : BlockingDecision()
    data class AllowEssentialSystem(val reason: String) : BlockingDecision()
    data class AllowPermittedApp(val reason: String) : BlockingDecision()
    data object AllowUnlistedApp : BlockingDecision()
    data class BlockApp(
        val packageName: String,
        val appLabel: String,
        val reason: String
    ) : BlockingDecision()
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isEssentialProtected: Boolean,
    val isHomeLauncher: Boolean
)

class BlockingRulesEvaluator(
    context: Context,
    val safetyAllowlist: SystemSafetyAllowlist
) {
    private val appContext = context.applicationContext

    fun evaluate(
        packageName: String,
        session: ActiveSessionSnapshot?,
        blockedPackages: Set<String>,
        allowedStudyPackages: Set<String>
    ): BlockingDecision {
        if (packageName.isBlank()) {
            return BlockingDecision.IgnoreTransientWindow("Empty package name")
        }

        if (safetyAllowlist.isOwnPackage(packageName)) {
            return BlockingDecision.AllowArrestMeApp
        }

        if (safetyAllowlist.isTransientSystemOrImePackage(packageName)) {
            return BlockingDecision.IgnoreTransientWindow("Transient system UI or IME window ($packageName)")
        }

        if (safetyAllowlist.isEssentialSafetyOrDialer(packageName)) {
            return BlockingDecision.AllowEssentialSystem(
                "Protected essential/emergency/dialer/settings package ($packageName)"
            )
        }

        if (safetyAllowlist.isHomeLauncher(packageName)) {
            return BlockingDecision.AllowHomeLauncher
        }

        if (session == null || session.state != SessionState.ACTIVE || session.hasExpired()) {
            return BlockingDecision.AllowUnlistedApp
        }

        // Explicitly allowed study apps take precedence when Study Apps category is active
        if (AllowedCategory.STUDY_APPS in session.allowedCategories &&
            packageName in allowedStudyPackages
        ) {
            return BlockingDecision.AllowPermittedApp("Explicitly permitted Study App")
        }

        val appLabel = resolveAppLabel(packageName)

        return when (session.ruleMode) {
            BlockingRuleMode.BLOCK_SELECTED -> {
                if (packageName in blockedPackages) {
                    BlockingDecision.BlockApp(
                        packageName = packageName,
                        appLabel = appLabel,
                        reason = "$appLabel ($packageName) is on the active Self Arrest Blocked List."
                    )
                } else {
                    BlockingDecision.AllowUnlistedApp
                }
            }
            BlockingRuleMode.STRICT_ALLOWLIST -> {
                BlockingDecision.BlockApp(
                    packageName = packageName,
                    appLabel = appLabel,
                    reason = "$appLabel ($packageName) is outside allowed categories (${
                        session.allowedCategories.joinToString(", ") { it.label }
                    })."
                )
            }
        }
    }

    fun resolveAppLabel(packageName: String): String {
        val pm = appContext.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    fun queryInstalledLaunchableApps(): List<InstalledAppInfo> {
        val pm = appContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val homePackages = safetyAllowlist.queryHomeLauncherPackages()
        val dialerPackages = safetyAllowlist.queryDialerPackages()

        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == appContext.packageName) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                InstalledAppInfo(
                    packageName = pkg,
                    label = label,
                    isEssentialProtected = safetyAllowlist.isEssentialSafetyOrDialer(pkg) || pkg in dialerPackages,
                    isHomeLauncher = pkg in homePackages
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<InstalledAppInfo> { it.isEssentialProtected || it.isHomeLauncher }
                    .thenBy { it.label.lowercase() }
            )
    }
}
