package com.arrestme.benchmark.core.session

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.arrestme.benchmark.core.analytics.UsageMonitor
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.data.local.DataConfidence
import com.arrestme.benchmark.data.local.EventSeverity
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import com.arrestme.benchmark.data.repository.ArrestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

sealed class StartSessionResult {
    data class Started(val snapshot: ActiveSessionSnapshot) : StartSessionResult()
    data class DuplicatePrevented(val existingSessionId: String, val state: SessionState) : StartSessionResult()
    data class Error(val message: String) : StartSessionResult()
}

interface SessionLifecycleObserver {
    fun onSessionUpdated(snapshot: ActiveSessionSnapshot?)
}

class SessionController(
    context: Context,
    private val repository: ArrestRepository,
    private val permissionChecker: PermissionChecker,
    private val usageMonitor: UsageMonitor,
    private val antiCheatingMonitor: AntiCheatingMonitor,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val controllerMutex = Mutex()
    private val observers = CopyOnWriteArrayList<SessionLifecycleObserver>()

    private val _activeSession = MutableStateFlow<ActiveSessionSnapshot?>(
        repository.snapshotStore.readSnapshot()
    )
    val activeSession: StateFlow<ActiveSessionSnapshot?> = _activeSession.asStateFlow()

    private val _remainingMs = MutableStateFlow(
        _activeSession.value?.computeRemainingMs() ?: 0L
    )
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private var watchdogJob: Job? = null
    private var clockAnomalyReportedForSession: String? = null

    fun registerObserver(observer: SessionLifecycleObserver) {
        observers.addIfAbsent(observer)
    }

    /**
     * Restores and reconciles persisted session state on process startup, reboot, or UI resume.
     */
    suspend fun restoreAndReconcile(
        unexpectedExitDetected: Boolean = false,
        exitDetails: String? = null
    ) {
        controllerMutex.withLock {
            repository.syncRulesCache()
            val dbSession = repository.getActiveOrInProgressSession()
            val snapSession = repository.snapshotStore.readSnapshot()

            val baseSnapshot = when {
                dbSession != null -> dbSession.toSnapshot()
                snapSession != null && snapSession.state.isInProgress -> snapSession
                else -> {
                    publishSnapshotLocked(null)
                    return@withLock
                }
            }

            val now = System.currentTimeMillis()
            checkClockIntegrity(baseSnapshot, now)

            if (baseSnapshot.hasExpired(now)) {
                completeSessionLocked(
                    snapshot = baseSnapshot,
                    nowWallMs = now,
                    reason = if (unexpectedExitDetected) {
                        "Session expired while app process was inactive (${exitDetails ?: "recovered on startup"})"
                    } else {
                        "Automatically completed after persisted end timestamp was reached"
                    }
                )
                return@withLock
            }

            val perms = permissionChecker.inspectAll()
            if (unexpectedExitDetected) {
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.APP_RESTART_AFTER_INTERRUPTION,
                    sessionId = baseSnapshot.sessionId,
                    severity = EventSeverity.HIGH,
                    details = "Session ${baseSnapshot.sessionId} restored after process interruption: ${exitDetails ?: "process restarted"}"
                )
            }

            // If core blocking permissions are missing during an active session, mark INTERRUPTED
            if (baseSnapshot.state == SessionState.ACTIVE && !perms.isCoreBlockingReady) {
                val interrupted = baseSnapshot.copy(
                    state = SessionState.INTERRUPTED,
                    lastUpdatedWallMs = now
                )
                persistTransitionLocked(
                    sessionId = baseSnapshot.sessionId,
                    fromState = baseSnapshot.state,
                    toState = SessionState.INTERRUPTED,
                    timestampMs = now,
                    reason = "Critical blocking permissions missing on recovery (${perms.toCompactString()})"
                )
                updateSessionStateInDbLocked(interrupted)
                publishSnapshotLocked(interrupted)
                ensureWatchdogRunning()
                return@withLock
            }

            // If unexpected force-stop happened, record the interruption transition explicitly
            if (unexpectedExitDetected && baseSnapshot.state == SessionState.ACTIVE) {
                if (!perms.isCoreBlockingReady) {
                    val interrupted = baseSnapshot.copy(
                        state = SessionState.INTERRUPTED,
                        lastUpdatedWallMs = now
                    )
                    persistTransitionLocked(
                        sessionId = baseSnapshot.sessionId,
                        fromState = SessionState.ACTIVE,
                        toState = SessionState.INTERRUPTED,
                        timestampMs = now,
                        reason = exitDetails ?: "Interrupted by process stop"
                    )
                    updateSessionStateInDbLocked(interrupted)
                    publishSnapshotLocked(interrupted)
                } else {
                    // Permissions remain granted; log interruption and resume ACTIVE enforcement
                    persistTransitionLocked(
                        sessionId = baseSnapshot.sessionId,
                        fromState = SessionState.ACTIVE,
                        toState = SessionState.INTERRUPTED,
                        timestampMs = now,
                        reason = exitDetails ?: "Temporary process termination detected"
                    )
                    persistTransitionLocked(
                        sessionId = baseSnapshot.sessionId,
                        fromState = SessionState.INTERRUPTED,
                        toState = SessionState.ACTIVE,
                        timestampMs = now + 1L,
                        reason = "Automatically resumed ACTIVE state from persisted timestamps (${
                            (baseSnapshot.computeRemainingMs(now) / 1000L)
                        }s remaining)"
                    )
                    val restored = baseSnapshot.copy(lastUpdatedWallMs = now)
                    updateSessionStateInDbLocked(restored)
                    publishSnapshotLocked(restored)
                }
            } else {
                publishSnapshotLocked(baseSnapshot)
            }

            ensureWatchdogRunning()
        }
    }

    suspend fun startSession(config: SessionConfig = SessionConfig()): StartSessionResult {
        return controllerMutex.withLock {
            val existing = _activeSession.value ?: repository.snapshotStore.readSnapshot()
            if (existing != null && existing.state.isInProgress) {
                if (existing.hasExpired()) {
                    completeSessionLocked(
                        snapshot = existing,
                        nowWallMs = System.currentTimeMillis(),
                        reason = "Auto-completed expired prior session before starting new session"
                    )
                } else {
                    antiCheatingMonitor.recordEvent(
                        type = MonitorEventType.SESSION_STATE_CHANGED,
                        sessionId = existing.sessionId,
                        severity = EventSeverity.WARNING,
                        details = "Prevented duplicate session start while session ${existing.sessionId} is ${existing.state}"
                    )
                    return@withLock StartSessionResult.DuplicatePrevented(
                        existingSessionId = existing.sessionId,
                        state = existing.state
                    )
                }
            }

            repository.syncRulesCache()

            val prepTime = System.currentTimeMillis()
            val sessionId = "arrest-${UUID.randomUUID().toString().take(8)}"
            val bootCount = readBootCount()
            val permsSnapshot = permissionChecker.inspectAll()

            // Transition 1: IDLE -> PREPARING
            persistTransitionLocked(
                sessionId = sessionId,
                fromState = SessionState.IDLE,
                toState = SessionState.PREPARING,
                timestampMs = prepTime,
                reason = "Initializing ${config.sessionType.label} (${config.durationMinutes} min)"
            )
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SESSION_PREPARING,
                sessionId = sessionId,
                severity = EventSeverity.INFO,
                details = "Preparing session: goal='${config.goal}', duration=${config.durationMinutes}m, mode=${config.ruleMode.name}"
            )

            val startWall = System.currentTimeMillis()
            val endWall = startWall + config.durationMs
            val startElapsed = SystemClock.elapsedRealtime()

            val initialConfidence = if (permsSnapshot.usageAccessGranted) {
                DataConfidence.CONFIRMED_REALTIME_AND_USAGESTATS
            } else {
                DataConfidence.CONFIRMED_REALTIME_ONLY
            }

            val activeSnapshot = ActiveSessionSnapshot(
                sessionId = sessionId,
                sessionType = config.sessionType,
                state = SessionState.ACTIVE,
                goal = config.goal.ifBlank { "Finish studying chapter 3" },
                allowedCategories = config.allowedCategories,
                ruleMode = config.ruleMode,
                overlayStatusBar = config.overlayStatusBar,
                disableEmergencyExit = config.disableEmergencyExit,
                configuredDurationMs = config.durationMs,
                startTimestampMs = startWall,
                endTimestampMs = endWall,
                pausedAtTimestampMs = null,
                accumulatedPausedMs = 0L,
                startElapsedRealtimeMs = startElapsed,
                bootCountAtStart = bootCount,
                lastUpdatedWallMs = startWall,
                cloudSessionId = config.cloudSessionId,
                authorizationId = config.authorizationId,
                controllingPartnerUserId = config.controllingPartnerUserId,
                controllingPartnerUsername = config.controllingPartnerUsername
            )

            if (config.cloudSessionId != null) {
                repository.snapshotStore.saveCloudToLocalSessionMapping(
                    cloudSessionId = config.cloudSessionId,
                    localSessionId = sessionId
                )
            }

            val entity = SessionEntity(
                sessionId = sessionId,
                sessionType = config.sessionType.name,
                state = SessionState.ACTIVE.name,
                goal = activeSnapshot.goal,
                allowedCategoriesCsv = config.allowedCategories.joinToString(",") { it.name },
                ruleMode = config.ruleMode.name,
                overlayStatusBar = config.overlayStatusBar,
                disableEmergencyExit = config.disableEmergencyExit,
                configuredDurationMs = config.durationMs,
                startTimestampMs = startWall,
                endTimestampMs = endWall,
                startElapsedRealtimeMs = startElapsed,
                bootCountAtStart = bootCount,
                usageDataConfidence = initialConfidence.name,
                permissionsSnapshotStart = permsSnapshot.toCompactString()
            )
            repository.upsertSession(entity)

            // Transition 2: PREPARING -> ACTIVE
            persistTransitionLocked(
                sessionId = sessionId,
                fromState = SessionState.PREPARING,
                toState = SessionState.ACTIVE,
                timestampMs = startWall,
                reason = "Session activated; endTimestamp=$endWall, perms=${permsSnapshot.toCompactString()}"
            )

            if (!permsSnapshot.isCoreBlockingReady) {
                usageMonitor.recordPermissionFailure(
                    sessionId = sessionId,
                    reason = "Session started without full blocking permissions: ${permsSnapshot.toCompactString()}",
                    eventType = if (!permsSnapshot.overlayGranted) {
                        MonitorEventType.OVERLAY_PERMISSION_LOST
                    } else {
                        MonitorEventType.ACCESSIBILITY_PERMISSION_LOST
                    }
                )
            }

            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SESSION_STARTED,
                sessionId = sessionId,
                severity = EventSeverity.INFO,
                details = "Active Self Arrest started for ${config.durationMinutes} min. Goal: '${activeSnapshot.goal}'"
            )

            clockAnomalyReportedForSession = null
            publishSnapshotLocked(activeSnapshot)
            ensureWatchdogRunning()

            StartSessionResult.Started(activeSnapshot)
        }
    }

    suspend fun pauseSession(reason: String = "User paused Self Arrest session") {
        controllerMutex.withLock {
            val current = _activeSession.value ?: return@withLock
            if (current.state != SessionState.ACTIVE) return@withLock
            if (current.disableEmergencyExit) {
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.SESSION_STATE_CHANGED,
                    sessionId = current.sessionId,
                    severity = EventSeverity.WARNING,
                    details = "Pause blocked because Emergency Exit is disabled for this session"
                )
                return@withLock
            }
            val now = System.currentTimeMillis()
            if (current.hasExpired(now)) {
                completeSessionLocked(current, now, "Expired right before pause request")
                return@withLock
            }

            val paused = current.copy(
                state = SessionState.PAUSED,
                pausedAtTimestampMs = now,
                lastUpdatedWallMs = now
            )
            persistTransitionLocked(
                sessionId = current.sessionId,
                fromState = SessionState.ACTIVE,
                toState = SessionState.PAUSED,
                timestampMs = now,
                reason = reason
            )
            updateSessionStateInDbLocked(paused)
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SESSION_STATE_CHANGED,
                sessionId = current.sessionId,
                severity = EventSeverity.WARNING,
                details = "Session paused: $reason"
            )
            publishSnapshotLocked(paused)
        }
    }

    suspend fun resumeSession(reason: String = "User resumed session") {
        controllerMutex.withLock {
            val current = _activeSession.value ?: return@withLock
            if (current.state != SessionState.PAUSED && current.state != SessionState.INTERRUPTED) {
                return@withLock
            }
            val now = System.currentTimeMillis()

            val updatedEndTs = if (current.state == SessionState.PAUSED && current.pausedAtTimestampMs != null) {
                val pauseDelta = (now - current.pausedAtTimestampMs).coerceAtLeast(0L)
                current.endTimestampMs + pauseDelta
            } else {
                current.endTimestampMs
            }

            val updatedAccumPaused = if (current.state == SessionState.PAUSED && current.pausedAtTimestampMs != null) {
                current.accumulatedPausedMs + (now - current.pausedAtTimestampMs).coerceAtLeast(0L)
            } else {
                current.accumulatedPausedMs
            }

            if (now >= updatedEndTs) {
                completeSessionLocked(current, now, "Session window already elapsed upon resume")
                return@withLock
            }

            val resumed = current.copy(
                state = SessionState.ACTIVE,
                endTimestampMs = updatedEndTs,
                pausedAtTimestampMs = null,
                accumulatedPausedMs = updatedAccumPaused,
                lastUpdatedWallMs = now
            )

            persistTransitionLocked(
                sessionId = current.sessionId,
                fromState = current.state,
                toState = SessionState.ACTIVE,
                timestampMs = now,
                reason = reason
            )
            updateSessionStateInDbLocked(resumed)
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SESSION_STATE_CHANGED,
                sessionId = current.sessionId,
                severity = EventSeverity.INFO,
                details = "Session resumed to ACTIVE: $reason"
            )
            publishSnapshotLocked(resumed)
            ensureWatchdogRunning()
        }
    }

    suspend fun markInterrupted(reason: String) {
        controllerMutex.withLock {
            val current = _activeSession.value ?: return@withLock
            if (current.state != SessionState.ACTIVE) return@withLock
            val now = System.currentTimeMillis()
            if (current.hasExpired(now)) {
                completeSessionLocked(current, now, "Session expired at interruption check")
                return@withLock
            }

            val interrupted = current.copy(
                state = SessionState.INTERRUPTED,
                lastUpdatedWallMs = now
            )
            persistTransitionLocked(
                sessionId = current.sessionId,
                fromState = SessionState.ACTIVE,
                toState = SessionState.INTERRUPTED,
                timestampMs = now,
                reason = reason
            )
            updateSessionStateInDbLocked(interrupted)
            usageMonitor.recordServiceInterruption(current.sessionId, reason)
            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.SESSION_INTERRUPTION,
                sessionId = current.sessionId,
                severity = EventSeverity.CRITICAL,
                details = reason
            )
            publishSnapshotLocked(interrupted)
        }
    }

    suspend fun emergencyStop(reason: String = "Explicit user emergency exit") {
        controllerMutex.withLock {
            val current = _activeSession.value ?: repository.snapshotStore.readSnapshot() ?: return@withLock
            if (!current.state.isInProgress) return@withLock
            if (current.disableEmergencyExit) {
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.EMERGENCY_EXIT,
                    sessionId = current.sessionId,
                    severity = EventSeverity.WARNING,
                    details = "Emergency stop refused because Emergency Exit is disabled for session ${current.sessionId}"
                )
                return@withLock
            }
            val now = System.currentTimeMillis()
            val endPerms = permissionChecker.inspectAll().toCompactString()

            val stopped = current.copy(
                state = SessionState.EMERGENCY_STOPPED,
                pausedAtTimestampMs = null,
                lastUpdatedWallMs = now
            )

            persistTransitionLocked(
                sessionId = current.sessionId,
                fromState = current.state,
                toState = SessionState.EMERGENCY_STOPPED,
                timestampMs = now,
                reason = reason
            )

            val dbSession = repository.getSessionById(current.sessionId)
            if (dbSession != null) {
                repository.upsertSession(
                    dbSession.copy(
                        state = SessionState.EMERGENCY_STOPPED.name,
                        completedOrStoppedAtMs = now,
                        pausedAtTimestampMs = null,
                        permissionsSnapshotEnd = endPerms,
                        completionReason = reason
                    )
                )
            }

            antiCheatingMonitor.recordEvent(
                type = MonitorEventType.EMERGENCY_EXIT,
                sessionId = current.sessionId,
                severity = EventSeverity.HIGH,
                details = "Emergency stop executed: $reason"
            )

            publishSnapshotLocked(stopped)
            repository.restoreBackedUpLocalRulesIfPresent()
            watchdogJob?.cancel()
            watchdogJob = null

            val finishedId = current.sessionId
            scope.launch(Dispatchers.IO) {
                usageMonitor.reconcileSessionWithUsageStats(finishedId)
            }
        }
    }

    /**
     * Completes an active session in response to an authenticated remote partner command
     * (`APPROVE_RELEASE` or `END_SESSION`), even when local emergency exit is disabled.
     */
    suspend fun forceCompleteRemoteSession(
        reason: String = "Released remotely by controlling partner"
    ): Boolean {
        return controllerMutex.withLock {
            val current = _activeSession.value ?: repository.snapshotStore.readSnapshot() ?: return@withLock false
            if (!current.state.isInProgress) return@withLock false
            val now = System.currentTimeMillis()
            completeSessionLocked(
                snapshot = current,
                nowWallMs = now,
                reason = reason
            )
            true
        }
    }

    suspend fun resetToIdle() {
        controllerMutex.withLock {
            val current = _activeSession.value
            if (current != null && current.state.isInProgress) return@withLock
            repository.restoreBackedUpLocalRulesIfPresent()
            publishSnapshotLocked(null)
        }
    }

    suspend fun checkAndCompleteIfExpired(): Boolean {
        return controllerMutex.withLock {
            val current = _activeSession.value ?: repository.snapshotStore.readSnapshot() ?: return@withLock false
            val now = System.currentTimeMillis()
            checkClockIntegrity(current, now)
            if (current.hasExpired(now)) {
                completeSessionLocked(
                    snapshot = current,
                    nowWallMs = now,
                    reason = "Session duration reached persisted endTimestampMs"
                )
                true
            } else {
                _remainingMs.value = current.computeRemainingMs(now)
                false
            }
        }
    }

    suspend fun applyTemporaryAppAccessOverride(grantId: String, packageName: String, expiresAtWallMs: Long) {
        controllerMutex.withLock {
            val current = _activeSession.value ?: return@withLock
            val existing = current.temporaryAccessOverrides.filterNot { it.grantId == grantId || it.packageName == packageName }
            val updatedOverrides = existing + TemporaryAppAccessOverride(grantId, packageName, expiresAtWallMs)
            val updatedSnapshot = current.copy(temporaryAccessOverrides = updatedOverrides)
            publishSnapshotLocked(updatedSnapshot)
        }
    }

    suspend fun revokeTemporaryAppAccessOverride(grantId: String) {
        controllerMutex.withLock {
            val current = _activeSession.value ?: return@withLock
            val updatedOverrides = current.temporaryAccessOverrides.filterNot { it.grantId == grantId }
            val updatedSnapshot = current.copy(temporaryAccessOverrides = updatedOverrides)
            publishSnapshotLocked(updatedSnapshot)
        }
    }

    suspend fun extendActiveSession(extensionMs: Long) {
        controllerMutex.withLock {
            val current = _activeSession.value ?: return@withLock
            if (!current.state.isInProgress) return@withLock
            val newEnd = current.endTimestampMs + extensionMs
            val updatedSnapshot = current.copy(
                endTimestampMs = newEnd,
                lastUpdatedWallMs = System.currentTimeMillis()
            )
            updateSessionStateInDbLocked(updatedSnapshot)
            publishSnapshotLocked(updatedSnapshot)
        }
    }

    private suspend fun completeSessionLocked(
        snapshot: ActiveSessionSnapshot,
        nowWallMs: Long,
        reason: String
    ) {
        val endPerms = permissionChecker.inspectAll().toCompactString()
        val completed = snapshot.copy(
            state = SessionState.COMPLETED,
            pausedAtTimestampMs = null,
            lastUpdatedWallMs = nowWallMs
        )

        persistTransitionLocked(
            sessionId = snapshot.sessionId,
            fromState = snapshot.state,
            toState = SessionState.COMPLETED,
            timestampMs = nowWallMs,
            reason = reason
        )

        val dbSession = repository.getSessionById(snapshot.sessionId)
        if (dbSession != null) {
            repository.upsertSession(
                dbSession.copy(
                    state = SessionState.COMPLETED.name,
                    completedOrStoppedAtMs = nowWallMs,
                    pausedAtTimestampMs = null,
                    permissionsSnapshotEnd = endPerms,
                    completionReason = reason
                )
            )
        }

        antiCheatingMonitor.recordEvent(
            type = MonitorEventType.SESSION_COMPLETED,
            sessionId = snapshot.sessionId,
            severity = EventSeverity.INFO,
            details = reason
        )

        publishSnapshotLocked(completed)
        repository.restoreBackedUpLocalRulesIfPresent()
        watchdogJob?.cancel()
        watchdogJob = null

        val completedId = snapshot.sessionId
        scope.launch(Dispatchers.IO) {
            usageMonitor.reconcileSessionWithUsageStats(completedId)
        }
    }

    private suspend fun updateSessionStateInDbLocked(snapshot: ActiveSessionSnapshot) {
        val dbSession = repository.getSessionById(snapshot.sessionId) ?: return
        repository.upsertSession(
            dbSession.copy(
                state = snapshot.state.name,
                endTimestampMs = snapshot.endTimestampMs,
                pausedAtTimestampMs = snapshot.pausedAtTimestampMs,
                accumulatedPausedMs = snapshot.accumulatedPausedMs
            )
        )
    }

    private suspend fun persistTransitionLocked(
        sessionId: String,
        fromState: SessionState,
        toState: SessionState,
        timestampMs: Long,
        reason: String
    ) {
        repository.insertTransition(
            SessionTransitionEntity(
                sessionId = sessionId,
                fromState = fromState.name,
                toState = toState.name,
                timestampMs = timestampMs,
                reason = reason
            )
        )
    }

    private fun publishSnapshotLocked(snapshot: ActiveSessionSnapshot?) {
        if (snapshot == null) {
            repository.snapshotStore.saveSnapshot(null)
            repository.snapshotStore.recordHeartbeat(cleanShutdown = true)
            _activeSession.value = null
            _remainingMs.value = 0L
        } else {
            repository.snapshotStore.saveSnapshot(snapshot)
            repository.snapshotStore.recordHeartbeat(cleanShutdown = !snapshot.state.isInProgress)
            _activeSession.value = snapshot
            _remainingMs.value = snapshot.computeRemainingMs()
        }

        observers.forEach { observer ->
            runCatching { observer.onSessionUpdated(snapshot) }
        }
    }

    private fun ensureWatchdogRunning() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch(Dispatchers.Default) {
            var tickCounter = 0
            while (isActive) {
                val snap = _activeSession.value
                if (snap == null || !snap.state.isInProgress) {
                    break
                }
                val expired = checkAndCompleteIfExpired()
                if (expired) break

                tickCounter++
                if (tickCounter % 10 == 0) {
                    repository.snapshotStore.recordHeartbeat(cleanShutdown = false)
                }
                // Check critical permissions while session is ACTIVE
                if (tickCounter % 5 == 0 && snap.state == SessionState.ACTIVE) {
                    val perms = permissionChecker.inspectAll()
                    if (!perms.overlayGranted) {
                        usageMonitor.recordPermissionFailure(
                            sessionId = snap.sessionId,
                            reason = "SYSTEM_ALERT_WINDOW (Overlay) permission revoked during ACTIVE session",
                            eventType = MonitorEventType.OVERLAY_PERMISSION_LOST
                        )
                        markInterrupted("Overlay permission revoked while session was ACTIVE")
                    } else if (!perms.accessibilitySettingEnabled) {
                        usageMonitor.recordPermissionFailure(
                            sessionId = snap.sessionId,
                            reason = "Accessibility Service disabled in Android Settings during ACTIVE session",
                            eventType = MonitorEventType.ACCESSIBILITY_PERMISSION_LOST
                        )
                        markInterrupted("Accessibility permission disabled while session was ACTIVE")
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun checkClockIntegrity(snapshot: ActiveSessionSnapshot, nowWallMs: Long) {
        if (clockAnomalyReportedForSession == snapshot.sessionId) return
        val currentBoot = readBootCount()
        if (currentBoot > 0 && currentBoot == snapshot.bootCountAtStart && snapshot.startElapsedRealtimeMs > 0L) {
            val wallElapsed = nowWallMs - snapshot.startTimestampMs
            val monoElapsed = SystemClock.elapsedRealtime() - snapshot.startElapsedRealtimeMs
            if (abs(wallElapsed - monoElapsed) > 60_000L) {
                clockAnomalyReportedForSession = snapshot.sessionId
                antiCheatingMonitor.recordEvent(
                    type = MonitorEventType.CLOCK_ANOMALY_DETECTED,
                    sessionId = snapshot.sessionId,
                    severity = EventSeverity.HIGH,
                    details = "System wall-clock shifted relative to monotonic clock (wallDelta=${wallElapsed}ms vs monoDelta=${monoElapsed}ms)"
                )
            }
        }
    }

    private fun readBootCount(): Int {
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)
    }

    private fun SessionEntity.toSnapshot(): ActiveSessionSnapshot {
        val categories = allowedCategoriesCsv.split(",")
            .mapNotNull { token ->
                token.trim().takeIf { it.isNotEmpty() }?.let {
                    runCatching { AllowedCategory.valueOf(it) }.getOrNull()
                }
            }
            .toSet()
            .ifEmpty { setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS) }

        val priorSnap = repository.snapshotStore.readSnapshot()
        val mappedCloudId = repository.snapshotStore.getCloudSessionIdForLocalSession(sessionId)

        return ActiveSessionSnapshot(
            sessionId = sessionId,
            sessionType = runCatching { SessionType.valueOf(sessionType) }
                .getOrDefault(SessionType.SELF_ARREST),
            state = runCatching { SessionState.valueOf(state) }
                .getOrDefault(SessionState.IDLE),
            goal = goal,
            allowedCategories = categories,
            ruleMode = runCatching { BlockingRuleMode.valueOf(ruleMode) }
                .getOrDefault(BlockingRuleMode.BLOCK_SELECTED),
            overlayStatusBar = overlayStatusBar,
            disableEmergencyExit = disableEmergencyExit,
            configuredDurationMs = configuredDurationMs,
            startTimestampMs = startTimestampMs,
            endTimestampMs = endTimestampMs,
            pausedAtTimestampMs = pausedAtTimestampMs,
            accumulatedPausedMs = accumulatedPausedMs,
            startElapsedRealtimeMs = startElapsedRealtimeMs,
            bootCountAtStart = bootCountAtStart,
            lastUpdatedWallMs = System.currentTimeMillis(),
            cloudSessionId = priorSnap?.takeIf { it.sessionId == sessionId }?.cloudSessionId ?: mappedCloudId,
            authorizationId = priorSnap?.takeIf { it.sessionId == sessionId }?.authorizationId,
            controllingPartnerUserId = priorSnap?.takeIf { it.sessionId == sessionId }?.controllingPartnerUserId,
            controllingPartnerUsername = priorSnap?.takeIf { it.sessionId == sessionId }?.controllingPartnerUsername
        )
    }
}
