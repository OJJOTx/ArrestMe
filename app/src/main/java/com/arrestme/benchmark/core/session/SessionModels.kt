package com.arrestme.benchmark.core.session

enum class SessionType(val label: String) {
    SELF_ARREST("Self Arrest"),
    PARTNER_ARREST("Partner Arrest"),
    MUTUAL_ARREST("Mutual Arrest")
}

enum class SessionState {
    IDLE,
    PREPARING,
    ACTIVE,
    PAUSED,
    COMPLETED,
    EMERGENCY_STOPPED,
    INTERRUPTED;

    val isTerminal: Boolean
        get() = this == IDLE || this == COMPLETED || this == EMERGENCY_STOPPED

    val isInProgress: Boolean
        get() = this == PREPARING || this == ACTIVE || this == PAUSED || this == INTERRUPTED
}

enum class AllowedCategory(val label: String) {
    CALLS("Calls"),
    STUDY_APPS("Study Apps")
}

enum class BlockingRuleMode(val label: String, val description: String) {
    BLOCK_SELECTED(
        label = "Block Selected Apps",
        description = "Blocks apps explicitly added to your Blocked List while always permitting Calls, Allowed Apps, and system safety apps."
    ),
    STRICT_ALLOWLIST(
        label = "Strict Allowlist Mode",
        description = "Blocks all launchable apps except explicitly selected Allowed Apps, Calls/Dialer, Home Launcher, and system safety screens."
    ),
    WHITELIST_ONLY(
        label = "Whitelist Mode",
        description = "Always displays the Arrest UI — even over the Device Launcher and Arrest Me — and hides ONLY inside your selected Whitelist apps (e.g. Calculator or Phone)."
    )
}

data class SessionConfig(
    val sessionType: SessionType = SessionType.SELF_ARREST,
    val durationMinutes: Int = 15,
    val goal: String = "Finish studying chapter 3",
    val allowedCategories: Set<AllowedCategory> = setOf(
        AllowedCategory.CALLS,
        AllowedCategory.STUDY_APPS
    ),
    val ruleMode: BlockingRuleMode = BlockingRuleMode.BLOCK_SELECTED,
    val overlayStatusBar: Boolean = false,
    val disableEmergencyExit: Boolean = false,
    val cloudSessionId: String? = null,
    val authorizationId: String? = null,
    val controllingPartnerUserId: String? = null,
    val controllingPartnerUsername: String? = null
) {
    val durationMs: Long
        get() = durationMinutes.coerceAtLeast(1) * 60_000L
}

data class ActiveSessionSnapshot(
    val sessionId: String,
    val sessionType: SessionType,
    val state: SessionState,
    val goal: String,
    val allowedCategories: Set<AllowedCategory>,
    val ruleMode: BlockingRuleMode,
    val overlayStatusBar: Boolean = false,
    val disableEmergencyExit: Boolean = false,
    val configuredDurationMs: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val pausedAtTimestampMs: Long?,
    val accumulatedPausedMs: Long,
    val startElapsedRealtimeMs: Long,
    val bootCountAtStart: Int,
    val lastUpdatedWallMs: Long,
    val cloudSessionId: String? = null,
    val authorizationId: String? = null,
    val controllingPartnerUserId: String? = null,
    val controllingPartnerUsername: String? = null
) {
    fun computeRemainingMs(nowWallMs: Long = System.currentTimeMillis()): Long {
        return when (state) {
            SessionState.PAUSED -> {
                val reference = pausedAtTimestampMs ?: nowWallMs
                (endTimestampMs - reference).coerceAtLeast(0L)
            }
            SessionState.PREPARING,
            SessionState.ACTIVE,
            SessionState.INTERRUPTED -> {
                (endTimestampMs - nowWallMs).coerceAtLeast(0L)
            }
            SessionState.IDLE,
            SessionState.COMPLETED,
            SessionState.EMERGENCY_STOPPED -> 0L
        }
    }

    fun hasExpired(nowWallMs: Long = System.currentTimeMillis()): Boolean {
        if (state == SessionState.PAUSED) return false
        return state.isInProgress && nowWallMs >= endTimestampMs
    }
}
