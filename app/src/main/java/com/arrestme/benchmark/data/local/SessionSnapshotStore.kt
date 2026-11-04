package com.arrestme.benchmark.data.local

import android.content.Context
import android.content.SharedPreferences
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType

/**
 * Synchronous, low-latency mirror of the active session and blocking rules.
 *
 * Used alongside Room so that BootRecoveryReceiver, AccessibilityService cold-binds,
 * and process-death inspection can immediately inspect the active session state
 * without waiting for database initialization.
 */
class SessionSnapshotStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveSnapshot(snapshot: ActiveSessionSnapshot?) {
        val editor = prefs.edit()
        if (snapshot == null || snapshot.state == SessionState.IDLE) {
            editor.putBoolean(KEY_HAS_SESSION, false)
            editor.putString(KEY_STATE, SessionState.IDLE.name)
        } else {
            editor.putBoolean(KEY_HAS_SESSION, true)
            editor.putString(KEY_SESSION_ID, snapshot.sessionId)
            editor.putString(KEY_SESSION_TYPE, snapshot.sessionType.name)
            editor.putString(KEY_STATE, snapshot.state.name)
            editor.putString(KEY_GOAL, snapshot.goal)
            editor.putString(
                KEY_ALLOWED_CATEGORIES,
                snapshot.allowedCategories.joinToString(",") { it.name }
            )
            editor.putString(KEY_RULE_MODE, snapshot.ruleMode.name)
            editor.putLong(KEY_CONFIGURED_DURATION_MS, snapshot.configuredDurationMs)
            editor.putLong(KEY_START_TS, snapshot.startTimestampMs)
            editor.putLong(KEY_END_TS, snapshot.endTimestampMs)
            editor.putLong(KEY_PAUSED_AT_TS, snapshot.pausedAtTimestampMs ?: -1L)
            editor.putLong(KEY_ACCUM_PAUSED_MS, snapshot.accumulatedPausedMs)
            editor.putLong(KEY_START_ELAPSED_MS, snapshot.startElapsedRealtimeMs)
            editor.putInt(KEY_BOOT_COUNT, snapshot.bootCountAtStart)
            editor.putLong(KEY_LAST_UPDATED_MS, snapshot.lastUpdatedWallMs)
        }
        editor.apply()
    }

    fun readSnapshot(): ActiveSessionSnapshot? {
        if (!prefs.getBoolean(KEY_HAS_SESSION, false)) return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val stateName = prefs.getString(KEY_STATE, SessionState.IDLE.name) ?: SessionState.IDLE.name
        val state = runCatching { SessionState.valueOf(stateName) }.getOrDefault(SessionState.IDLE)
        if (state == SessionState.IDLE) return null

        val typeName = prefs.getString(KEY_SESSION_TYPE, SessionType.SELF_ARREST.name)
            ?: SessionType.SELF_ARREST.name
        val sessionType = runCatching { SessionType.valueOf(typeName) }
            .getOrDefault(SessionType.SELF_ARREST)

        val categoriesCsv = prefs.getString(KEY_ALLOWED_CATEGORIES, "") ?: ""
        val categories = categoriesCsv.split(",")
            .mapNotNull { token ->
                token.trim().takeIf { it.isNotEmpty() }?.let {
                    runCatching { AllowedCategory.valueOf(it) }.getOrNull()
                }
            }
            .toSet()
            .ifEmpty { setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS) }

        val ruleModeName = prefs.getString(KEY_RULE_MODE, BlockingRuleMode.BLOCK_SELECTED.name)
            ?: BlockingRuleMode.BLOCK_SELECTED.name
        val ruleMode = runCatching { BlockingRuleMode.valueOf(ruleModeName) }
            .getOrDefault(BlockingRuleMode.BLOCK_SELECTED)

        val pausedRaw = prefs.getLong(KEY_PAUSED_AT_TS, -1L)

        return ActiveSessionSnapshot(
            sessionId = sessionId,
            sessionType = sessionType,
            state = state,
            goal = prefs.getString(KEY_GOAL, "Finish studying chapter 3") ?: "Finish studying chapter 3",
            allowedCategories = categories,
            ruleMode = ruleMode,
            configuredDurationMs = prefs.getLong(KEY_CONFIGURED_DURATION_MS, 15 * 60_000L),
            startTimestampMs = prefs.getLong(KEY_START_TS, 0L),
            endTimestampMs = prefs.getLong(KEY_END_TS, 0L),
            pausedAtTimestampMs = if (pausedRaw > 0L) pausedRaw else null,
            accumulatedPausedMs = prefs.getLong(KEY_ACCUM_PAUSED_MS, 0L),
            startElapsedRealtimeMs = prefs.getLong(KEY_START_ELAPSED_MS, 0L),
            bootCountAtStart = prefs.getInt(KEY_BOOT_COUNT, 0),
            lastUpdatedWallMs = prefs.getLong(KEY_LAST_UPDATED_MS, 0L)
        )
    }

    fun cacheBlockedAndAllowedPackages(blocked: Set<String>, allowed: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_BLOCKED_PACKAGES, blocked.toSet())
            .putStringSet(KEY_ALLOWED_PACKAGES, allowed.toSet())
            .apply()
    }

    fun getCachedBlockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun getCachedAllowedPackages(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun recordHeartbeat(nowWallMs: Long = System.currentTimeMillis(), cleanShutdown: Boolean = false) {
        prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT_MS, nowWallMs)
            .putBoolean(KEY_CLEAN_SHUTDOWN, cleanShutdown)
            .apply()
    }

    fun getLastHeartbeatMs(): Long = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)

    fun wasLastShutdownClean(): Boolean = prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true)

    fun getLastProcessedExitTimestampMs(): Long = prefs.getLong(KEY_LAST_EXIT_TS_MS, 0L)

    fun setLastProcessedExitTimestampMs(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_EXIT_TS_MS, timestampMs).apply()
    }

    companion object {
        private const val PREFS_NAME = "arrest_me_snapshot_store"
        private const val KEY_HAS_SESSION = "has_session"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_TYPE = "session_type"
        private const val KEY_STATE = "state"
        private const val KEY_GOAL = "goal"
        private const val KEY_ALLOWED_CATEGORIES = "allowed_categories"
        private const val KEY_RULE_MODE = "rule_mode"
        private const val KEY_CONFIGURED_DURATION_MS = "configured_duration_ms"
        private const val KEY_START_TS = "start_ts"
        private const val KEY_END_TS = "end_ts"
        private const val KEY_PAUSED_AT_TS = "paused_at_ts"
        private const val KEY_ACCUM_PAUSED_MS = "accum_paused_ms"
        private const val KEY_START_ELAPSED_MS = "start_elapsed_ms"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_LAST_UPDATED_MS = "last_updated_ms"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
        private const val KEY_CLEAN_SHUTDOWN = "clean_shutdown"
        private const val KEY_LAST_EXIT_TS_MS = "last_exit_ts_ms"
    }
}
