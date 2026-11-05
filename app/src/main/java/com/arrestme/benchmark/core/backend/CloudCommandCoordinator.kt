package com.arrestme.benchmark.core.backend

import com.arrestme.benchmark.core.blocking.InstalledAppInfo
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionConfig
import com.arrestme.benchmark.core.session.SessionController
import com.arrestme.benchmark.core.session.SessionLifecycleObserver
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType
import com.arrestme.benchmark.core.session.StartSessionResult
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.ArrestDatabase
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import com.arrestme.benchmark.data.remote.ApiErrorMapper
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.ArrestCloudApi
import com.arrestme.benchmark.data.remote.NetworkConnectivityChecker
import com.arrestme.benchmark.data.remote.dto.AcknowledgeCommandRequestDto
import com.arrestme.benchmark.data.remote.dto.AcknowledgeSessionRequestDto
import com.arrestme.benchmark.data.remote.dto.ArrestSessionDto
import com.arrestme.benchmark.data.remote.dto.BlockingConfigurationDto
import com.arrestme.benchmark.data.remote.dto.CreateRemoteSessionRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateRemoteSessionResponseDto
import com.arrestme.benchmark.data.remote.dto.DeviceAppCatalogDto
import com.arrestme.benchmark.data.remote.dto.LiveSessionDashboardItemDto
import com.arrestme.benchmark.data.remote.dto.PartnerPermissionDto
import com.arrestme.benchmark.data.remote.dto.PostSessionEventsRequestDto
import com.arrestme.benchmark.data.remote.dto.RemoteSessionCommandRequestDto
import com.arrestme.benchmark.data.remote.dto.ScheduledArrestDto
import com.arrestme.benchmark.data.remote.dto.SelectableAppCatalogItemDto
import com.arrestme.benchmark.data.remote.dto.SessionCommandDto
import com.arrestme.benchmark.data.remote.dto.SyncEventItemDto
import com.arrestme.benchmark.data.remote.dto.UpsertDeviceAppCatalogRequestDto
import com.arrestme.benchmark.data.repository.ArrestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge interface decoupling CloudCommandCoordinator from direct Android database/context dependencies.
 */
interface LocalSessionEngineBridge {
    fun registerObserver(observer: SessionLifecycleObserver)
    val activeSession: StateFlow<ActiveSessionSnapshot?>
    suspend fun startSession(config: SessionConfig): StartSessionResult
    suspend fun forceCompleteRemoteSession(reason: String): Boolean
    suspend fun applyTemporaryPartnerRulesWithBackup(blockedPackages: List<String>?, allowedStudyPackages: List<String>?)
    suspend fun restoreBackedUpLocalRulesIfPresent()
    suspend fun getAllRulesSnapshot(): List<AppRuleEntity>
    suspend fun getTransitionsForSession(localSessionId: String): List<SessionTransitionEntity>
    fun saveCloudToLocalSessionMapping(cloudSessionId: String, localSessionId: String)
    fun getLocalSessionIdForCloudSession(cloudSessionId: String): String?
    fun getCloudSessionIdForLocalSession(localSessionId: String): String?
    fun getLastCommandSequenceNo(deviceId: String): Long
    fun setLastCommandSequenceNo(deviceId: String, sequenceNo: Long)
    fun saveCachedSchedulesJson(schedulesJson: String)
    fun readCachedSchedulesJson(): String?
    suspend fun applyTemporaryAppAccessOverride(grantId: String, packageName: String, expiresAtWallMs: Long)
    suspend fun revokeTemporaryAppAccessOverride(grantId: String)
    suspend fun extendActiveSession(extensionMs: Long)
    suspend fun recordDiagnosticEvent(
        eventType: String,
        details: String,
        severity: String = "INFO",
        sessionId: String? = null
    )
}

class DefaultLocalSessionEngineBridge(
    private val arrestRepository: ArrestRepository,
    private val database: ArrestDatabase,
    private val sessionController: SessionController
) : LocalSessionEngineBridge {
    override fun registerObserver(observer: SessionLifecycleObserver) = sessionController.registerObserver(observer)
    override val activeSession: StateFlow<ActiveSessionSnapshot?> = sessionController.activeSession
    override suspend fun startSession(config: SessionConfig): StartSessionResult = sessionController.startSession(config)
    override suspend fun forceCompleteRemoteSession(reason: String): Boolean = sessionController.forceCompleteRemoteSession(reason)
    override suspend fun applyTemporaryPartnerRulesWithBackup(blockedPackages: List<String>?, allowedStudyPackages: List<String>?) =
        arrestRepository.applyTemporaryPartnerRulesWithBackup(blockedPackages, allowedStudyPackages)
    override suspend fun restoreBackedUpLocalRulesIfPresent() = arrestRepository.restoreBackedUpLocalRulesIfPresent()
    override suspend fun getAllRulesSnapshot(): List<AppRuleEntity> = arrestRepository.getAllRulesSnapshot()
    override suspend fun getTransitionsForSession(localSessionId: String): List<SessionTransitionEntity> =
        database.sessionDao().getTransitionsForSession(localSessionId)
    override fun saveCloudToLocalSessionMapping(cloudSessionId: String, localSessionId: String) =
        arrestRepository.snapshotStore.saveCloudToLocalSessionMapping(cloudSessionId, localSessionId)
    override fun getLocalSessionIdForCloudSession(cloudSessionId: String): String? =
        arrestRepository.snapshotStore.getLocalSessionIdForCloudSession(cloudSessionId)
    override fun getCloudSessionIdForLocalSession(localSessionId: String): String? =
        arrestRepository.snapshotStore.getCloudSessionIdForLocalSession(localSessionId)
    override fun getLastCommandSequenceNo(deviceId: String): Long =
        arrestRepository.snapshotStore.getLastCommandSequenceNo(deviceId)
    override fun setLastCommandSequenceNo(deviceId: String, sequenceNo: Long) =
        arrestRepository.snapshotStore.setLastCommandSequenceNo(deviceId, sequenceNo)
    override fun saveCachedSchedulesJson(schedulesJson: String) =
        arrestRepository.snapshotStore.saveCachedSchedulesJson(schedulesJson)
    override fun readCachedSchedulesJson(): String? =
        arrestRepository.snapshotStore.readCachedSchedulesJson()
    override suspend fun applyTemporaryAppAccessOverride(grantId: String, packageName: String, expiresAtWallMs: Long) =
        sessionController.applyTemporaryAppAccessOverride(grantId, packageName, expiresAtWallMs)
    override suspend fun revokeTemporaryAppAccessOverride(grantId: String) =
        sessionController.revokeTemporaryAppAccessOverride(grantId)
    override suspend fun extendActiveSession(extensionMs: Long) =
        sessionController.extendActiveSession(extensionMs)
    override suspend fun recordDiagnosticEvent(
        eventType: String,
        details: String,
        severity: String,
        sessionId: String?
    ) {
        runCatching {
            arrestRepository.insertDiagnosticEvent(
                com.arrestme.benchmark.data.local.DiagnosticEventEntity(
                    timestampMs = System.currentTimeMillis(),
                    eventType = eventType,
                    packageName = "com.arrestme.benchmark",
                    sessionId = sessionId,
                    details = details,
                    severity = severity
                )
            )
        }
    }
}

/**
 * V0.5 Remote Arrest Coordinator, Scheduling Engine & Live Session Synchronizer.
 *
 * Strictly isolated from `BlockingEngine`, `BlockingRulesEvaluator`, `OverlayManager`,
 * and `ArrestAccessibilityService`. Communicates with the local Arrest Engine exclusively
 * via `SessionController` and `ArrestRepository`.
 */
class CloudCommandCoordinator(
    private val preferencesDataSource: BackendPreferencesDataSource,
    private val configRepository: BackendConfigRepository,
    private val authRepository: BackendAuthRepository,
    private val apiClientFactory: ArrestApiClientFactory,
    private val connectivityChecker: NetworkConnectivityChecker,
    private val engineBridge: LocalSessionEngineBridge,
    private val scope: CoroutineScope,
    private val enableBackgroundPolling: Boolean = true,
    private val clockNowMs: () -> Long = { System.currentTimeMillis() }
) : SessionLifecycleObserver {

    constructor(
        preferencesDataSource: BackendPreferencesDataSource,
        configRepository: BackendConfigRepository,
        authRepository: BackendAuthRepository,
        apiClientFactory: ArrestApiClientFactory,
        connectivityChecker: NetworkConnectivityChecker,
        arrestRepository: ArrestRepository,
        database: ArrestDatabase,
        sessionController: SessionController,
        scope: CoroutineScope,
        enableBackgroundPolling: Boolean = true,
        clockNowMs: () -> Long = { System.currentTimeMillis() }
    ) : this(
        preferencesDataSource = preferencesDataSource,
        configRepository = configRepository,
        authRepository = authRepository,
        apiClientFactory = apiClientFactory,
        connectivityChecker = connectivityChecker,
        engineBridge = DefaultLocalSessionEngineBridge(arrestRepository, database, sessionController),
        scope = scope,
        enableBackgroundPolling = enableBackgroundPolling,
        clockNowMs = clockNowMs
    )

    private val coordinatorMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _liveDashboardItems = MutableStateFlow<List<LiveSessionDashboardItemDto>>(emptyList())
    val liveDashboardItems: StateFlow<List<LiveSessionDashboardItemDto>> = _liveDashboardItems.asStateFlow()

    private val _partnerDeviceAppCatalog = MutableStateFlow<DeviceAppCatalogDto?>(null)
    val partnerDeviceAppCatalog: StateFlow<DeviceAppCatalogDto?> = _partnerDeviceAppCatalog.asStateFlow()

    private val _isSyncingCommands = MutableStateFlow(false)
    val isSyncingCommands: StateFlow<Boolean> = _isSyncingCommands.asStateFlow()

    private val _coordinatorBanner = MutableStateFlow<String?>(null)
    val coordinatorBanner: StateFlow<String?> = _coordinatorBanner.asStateFlow()

    private val pendingCloudSessionSyncIds = ConcurrentHashMap.newKeySet<String>()
    private val cachedGrantedPermissions = ConcurrentHashMap<String, PartnerPermissionDto>()
    private var pollingJob: Job? = null

    init {
        engineBridge.registerObserver(this)

        if (enableBackgroundPolling) {
            scope.launch(Dispatchers.IO) {
                authRepository.authState.collect { state ->
                    if (state is BackendAuthState.Authenticated) {
                        startPeriodicCommandAndScheduleLoop()
                    } else {
                        pollingJob?.cancel()
                        pollingJob = null
                        _liveDashboardItems.value = emptyList()
                    }
                }
            }
        }
    }

    private fun startPeriodicCommandAndScheduleLoop() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    syncCommandsSchedulesAndDashboard()
                }
                delay(15_000L)
            }
        }
    }

    private suspend fun resolveAuthenticatedApi(): CloudResult<Triple<String, AuthCredentials, ArrestCloudApi>> {
        val config = configRepository.activeConfig.first()
        val baseUrl = config.effectiveBaseUrl
        if (config.isLocalOnly || baseUrl.isNullOrBlank()) {
            return CloudResult.Failure(
                CloudError.Configuration("Command coordinator requires a connected cloud server.")
            )
        }
        val identity = config.backendIdentity
        val creds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        if (!creds.isValid) {
            return CloudResult.Failure(
                CloudError.Unauthorized(message = "Sign in to synchronize commands.")
            )
        }
        return apiClientFactory.getOrCreateApi(baseUrl).fold(
            onSuccess = { (resolvedId, api) -> CloudResult.Success(Triple(resolvedId, creds, api)) },
            onFailure = { err -> CloudResult.Failure(CloudError.Configuration(err.message ?: "Invalid server URL.")) }
        )
    }

    private suspend fun <T, R> executeApiCall(
        call: suspend () -> retrofit2.Response<T>,
        transform: (T) -> R
    ): CloudResult<R> {
        return try {
            val resp = call()
            ApiErrorMapper.mapResponse(resp, transform = transform)
        } catch (e: Throwable) {
            CloudResult.Failure(ApiErrorMapper.mapThrowable(e))
        }
    }

    private suspend fun <T> executeApiCall(
        call: suspend () -> retrofit2.Response<T>
    ): CloudResult<T> = executeApiCall(call) { it }

    override fun onSessionUpdated(snapshot: ActiveSessionSnapshot?) {
        val localSessionId = snapshot?.sessionId ?: return
        val cloudSessionId = snapshot.cloudSessionId
            ?: engineBridge.getCloudSessionIdForLocalSession(localSessionId)
            ?: return

        pendingCloudSessionSyncIds.add(cloudSessionId)
        scope.launch(Dispatchers.IO) {
            reconcileSessionEventsForCloudSession(cloudSessionId, localSessionId)
            refreshLiveDashboard()
        }
    }

    /**
     * Full V0.5 synchronization pass:
     * 1. Evaluates due scheduled arrests (online trigger or offline cached evaluation).
     * 2. Polls & executes pending remote commands for this device.
     * 3. Reconciles any local session lifecycle events queued while offline.
     * 4. Refreshes the live session dashboard for both owner & controlling partner views.
     */
    suspend fun syncCommandsSchedulesAndDashboard(): CloudResult<List<SessionCommandDto>> =
        withContext(Dispatchers.IO) {
            _isSyncingCommands.value = true
            try {
                evaluateDueSchedules()
                val cmdResult = pollAndExecutePendingCommands()
                reconcileAllPendingSessionEvents()
                refreshLiveDashboard()
                cmdResult
            } finally {
                _isSyncingCommands.value = false
            }
        }

    /**
     * Retrieves pending remote commands for this device, validates target device, status,
     * expiration, and authorization, maps remote configuration into `SessionConfig`,
     * backs up local Self-Arrest rules, starts `SessionController`, persists cloud-to-local
     * session mapping, and acknowledges the actual result.
     */
    suspend fun pollAndExecutePendingCommands(): CloudResult<List<SessionCommandDto>> =
        withContext(Dispatchers.IO) {
            coordinatorMutex.withLock {
                val resolved = resolveAuthenticatedApi()
                if (resolved is CloudResult.Failure) return@withLock resolved
                val (_, creds, api) = (resolved as CloudResult.Success).data
                val myDeviceId = creds.registeredDevice?.id
                val myUserId = creds.userId
                if (myDeviceId.isNullOrBlank() || myUserId.isNullOrBlank()) {
                    return@withLock CloudResult.Failure(
                        CloudError.Unauthorized(message = "Device must be authenticated and registered.")
                    )
                }

                // Refresh active granted permissions cache so step 5 (authorization matching) is up to date.
                // Do not clear the cache unconditionally so active/pending session authorizations are preserved.
                runCatching {
                    val permsRes = api.listPartnerPermissions(
                        role = "granted",
                        activeOnly = true,
                        includeConsumed = true,
                        limit = 100
                    )
                    if (permsRes.isSuccessful) {
                        val items = permsRes.body()?.items ?: emptyList()
                        items.forEach { cachedGrantedPermissions[it.permissionId] = it }
                    }
                }

                val pollRes = executeApiCall {
                    api.pollDeviceCommands(
                        deviceId = myDeviceId,
                        afterSequence = 0L,
                        includeDelivered = true,
                        limit = 25
                    )
                }

                when (pollRes) {
                    is CloudResult.Failure -> pollRes
                    is CloudResult.Success -> {
                        val commands = pollRes.data.items
                        engineBridge.recordDiagnosticEvent(
                            eventType = "COMMAND_POLLING",
                            details = "Polled ${commands.size} commands from cloud",
                            severity = "INFO"
                        )
                        for (cmd in commands) {
                            processSingleCommandLocked(api, myUserId, myDeviceId, cmd)
                            if (cmd.sequenceNo > 0L) {
                                engineBridge.setLastCommandSequenceNo(
                                    myDeviceId,
                                    cmd.sequenceNo
                                )
                            }
                        }
                        CloudResult.Success(commands)
                    }
                }
            }
        }

    private suspend fun processSingleCommandLocked(
        api: ArrestCloudApi,
        myUserId: String,
        myDeviceId: String,
        command: SessionCommandDto
    ) {
        val nowMs = clockNowMs()

        // Step 3: Validate target device and command status
        if (command.targetDeviceId != myDeviceId) {
            return
        }
        if (command.status != "PENDING" && command.status != "DELIVERED" && command.status != "ACKNOWLEDGED") {
            return
        }

        // Step 4: Validate command expiration (and scheduled window end time if present)
        val expiresAtMs = parseIsoUtcMs(command.expiresAt)
        val scheduledEndAtUtcStr = command.payload["scheduledEndAtUtc"]?.jsonPrimitive?.contentOrNull()
        val scheduledEndMs = scheduledEndAtUtcStr?.let { parseIsoUtcMs(it) }

        if ((expiresAtMs != null && nowMs >= expiresAtMs) ||
            (scheduledEndMs != null && nowMs >= scheduledEndMs)
        ) {
            val expiredReason = if (scheduledEndMs != null && nowMs >= scheduledEndMs) {
                "Scheduled session window has already passed and cannot start late"
            } else {
                "Remote command expired before local execution"
            }
            command.sessionId?.let { sId ->
                runCatching {
                    api.acknowledgeSession(
                        sessionId = sId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "REJECTED",
                            localEngineState = engineBridge.activeSession.value?.state?.name ?: "IDLE",
                            reason = expiredReason
                        )
                    )
                }
            }
            runCatching {
                api.acknowledgeCommand(
                    commandId = command.commandId,
                    body = AcknowledgeCommandRequestDto(
                        deviceId = myDeviceId,
                        status = "REJECTED",
                        failureReason = expiredReason
                    )
                )
            }
            return
        }

        when (command.commandType) {
            "START_ARREST" -> executeStartArrestCommandLocked(api, myUserId, myDeviceId, command, nowMs)
            "END_SESSION", "APPROVE_RELEASE" -> executeRemoteEndOrReleaseCommandLocked(api, myDeviceId, command)
            "SYNC_SESSION" -> executeSyncSessionCommandLocked(api, myDeviceId, command)
            "APPLY_TEMPORARY_APP_ACCESS" -> executeApplyTemporaryAppAccessLocked(api, myDeviceId, command, nowMs)
            "REVOKE_TEMPORARY_APP_ACCESS" -> executeRevokeTemporaryAppAccessLocked(api, myDeviceId, command)
            "UPDATE_SESSION_RULES" -> executeUpdateSessionRulesLocked(api, myDeviceId, command)
            else -> {
                // Unknown or future command type: leave or acknowledge if needed
            }
        }
    }

    private suspend fun executeStartArrestCommandLocked(
        api: ArrestCloudApi,
        myUserId: String,
        myDeviceId: String,
        command: SessionCommandDto,
        nowMs: Long
    ) {
        val payload = command.payload
        val cloudSessionId = command.sessionId
            ?: payload["sessionId"]?.jsonPrimitive?.contentOrNull()
            ?: return

        // Check if already mapped and running locally (idempotent replay)
        val existingLocalId = engineBridge.getLocalSessionIdForCloudSession(cloudSessionId)
        val currentActive = engineBridge.activeSession.value
        if (existingLocalId != null) {
            if (currentActive?.sessionId == existingLocalId && currentActive.state.isInProgress) {
                runCatching {
                    api.acknowledgeSession(
                        sessionId = cloudSessionId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "STARTED",
                            localSessionId = existingLocalId,
                            localEngineState = currentActive.state.name
                        )
                    )
                    api.acknowledgeCommand(
                        commandId = command.commandId,
                        body = AcknowledgeCommandRequestDto(
                            deviceId = myDeviceId,
                            status = "EXECUTED"
                        )
                    )
                }
                return
            } else {
                val replayReason = "Session $cloudSessionId has already been executed on this device and cannot be replayed"
                engineBridge.recordDiagnosticEvent(
                    eventType = "COMMAND_REPLAY_REJECTED",
                    details = replayReason,
                    severity = "WARN",
                    sessionId = cloudSessionId
                )
                runCatching {
                    api.acknowledgeSession(
                        sessionId = cloudSessionId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "REJECTED",
                            localSessionId = existingLocalId,
                            localEngineState = currentActive?.state?.name ?: "IDLE",
                            reason = replayReason
                        )
                    )
                    api.acknowledgeCommand(
                        commandId = command.commandId,
                        body = AcknowledgeCommandRequestDto(
                            deviceId = myDeviceId,
                            status = "REJECTED",
                            failureReason = replayReason
                        )
                    )
                }
                return
            }
        }

        val sessionTypeStr = payload["sessionType"]?.jsonPrimitive?.contentOrNull() ?: "PARTNER_ARREST"
        val sessionType = runCatching { SessionType.valueOf(sessionTypeStr) }
            .getOrDefault(SessionType.PARTNER_ARREST)
        val durationMinutes = payload["durationMinutes"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, 720) ?: 30
        val goal = payload["goal"]?.jsonPrimitive?.contentOrNull()?.ifBlank { "Partner Focus Session" }
            ?: "Partner Focus Session"
        val ruleModeStr = payload["ruleMode"]?.jsonPrimitive?.contentOrNull() ?: "BLOCK_SELECTED"
        val ruleMode = runCatching { BlockingRuleMode.valueOf(ruleModeStr) }
            .getOrDefault(BlockingRuleMode.BLOCK_SELECTED)
        val overlayStatusBar = payload["overlayStatusBar"]?.jsonPrimitive?.booleanOrNull ?: false
        val disableEmergencyExit = payload["disableEmergencyExit"]?.jsonPrimitive?.booleanOrNull ?: false
        val authorizationId = payload["authorizationId"]?.jsonPrimitive?.contentOrNull()
        val initiatedByUserId = payload["initiatedByUserId"]?.jsonPrimitive?.contentOrNull()
            ?: command.issuedByUserId

        var matchingPerm = authorizationId?.let { cachedGrantedPermissions[it] }
        if (matchingPerm == null && !authorizationId.isNullOrBlank()) {
            runCatching {
                val fetchRes = api.getPartnerPermission(authorizationId)
                if (fetchRes.isSuccessful) {
                    val p = fetchRes.body()?.permission
                    if (p != null) {
                        cachedGrantedPermissions[p.permissionId] = p
                        matchingPerm = p
                    }
                }
            }
        }

        val allowedCategories = parseStringList(payload["allowedCategories"])
            .mapNotNull { token -> runCatching { AllowedCategory.valueOf(token) }.getOrNull() }
            .toSet()
            .ifEmpty { setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS) }

        val blockedPackages = parseStringListOrNull(payload["blockedPackages"])
        val allowedStudyPackages = parseStringListOrNull(payload["allowedStudyPackages"])

        // Step 5: Match commands to an approved authorization
        if (sessionType != SessionType.SELF_ARREST) {
            val validationError = validateCommandAuthorization(
                myUserId = myUserId,
                myDeviceId = myDeviceId,
                authorizationId = authorizationId,
                initiatedByUserId = initiatedByUserId,
                cloudSessionId = cloudSessionId,
                commandPayload = payload,
                durationMinutes = durationMinutes,
                ruleMode = ruleMode,
                allowedCategories = allowedCategories,
                blockedPackages = blockedPackages,
                allowedStudyPackages = allowedStudyPackages,
                overlayStatusBar = overlayStatusBar,
                disableEmergencyExit = disableEmergencyExit,
                matchingPerm = matchingPerm,
                nowMs = nowMs
            )
            if (validationError != null) {
                engineBridge.recordDiagnosticEvent(
                    eventType = "AUTHORIZATION_VALIDATION",
                    details = "Validation failed for command ${command.commandId} (session $cloudSessionId, auth $authorizationId): $validationError",
                    severity = "WARN",
                    sessionId = cloudSessionId
                )
                runCatching {
                    api.acknowledgeSession(
                        sessionId = cloudSessionId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "REJECTED",
                            localEngineState = engineBridge.activeSession.value?.state?.name ?: "IDLE",
                            reason = validationError
                        )
                    )
                    api.acknowledgeCommand(
                        commandId = command.commandId,
                        body = AcknowledgeCommandRequestDto(
                            deviceId = myDeviceId,
                            status = "REJECTED",
                            failureReason = validationError
                        )
                    )
                }
                return
            }
            engineBridge.recordDiagnosticEvent(
                eventType = "AUTHORIZATION_VALIDATION",
                details = "Validation succeeded for command ${command.commandId} (session $cloudSessionId, auth $authorizationId)",
                severity = "INFO",
                sessionId = cloudSessionId
            )
        }

        // Notify backend that target device accepted command and is preparing engine (phase -> EXECUTING)
        runCatching {
            api.acknowledgeSession(
                sessionId = cloudSessionId,
                body = AcknowledgeSessionRequestDto(
                    deviceId = myDeviceId,
                    outcome = "ACCEPTED",
                    localEngineState = "PREPARING"
                )
            )
        }

        // Snapshot local Self-Arrest rules and apply partner session package lists
        if (sessionType != SessionType.SELF_ARREST) {
            engineBridge.applyTemporaryPartnerRulesWithBackup(
                blockedPackages = blockedPackages,
                allowedStudyPackages = allowedStudyPackages
            )
        }

        // Step 6: Map remote configuration into existing SessionConfig
        val sessionConfig = SessionConfig(
            sessionType = sessionType,
            durationMinutes = durationMinutes,
            goal = goal,
            allowedCategories = allowedCategories,
            ruleMode = ruleMode,
            overlayStatusBar = overlayStatusBar,
            disableEmergencyExit = disableEmergencyExit,
            cloudSessionId = cloudSessionId,
            authorizationId = authorizationId,
            controllingPartnerUserId = initiatedByUserId,
            controllingPartnerUsername = matchingPerm?.partner?.username
        )

        // Step 7 & 8: Start local SessionController & persist cloud-to-local session ID mapping
        when (val startRes = engineBridge.startSession(sessionConfig)) {
            is StartSessionResult.Started -> {
                val localSessionId = startRes.snapshot.sessionId
                engineBridge.saveCloudToLocalSessionMapping(
                    cloudSessionId = cloudSessionId,
                    localSessionId = localSessionId
                )
                pendingCloudSessionSyncIds.add(cloudSessionId)
                engineBridge.recordDiagnosticEvent(
                    eventType = "LOCAL_SESSION_STARTUP",
                    details = "Started local session $localSessionId for cloud session $cloudSessionId",
                    severity = "INFO",
                    sessionId = cloudSessionId
                )

                // Step 9: Acknowledge the actual STARTED / EXECUTED result
                runCatching {
                    api.acknowledgeSession(
                        sessionId = cloudSessionId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "STARTED",
                            localSessionId = localSessionId,
                            localEngineState = "ACTIVE"
                        )
                    )
                    api.acknowledgeCommand(
                        commandId = command.commandId,
                        body = AcknowledgeCommandRequestDto(
                            deviceId = myDeviceId,
                            status = "EXECUTED"
                        )
                    )
                }.onSuccess {
                    engineBridge.recordDiagnosticEvent(
                        eventType = "COMMAND_ACKNOWLEDGMENT",
                        details = "Acknowledged START_ARREST (command ${command.commandId}, session $cloudSessionId) as EXECUTED / STARTED",
                        severity = "INFO",
                        sessionId = cloudSessionId
                    )
                }.onFailure { err ->
                    engineBridge.recordDiagnosticEvent(
                        eventType = "COMMAND_ACKNOWLEDGMENT",
                        details = "Failed acknowledging START_ARREST (command ${command.commandId}): ${err.message}",
                        severity = "ERROR",
                        sessionId = cloudSessionId
                    )
                }
                _coordinatorBanner.value = "Remote ${sessionType.label} active: $goal (${durationMinutes}m)"
            }
            is StartSessionResult.DuplicatePrevented -> {
                if (sessionType != SessionType.SELF_ARREST) {
                    engineBridge.restoreBackedUpLocalRulesIfPresent()
                }
                val reason = "Target device already has an active session (${startRes.existingSessionId})"
                engineBridge.recordDiagnosticEvent(
                    eventType = "LOCAL_SESSION_STARTUP",
                    details = "Duplicate session prevented: $reason",
                    severity = "WARN",
                    sessionId = cloudSessionId
                )
                runCatching {
                    api.acknowledgeSession(
                        sessionId = cloudSessionId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "REJECTED",
                            localSessionId = startRes.existingSessionId,
                            localEngineState = startRes.state.name,
                            reason = reason
                        )
                    )
                    api.acknowledgeCommand(
                        commandId = command.commandId,
                        body = AcknowledgeCommandRequestDto(
                            deviceId = myDeviceId,
                            status = "REJECTED",
                            failureReason = reason
                        )
                    )
                }
            }
            is StartSessionResult.Error -> {
                if (sessionType != SessionType.SELF_ARREST) {
                    engineBridge.restoreBackedUpLocalRulesIfPresent()
                }
                engineBridge.recordDiagnosticEvent(
                    eventType = "LOCAL_SESSION_STARTUP",
                    details = "Engine startup error: ${startRes.message}",
                    severity = "ERROR",
                    sessionId = cloudSessionId
                )
                runCatching {
                    api.acknowledgeSession(
                        sessionId = cloudSessionId,
                        body = AcknowledgeSessionRequestDto(
                            deviceId = myDeviceId,
                            outcome = "REJECTED",
                            localEngineState = "IDLE",
                            reason = startRes.message
                        )
                    )
                    api.acknowledgeCommand(
                        commandId = command.commandId,
                        body = AcknowledgeCommandRequestDto(
                            deviceId = myDeviceId,
                            status = "FAILED",
                            failureReason = startRes.message
                        )
                    )
                }
            }
        }
    }

    private fun validateCommandAuthorization(
        myUserId: String,
        myDeviceId: String,
        authorizationId: String?,
        initiatedByUserId: String,
        cloudSessionId: String,
        commandPayload: JsonObject,
        durationMinutes: Int,
        ruleMode: BlockingRuleMode,
        allowedCategories: Set<AllowedCategory>,
        blockedPackages: List<String>?,
        allowedStudyPackages: List<String>?,
        overlayStatusBar: Boolean,
        disableEmergencyExit: Boolean,
        matchingPerm: PartnerPermissionDto?,
        nowMs: Long
    ): String? {
        if (authorizationId.isNullOrBlank()) {
            return "Missing authorizationId on remote partner command"
        }
        val perm = matchingPerm ?: cachedGrantedPermissions[authorizationId]
            ?: return "Authorization $authorizationId is not active or was revoked by device owner"

        if (perm.owner.id != myUserId) {
            return "Authorization owner mismatch"
        }
        if (perm.targetDevice.id != myDeviceId) {
            return "Authorization target device mismatch"
        }
        if (perm.partner.id != initiatedByUserId) {
            return "Command initiator does not match authorized partner"
        }
        if (perm.revokedAt != null) {
            return "Authorization was revoked"
        }
        val expiresMs = parseIsoUtcMs(perm.expiresAt)
        if (expiresMs == null || nowMs >= expiresMs) {
            return "Authorization has expired"
        }
        val validFromMs = parseIsoUtcMs(perm.validFrom)
        if (validFromMs != null && nowMs < validFromMs) {
            return "Authorization is not yet active"
        }

        // Differentiate one-time authorization vs standing authorization
        if (perm.oneTimeOnly) {
            if (perm.usedCount > 1) {
                return "One-time authorization has been consumed multiple times"
            }
            if (perm.usedCount == 1) {
                // Must be bound to this exact cloud session
                if (!perm.boundSessionId.isNullOrBlank() && perm.boundSessionId != cloudSessionId) {
                    return "One-time authorization has already been consumed for another session (${perm.boundSessionId})"
                }
            }
            val proposalId = commandPayload["proposalId"]?.jsonPrimitive?.contentOrNull()
            if (!perm.proposalId.isNullOrBlank() && !proposalId.isNullOrBlank() && perm.proposalId != proposalId) {
                return "Command proposal ID mismatch with authorization"
            }
            val cmdApprovedRev = commandPayload["approvedRevision"]?.jsonPrimitive?.intOrNull
            if (perm.approvedRevision != null && cmdApprovedRev != null && perm.approvedRevision != cmdApprovedRev) {
                return "Command proposal revision mismatch with authorization"
            }
        } else {
            // Standing authorizations must be currently active (not exhausted/expired)
            if (!perm.isCurrentlyActive) {
                return "Standing authorization is not currently active"
            }
        }

        // Validate exact blocking configuration if present, otherwise check standing limits
        val exact = perm.exactBlockingConfiguration
        if (exact != null) {
            if (durationMinutes != exact.durationMinutes) {
                return "Requested duration ($durationMinutes min) does not match approved configuration (${exact.durationMinutes} min)"
            }
            if (ruleMode.name != exact.blockingConfiguration.ruleMode) {
                return "Rule mode (${ruleMode.name}) does not match approved configuration (${exact.blockingConfiguration.ruleMode})"
            }
            if (overlayStatusBar != exact.blockingConfiguration.overlayStatusBar) {
                return "overlayStatusBar does not match approved configuration"
            }
            if (disableEmergencyExit != exact.blockingConfiguration.disableEmergencyExit) {
                return "disableEmergencyExit does not match approved configuration"
            }
            val approvedCats = exact.blockingConfiguration.allowedCategories.toSet()
            val reqCats = allowedCategories.map { it.name }.toSet()
            if (reqCats != approvedCats) {
                return "Allowed categories do not match approved configuration"
            }
            if (exact.blockingConfiguration.blockedPackages != null) {
                val approvedBlocked = exact.blockingConfiguration.blockedPackages.toSet()
                val reqBlocked = blockedPackages?.toSet() ?: emptySet()
                if (reqBlocked != approvedBlocked) {
                    return "Blocked packages do not match approved configuration"
                }
            }
            if (exact.blockingConfiguration.allowedStudyPackages != null) {
                val approvedStudy = exact.blockingConfiguration.allowedStudyPackages.toSet()
                val reqStudy = allowedStudyPackages?.toSet() ?: emptySet()
                if (reqStudy != approvedStudy) {
                    return "Allowed study packages do not match approved configuration"
                }
            }
        } else {
            if (durationMinutes > perm.maximumSessionDuration) {
                return "Requested duration ($durationMinutes min) exceeds authorized maximum (${perm.maximumSessionDuration} min)"
            }
            val limits = perm.blockingConfigLimits
            if (!limits.allowedRuleModes.contains(ruleMode.name)) {
                return "Rule mode ${ruleMode.name} not permitted by authorization limits"
            }
            if (disableEmergencyExit && !limits.allowDisableEmergencyExit) {
                return "Disabling emergency exit is not permitted by authorization limits"
            }
            if (overlayStatusBar && !limits.allowOverlayStatusBar) {
                return "Overlay status bar is not permitted by authorization limits"
            }
            val reqCats = allowedCategories.map { it.name }.toSet()
            for (req in limits.requiredAllowedCategories) {
                if (!reqCats.contains(req)) {
                    return "Missing required category $req in requested configuration"
                }
            }
        }

        return null
    }

    private suspend fun executeRemoteEndOrReleaseCommandLocked(
        api: ArrestCloudApi,
        myDeviceId: String,
        command: SessionCommandDto
    ) {
        val reason = command.payload["reason"]?.jsonPrimitive?.contentOrNull()
            ?: "Session released remotely by controlling partner (${command.commandType})"
        val current = engineBridge.activeSession.value
        val targetCloudId = command.sessionId ?: command.payload["sessionId"]?.jsonPrimitive?.contentOrNull()

        if (current != null && current.state.isInProgress) {
            val mappedCloudId = current.cloudSessionId
                ?: engineBridge.getCloudSessionIdForLocalSession(current.sessionId)
            if (targetCloudId == null || mappedCloudId == null || mappedCloudId == targetCloudId) {
                engineBridge.forceCompleteRemoteSession(reason = reason)
                _coordinatorBanner.value = "Session released by partner: $reason"
            }
        }

        runCatching {
            api.acknowledgeCommand(
                commandId = command.commandId,
                body = AcknowledgeCommandRequestDto(
                    deviceId = myDeviceId,
                    status = "EXECUTED"
                )
            )
        }
    }

    private suspend fun executeApplyTemporaryAppAccessLocked(
        api: ArrestCloudApi,
        myDeviceId: String,
        command: SessionCommandDto,
        nowMs: Long
    ) {
        val payload = command.payload
        val grantId = payload["grantId"]?.jsonPrimitive?.contentOrNull() ?: command.commandId
        val packageName = payload["packageName"]?.jsonPrimitive?.contentOrNull() ?: return
        val expiresAtUtcStr = payload["expiresAtUtc"]?.jsonPrimitive?.contentOrNull()
        val durationMinutes = payload["durationMinutes"]?.jsonPrimitive?.intOrNull ?: 10
        val expiresAtMs = expiresAtUtcStr?.let { parseIsoUtcMs(it) } ?: (nowMs + durationMinutes * 60_000L)

        engineBridge.applyTemporaryAppAccessOverride(grantId, packageName, expiresAtMs)

        runCatching {
            api.acknowledgeCommand(
                commandId = command.commandId,
                body = AcknowledgeCommandRequestDto(deviceId = myDeviceId, status = "EXECUTED")
            )
        }
    }

    private suspend fun executeRevokeTemporaryAppAccessLocked(
        api: ArrestCloudApi,
        myDeviceId: String,
        command: SessionCommandDto
    ) {
        val payload = command.payload
        val grantId = payload["grantId"]?.jsonPrimitive?.contentOrNull() ?: return

        engineBridge.revokeTemporaryAppAccessOverride(grantId)

        runCatching {
            api.acknowledgeCommand(
                commandId = command.commandId,
                body = AcknowledgeCommandRequestDto(deviceId = myDeviceId, status = "EXECUTED")
            )
        }
    }

    private suspend fun executeUpdateSessionRulesLocked(
        api: ArrestCloudApi,
        myDeviceId: String,
        command: SessionCommandDto
    ) {
        val payload = command.payload
        val action = payload["action"]?.jsonPrimitive?.contentOrNull()
        if (action == "SESSION_EXTENSION") {
            val extMinutes = payload["extendedByMinutes"]?.jsonPrimitive?.intOrNull ?: 15
            engineBridge.extendActiveSession(extMinutes * 60_000L)
        }

        runCatching {
            api.acknowledgeCommand(
                commandId = command.commandId,
                body = AcknowledgeCommandRequestDto(deviceId = myDeviceId, status = "EXECUTED")
            )
        }
    }

    private suspend fun executeSyncSessionCommandLocked(
        api: ArrestCloudApi,
        myDeviceId: String,
        command: SessionCommandDto
    ) {
        val cloudSessionId = command.sessionId
            ?: command.payload["sessionId"]?.jsonPrimitive?.contentOrNull()
        if (cloudSessionId != null) {
            val localSessionId = engineBridge.getLocalSessionIdForCloudSession(cloudSessionId)
                ?: engineBridge.activeSession.value?.sessionId
            if (localSessionId != null) {
                reconcileSessionEventsForCloudSession(cloudSessionId, localSessionId)
            }
        }
        runCatching {
            api.acknowledgeCommand(
                commandId = command.commandId,
                body = AcknowledgeCommandRequestDto(
                    deviceId = myDeviceId,
                    status = "EXECUTED"
                )
            )
        }
    }

    /**
     * Step 10: Synchronize deduplicated local SessionController transitions to the backend.
     */
    suspend fun reconcileSessionEventsForCloudSession(
        cloudSessionId: String,
        localSessionId: String
    ): CloudResult<ArrestSessionDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, creds, api) = (resolved as CloudResult.Success).data
        val myDeviceId = creds.registeredDevice?.id ?: return@withContext CloudResult.Failure(
            CloudError.Unauthorized(message = "Device is not registered.")
        )

        val transitions = engineBridge.getTransitionsForSession(localSessionId)
        val events = transitions.mapNotNull { t ->
            val eventType = when (t.toState) {
                SessionState.ACTIVE.name -> {
                    if (t.fromState == SessionState.PAUSED.name || t.fromState == SessionState.INTERRUPTED.name) {
                        "SESSION_RESUMED"
                    } else {
                        "SESSION_STARTED"
                    }
                }
                SessionState.PAUSED.name -> "SESSION_PAUSED"
                SessionState.INTERRUPTED.name -> "SESSION_INTERRUPTED"
                SessionState.COMPLETED.name -> "SESSION_COMPLETED"
                SessionState.EMERGENCY_STOPPED.name -> "EMERGENCY_STOPPED"
                else -> null
            } ?: return@mapNotNull null

            SyncEventItemDto(
                clientEventId = "evt_${localSessionId}_${t.id}_${t.toState}_${t.timestampMs}",
                eventType = eventType,
                fromEngineState = t.fromState,
                toEngineState = t.toState,
                occurredAtClientMs = t.timestampMs,
                localSessionId = localSessionId,
                reason = t.reason.take(250)
            )
        }

        if (events.isEmpty()) {
            return@withContext CloudResult.Failure(
                CloudError.Validation(message = "No session events to synchronize.")
            )
        }

        val res = executeApiCall {
            api.postSessionEvents(
                sessionId = cloudSessionId,
                body = PostSessionEventsRequestDto(
                    deviceId = myDeviceId,
                    events = events.takeLast(50)
                )
            )
        }
        if (res is CloudResult.Success) {
            val snap = engineBridge.activeSession.value
            if (snap == null || snap.sessionId != localSessionId || snap.state.isTerminal) {
                pendingCloudSessionSyncIds.remove(cloudSessionId)
            }
            CloudResult.Success(res.data.session)
        } else {
            pendingCloudSessionSyncIds.add(cloudSessionId)
            res as CloudResult.Failure
        }
    }

    suspend fun reconcileAllPendingSessionEvents() = withContext(Dispatchers.IO) {
        val currentSnap = engineBridge.activeSession.value
        if (currentSnap?.cloudSessionId != null) {
            pendingCloudSessionSyncIds.add(currentSnap.cloudSessionId)
        }
        for (cloudId in pendingCloudSessionSyncIds.toList()) {
            val localId = engineBridge.getLocalSessionIdForCloudSession(cloudId)
                ?: currentSnap?.takeIf { it.cloudSessionId == cloudId }?.sessionId
                ?: continue
            reconcileSessionEventsForCloudSession(cloudId, localId)
        }
    }

    // ========================================================================
    // V0.5 Section 4.3 — Scheduling Engine (Server Sync + Local Cache + Bounds)
    // ========================================================================

    suspend fun cacheUpcomingSchedules(schedules: List<ScheduledArrestDto>) = withContext(Dispatchers.IO) {
        runCatching {
            val activeOnly = schedules.filter { it.status == "SCHEDULED" }
            engineBridge.saveCachedSchedulesJson(json.encodeToString(activeOnly))
        }
    }

    fun readCachedSchedules(): List<ScheduledArrestDto> {
        val raw = engineBridge.readCachedSchedulesJson() ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ScheduledArrestDto>>(raw)
        }.getOrDefault(emptyList())
    }

    /**
     * Evaluates cached and server schedules for this device:
     * - Never starts a cancelled, revoked, or expired schedule.
     * - Never starts a schedule whose scheduled end time has already passed.
     * - When online, triggers the schedule on the server and polls the resulting command.
     * - When offline during a valid scheduled window, starts the local session for the
     *   remaining window duration if the authorization is still valid locally.
     */
    suspend fun evaluateDueSchedules(): List<String> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        val api = (resolved as? CloudResult.Success)?.data?.third
        val creds = (resolved as? CloudResult.Success)?.data?.second
        val myDeviceId = creds?.registeredDevice?.id ?: return@withContext emptyList()
        val myUserId = creds.userId ?: return@withContext emptyList()

        if (api != null && connectivityChecker.isOnline()) {
            runCatching {
                val res = api.listScheduledArrests(role = "owner", status = "SCHEDULED", deviceId = myDeviceId, limit = 50)
                if (res.isSuccessful) {
                    cacheUpcomingSchedules(res.body()?.items ?: emptyList())
                }
            }
        }

        val nowMs = clockNowMs()
        val cached = readCachedSchedules()
        val triggeredScheduleIds = mutableListOf<String>()

        for (sched in cached) {
            if (sched.targetDevice.id != myDeviceId) continue
            if (sched.status != "SCHEDULED") continue

            val nextStartStr = sched.nextOccurrenceAt ?: sched.scheduledStartAt ?: continue
            val startMs = parseIsoUtcMs(nextStartStr) ?: continue
            val endMs = startMs + sched.durationMinutes.coerceAtLeast(1) * 60_000L

            // Never start before due window
            if (nowMs < startMs) continue

            // Never start after scheduled end time has already passed!
            if (nowMs >= endMs) {
                continue
            }

            if (api != null && connectivityChecker.isOnline()) {
                val trigRes = executeApiCall {
                    api.triggerScheduledArrest(sched.scheduleId)
                }
                if (trigRes is CloudResult.Success) {
                    triggeredScheduleIds.add(sched.scheduleId)
                }
            } else {
                // Offline at scheduled start time: verify local authorization & start for remaining window
                val permId = sched.permissionId
                val perm = permId?.let { cachedGrantedPermissions[it] }
                val permValid = perm != null &&
                    perm.owner.id == myUserId &&
                    perm.revokedAt == null &&
                    perm.isCurrentlyActive &&
                    (parseIsoUtcMs(perm.expiresAt)?.let { nowMs < it } == true)

                if (permValid) {
                    val remainingMin = ((endMs - nowMs + 59_999L) / 60_000L).toInt()
                        .coerceIn(1, sched.durationMinutes)
                    val cfg = sched.blockingConfiguration
                    engineBridge.applyTemporaryPartnerRulesWithBackup(
                        blockedPackages = cfg.blockedPackages,
                        allowedStudyPackages = cfg.allowedStudyPackages
                    )
                    val allowedCats = cfg.allowedCategories
                        .mapNotNull { runCatching { AllowedCategory.valueOf(it) }.getOrNull() }
                        .toSet()
                        .ifEmpty { setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS) }
                    val ruleMode = runCatching { BlockingRuleMode.valueOf(cfg.ruleMode) }
                        .getOrDefault(BlockingRuleMode.BLOCK_SELECTED)
                    val sessionType = runCatching { SessionType.valueOf(sched.sessionType) }
                        .getOrDefault(SessionType.PARTNER_ARREST)

                    val startRes = engineBridge.startSession(
                        SessionConfig(
                            sessionType = sessionType,
                            durationMinutes = remainingMin,
                            goal = sched.goal,
                            allowedCategories = allowedCats,
                            ruleMode = ruleMode,
                            overlayStatusBar = cfg.overlayStatusBar,
                            disableEmergencyExit = cfg.disableEmergencyExit,
                            authorizationId = permId,
                            controllingPartnerUserId = sched.partner.id,
                            controllingPartnerUsername = sched.partner.username
                        )
                    )
                    if (startRes is StartSessionResult.Started) {
                        triggeredScheduleIds.add(sched.scheduleId)
                    }
                }
            }
        }

        // Remove or update locally triggered one-time schedules from cache
        if (triggeredScheduleIds.isNotEmpty()) {
            val updatedCache = cached.map { s ->
                if (s.scheduleId in triggeredScheduleIds && s.scheduleType != "RECURRING") {
                    s.copy(status = "COMPLETED")
                } else {
                    s
                }
            }
            cacheUpcomingSchedules(updatedCache)
        }

        triggeredScheduleIds
    }

    // ========================================================================
    // V0.5 Section 4.2 & 4.5 — Remote Session Initiation & Live Dashboard
    // ========================================================================

    suspend fun startRemotePartnerSession(
        targetDeviceId: String,
        authorizationId: String,
        durationMinutes: Int,
        goal: String,
        blockingConfiguration: BlockingConfigurationDto,
        sessionType: String = "PARTNER_ARREST"
    ): CloudResult<CreateRemoteSessionResponseDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, _, api) = (resolved as CloudResult.Success).data
        val res = executeApiCall {
            api.createRemoteSession(
                CreateRemoteSessionRequestDto(
                    sessionType = sessionType,
                    targetDeviceId = targetDeviceId,
                    authorizationId = authorizationId,
                    requestedDurationMinutes = durationMinutes.coerceIn(1, 720),
                    goal = goal.trim().ifBlank { "Partner Focus Session" },
                    blockingConfiguration = blockingConfiguration
                )
            )
        }
        if (res is CloudResult.Success) {
            refreshLiveDashboard()
        }
        res
    }

    suspend fun requestRemoteEndSession(
        sessionId: String,
        reason: String? = "Ended remotely by controlling partner"
    ): CloudResult<SessionCommandDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, _, api) = (resolved as CloudResult.Success).data
        val res = executeApiCall {
            api.endRemoteSession(
                sessionId = sessionId,
                body = RemoteSessionCommandRequestDto(reason = reason)
            )
        }
        when (res) {
            is CloudResult.Success -> {
                refreshLiveDashboard()
                CloudResult.Success(res.data.command)
            }
            is CloudResult.Failure -> res
        }
    }

    suspend fun requestRemoteSyncSession(sessionId: String): CloudResult<SessionCommandDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data
            val res = executeApiCall {
                api.syncRemoteSession(sessionId = sessionId)
            }
            when (res) {
                is CloudResult.Success -> {
                    refreshLiveDashboard()
                    CloudResult.Success(res.data.command)
                }
                is CloudResult.Failure -> res
            }
        }

    suspend fun refreshLiveDashboard(): CloudResult<List<LiveSessionDashboardItemDto>> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data
            val res = executeApiCall { api.getLiveDashboardSessions() }
            when (res) {
                is CloudResult.Success -> {
                    _liveDashboardItems.value = res.data.items
                    CloudResult.Success(res.data.items)
                }
                is CloudResult.Failure -> res
            }
        }

    // ========================================================================
    // V0.5 Section 4.4 — Controlled Selectable App Catalog Sharing
    // ========================================================================

    suspend fun uploadSelectableAppCatalog(
        installedApps: List<InstalledAppInfo>
    ): CloudResult<DeviceAppCatalogDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, creds, api) = (resolved as CloudResult.Success).data
        val myDeviceId = creds.registeredDevice?.id ?: return@withContext CloudResult.Failure(
            CloudError.Unauthorized(message = "Device must be registered before sharing selectable app catalog.")
        )

        val localRules = engineBridge.getAllRulesSnapshot().associateBy { it.packageName }
        val filteredItems = installedApps
            .filterNot { isExcludedSystemPackage(it.packageName) }
            .map { app ->
                val rule = localRules[app.packageName]
                SelectableAppCatalogItemDto(
                    packageName = app.packageName,
                    appLabel = app.label,
                    category = if (rule?.ruleType == "ALLOWED_STUDY") "STUDY_APP" else "USER_APP",
                    isStudyCandidate = rule?.ruleType == "ALLOWED_STUDY"
                )
            }
            .take(300)

        val res = executeApiCall {
            api.upsertDeviceAppCatalog(
                deviceId = myDeviceId,
                body = UpsertDeviceAppCatalogRequestDto(apps = filteredItems)
            )
        }
        when (res) {
            is CloudResult.Success -> CloudResult.Success(res.data.catalog)
            is CloudResult.Failure -> res
        }
    }

    suspend fun loadPartnerDeviceAppCatalog(targetDeviceId: String): CloudResult<DeviceAppCatalogDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data
            val res = executeApiCall { api.getDeviceAppCatalog(targetDeviceId) }
            when (res) {
                is CloudResult.Success -> {
                    _partnerDeviceAppCatalog.value = res.data.catalog
                    CloudResult.Success(res.data.catalog)
                }
                is CloudResult.Failure -> res
            }
        }

    fun dismissBanner() {
        _coordinatorBanner.value = null
    }

    companion object {
        private val EXCLUDED_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.arrestme.benchmark"
        )

        fun isExcludedSystemPackage(packageName: String): Boolean {
            val normalized = packageName.trim().lowercase(Locale.US)
            if (normalized in EXCLUDED_SYSTEM_PACKAGES) return true
            if (normalized.startsWith("com.android.internal")) return true
            if (normalized.startsWith("com.android.providers.")) return true
            return false
        }

        fun parseIsoUtcMs(iso: String?): Long? {
            if (iso.isNullOrBlank()) return null
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val parsed = sdf.parse(iso.trim())
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                }
            }
            return null
        }

        private fun parseStringList(element: kotlinx.serialization.json.JsonElement?): List<String> {
            val arr = element as? JsonArray ?: return emptyList()
            return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
        }

        private fun parseStringListOrNull(element: kotlinx.serialization.json.JsonElement?): List<String>? {
            val arr = element as? JsonArray ?: return null
            return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
        }

        private fun JsonPrimitive.contentOrNull(): String? =
            if (this.isString) this.content else this.content.takeIf { it != "null" }
    }
}
