package com.arrestme.benchmark.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE state IN ('PREPARING', 'ACTIVE', 'PAUSED', 'INTERRUPTED') ORDER BY startTimestampMs DESC LIMIT 1")
    suspend fun getActiveOrInProgressSession(): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startTimestampMs DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startTimestampMs DESC")
    suspend fun getAllSessionsSnapshot(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransition(transition: SessionTransitionEntity)

    @Query("SELECT * FROM session_transitions WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getTransitionsForSession(sessionId: String): List<SessionTransitionEntity>

    @Query("SELECT * FROM session_transitions ORDER BY timestampMs DESC LIMIT 200")
    fun observeRecentTransitions(): Flow<List<SessionTransitionEntity>>

    @Query("SELECT * FROM session_transitions ORDER BY timestampMs DESC")
    suspend fun getAllTransitionsSnapshot(): List<SessionTransitionEntity>
}

@Dao
interface AppRuleDao {
    @Upsert
    suspend fun upsertRule(rule: AppRuleEntity)

    @Query("DELETE FROM app_rules WHERE packageName = :packageName")
    suspend fun deleteRule(packageName: String)

    @Query("SELECT * FROM app_rules ORDER BY appLabel ASC")
    fun observeAllRules(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAllRulesSnapshot(): List<AppRuleEntity>

    @Query("DELETE FROM app_rules")
    suspend fun clearAllRules()
}

@Dao
interface DiagnosticEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events ORDER BY timestampMs DESC LIMIT 500")
    fun observeRecentEvents(): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT * FROM diagnostic_events WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getEventsForSession(sessionId: String): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events ORDER BY timestampMs DESC LIMIT 1000")
    suspend fun getRecentEventsSnapshot(): List<DiagnosticEventEntity>

    @Query("DELETE FROM diagnostic_events")
    suspend fun clearAllEvents()
}

@Dao
interface UsageTransitionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageTransition(transition: UsageTransitionEntity)

    @Query("SELECT * FROM usage_transitions WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getTransitionsForSession(sessionId: String): List<UsageTransitionEntity>
}
