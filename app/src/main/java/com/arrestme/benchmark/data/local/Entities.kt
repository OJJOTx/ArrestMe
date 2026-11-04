package com.arrestme.benchmark.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AppRuleType {
    BLOCKED,
    ALLOWED_STUDY
}

enum class EventSeverity {
    INFO,
    WARNING,
    HIGH,
    CRITICAL
}

enum class DataConfidence {
    CONFIRMED_REALTIME_AND_USAGESTATS,
    CONFIRMED_REALTIME_ONLY,
    CONFIRMED_USAGESTATS_ONLY,
    ESTIMATED,
    UNAVAILABLE_PERMISSION_MISSING
}

@Entity(
    tableName = "sessions",
    indices = [Index(value = ["startTimestampMs"])]
)
data class SessionEntity(
    @PrimaryKey
    val sessionId: String,
    val sessionType: String,
    val state: String,
    val goal: String,
    val allowedCategoriesCsv: String,
    val ruleMode: String,
    val overlayStatusBar: Boolean = false,
    val disableEmergencyExit: Boolean = false,
    val configuredDurationMs: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val completedOrStoppedAtMs: Long? = null,
    val pausedAtTimestampMs: Long? = null,
    val accumulatedPausedMs: Long = 0L,
    val startElapsedRealtimeMs: Long = 0L,
    val bootCountAtStart: Int = 0,
    val blockedAttempts: Int = 0,
    val successfulBlocks: Int = 0,
    val failedBlocks: Int = 0,
    val overlayActivations: Int = 0,
    val totalOverlayLatencyMs: Double = 0.0,
    val minOverlayLatencyMs: Double? = null,
    val maxOverlayLatencyMs: Double? = null,
    val lastOverlayLatencyMs: Double? = null,
    val serviceInterruptions: Int = 0,
    val permissionFailures: Int = 0,
    val rapidSwitchEvents: Int = 0,
    val usageStatsConfirmedBlockedLaunches: Int? = null,
    val usageStatsTotalForegroundEvents: Int? = null,
    val usageStatsDiscrepancyCount: Int? = null,
    val usageDataConfidence: String = DataConfidence.CONFIRMED_REALTIME_ONLY.name,
    val permissionsSnapshotStart: String = "",
    val permissionsSnapshotEnd: String? = null,
    val completionReason: String? = null
) {
    val avgOverlayLatencyMs: Double?
        get() = if (overlayActivations > 0) totalOverlayLatencyMs / overlayActivations else null
}

@Entity(
    tableName = "session_transitions",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestampMs"])]
)
data class SessionTransitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val fromState: String,
    val toState: String,
    val timestampMs: Long,
    val reason: String
)

@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey
    val packageName: String,
    val appLabel: String,
    val ruleType: String,
    val updatedAtMs: Long
)

@Entity(
    tableName = "diagnostic_events",
    indices = [Index(value = ["timestampMs"]), Index(value = ["sessionId"])]
)
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long,
    val eventType: String,
    val packageName: String?,
    val sessionId: String?,
    val details: String,
    val severity: String,
    val sourceConfidence: String = DataConfidence.CONFIRMED_REALTIME_ONLY.name
)

@Entity(
    tableName = "usage_transitions",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestampMs"])]
)
data class UsageTransitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val timestampMs: Long,
    val packageName: String,
    val source: String,
    val wasBlockedRuleMatch: Boolean,
    val overlayTriggered: Boolean
)
