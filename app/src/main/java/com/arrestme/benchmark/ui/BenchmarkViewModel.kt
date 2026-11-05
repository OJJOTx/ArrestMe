package com.arrestme.benchmark.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arrestme.benchmark.ArrestMeApp
import com.arrestme.benchmark.BuildConfig
import com.arrestme.benchmark.core.accessibility.ArrestAccessibilityService
import com.arrestme.benchmark.core.backend.ActiveBackendConfig
import com.arrestme.benchmark.core.backend.BackendAuthState
import com.arrestme.benchmark.core.backend.BackendMode
import com.arrestme.benchmark.core.backend.CloudResult
import com.arrestme.benchmark.core.backend.HealthCheckResult
import com.arrestme.benchmark.core.backend.UrlValidationResult
import com.arrestme.benchmark.core.blocking.InstalledAppInfo
import com.arrestme.benchmark.core.permissions.PermissionStatusSnapshot
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.SessionConfig
import com.arrestme.benchmark.core.session.StartSessionResult
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.data.local.DiagnosticEventEntity
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import com.arrestme.benchmark.data.remote.dto.BlockingConfigurationDto
import com.arrestme.benchmark.data.remote.dto.CounterofferProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.CreatePartnerPermissionRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.DeviceAppCatalogDto
import com.arrestme.benchmark.data.remote.dto.LiveSessionDashboardItemDto
import com.arrestme.benchmark.data.remote.dto.PartnerDiscoveryResponseDto
import com.arrestme.benchmark.data.remote.dto.PartnerProfileDto
import com.arrestme.benchmark.data.remote.dto.PartnershipDto
import com.arrestme.benchmark.data.remote.dto.PendingCountsDto
import com.arrestme.benchmark.data.remote.dto.ProposalDto
import com.arrestme.benchmark.data.remote.dto.ScheduledArrestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ArrestMeApp = application as ArrestMeApp

    val activeSession: StateFlow<ActiveSessionSnapshot?> = app.sessionController.activeSession
    val remainingMs: StateFlow<Long> = app.sessionController.remainingMs

    private val _permissions = MutableStateFlow(app.permissionChecker.inspectAll())
    val permissions: StateFlow<PermissionStatusSnapshot> = _permissions.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val appRules: StateFlow<List<AppRuleEntity>> = app.repository.observeAppRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val sessions: StateFlow<List<SessionEntity>> = app.repository.observeAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val transitions: StateFlow<List<SessionTransitionEntity>> = app.repository.observeRecentTransitions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val diagnosticEvents: StateFlow<List<DiagnosticEventEntity>> = app.repository.observeDiagnosticEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val backendConfig: StateFlow<ActiveBackendConfig> = app.backendConfigRepository.activeConfig
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            ActiveBackendConfig(
                mode = BackendMode.OFFLINE_LOCAL,
                effectiveBaseUrl = null,
                backendIdentity = "offline",
                customUrl = "",
                buildConfigUrl = BuildConfig.API_BASE_URL
            )
        )

    val backendAuthState: StateFlow<BackendAuthState> = app.backendAuthRepository.authState

    private val _healthCheckStatus = MutableStateFlow<HealthCheckResult?>(null)
    val healthCheckStatus: StateFlow<HealthCheckResult?> = _healthCheckStatus.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _isAuthBusy = MutableStateFlow(false)
    val isAuthBusy: StateFlow<Boolean> = _isAuthBusy.asStateFlow()

    private val _isSyncingAccount = MutableStateFlow(false)
    val isSyncingAccount: StateFlow<Boolean> = _isSyncingAccount.asStateFlow()

    private val _authErrorMessage = MutableStateFlow<String?>(null)
    val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

    // V0.4 Partner System StateFlows
    val partners: StateFlow<List<PartnershipDto>> = app.partnerSystemRepository.partners
    val incomingPartnerRequests: StateFlow<List<PartnershipDto>> = app.partnerSystemRepository.incomingRequests
    val outgoingPartnerRequests: StateFlow<List<PartnershipDto>> = app.partnerSystemRepository.outgoingRequests
    val pendingCounts: StateFlow<PendingCountsDto> = app.partnerSystemRepository.pendingCounts
    val myInviteCode: StateFlow<String?> = app.partnerSystemRepository.myInviteCode
    val discoveryResult: StateFlow<PartnerDiscoveryResponseDto?> = app.partnerSystemRepository.discoveryResult
    val selectedPartnerProfile: StateFlow<PartnerProfileDto?> = app.partnerSystemRepository.selectedPartnerProfile
    val proposals: StateFlow<List<ProposalDto>> = app.partnerSystemRepository.proposals
    val selectedProposal: StateFlow<ProposalDto?> = app.partnerSystemRepository.selectedProposal
    val scheduledArrests: StateFlow<List<ScheduledArrestDto>> = app.partnerSystemRepository.scheduledArrests
    val isPartnerSystemLoading: StateFlow<Boolean> = app.partnerSystemRepository.isLoading
    val partnerErrorMessage: StateFlow<String?> = app.partnerSystemRepository.errorMessage

    // V0.5 Cloud Command & Remote Arrest Coordinator StateFlows
    val liveDashboardItems: StateFlow<List<LiveSessionDashboardItemDto>> = app.cloudCommandCoordinator.liveDashboardItems
    val partnerDeviceAppCatalog: StateFlow<DeviceAppCatalogDto?> = app.cloudCommandCoordinator.partnerDeviceAppCatalog
    val isSyncingCommands: StateFlow<Boolean> = app.cloudCommandCoordinator.isSyncingCommands
    val coordinatorBanner: StateFlow<String?> = app.cloudCommandCoordinator.coordinatorBanner

    init {
        refreshPermissions()
        loadInstalledApps()

        viewModelScope.launch {
            ArrestAccessibilityService.isConnected.collect {
                _permissions.value = app.permissionChecker.inspectAll()
            }
        }
    }

    fun refreshPermissions() {
        _permissions.value = app.permissionChecker.inspectAll()
        viewModelScope.launch {
            app.sessionController.checkAndCompleteIfExpired()
        }
    }

    fun loadInstalledApps() {
        if (_isLoadingApps.value) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            val list = withContext(Dispatchers.IO) {
                app.rulesEvaluator.queryInstalledLaunchableApps()
            }
            _installedApps.value = list
            _isLoadingApps.value = false
        }
    }

    fun updateAppRule(packageName: String, appLabel: String, ruleType: AppRuleType?) {
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.setAppRule(packageName, appLabel, ruleType)
        }
    }

    fun startArrestSession(config: SessionConfig) {
        viewModelScope.launch {
            refreshPermissions()
            when (val res = app.sessionController.startSession(config)) {
                is StartSessionResult.Started -> {
                    _statusBanner.value = "Session ${res.snapshot.sessionId} started (${config.durationMinutes} min)."
                }
                is StartSessionResult.DuplicatePrevented -> {
                    _statusBanner.value = "Duplicate session prevented: ${res.existingSessionId} is already ${res.state}."
                }
                is StartSessionResult.Error -> {
                    _statusBanner.value = "Error: ${res.message}"
                }
            }
        }
    }

    fun pauseSession() {
        viewModelScope.launch {
            app.sessionController.pauseSession("Paused from Active Session screen")
        }
    }

    fun resumeSession() {
        viewModelScope.launch {
            refreshPermissions()
            app.sessionController.resumeSession("Resumed from Active Session screen")
        }
    }

    fun emergencyStopSession() {
        viewModelScope.launch {
            app.sessionController.emergencyStop("Explicit user emergency exit from Active Session screen")
            _statusBanner.value = "Emergency Exit executed. Session stopped."
        }
    }

    fun resetFinishedSessionToIdle() {
        viewModelScope.launch {
            app.sessionController.resetToIdle()
        }
    }

    fun reconcileUsageStatsForAllRecent() {
        viewModelScope.launch {
            val recent = sessions.value.take(5)
            for (s in recent) {
                app.usageMonitor.reconcileSessionWithUsageStats(s.sessionId)
            }
            _statusBanner.value = "UsageStats reconciliation completed for ${recent.size} session(s)."
        }
    }

    fun clearDiagnosticLogs() {
        viewModelScope.launch {
            app.repository.clearDiagnosticEvents()
            _statusBanner.value = "Diagnostic event history cleared."
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun dismissAuthError() {
        _authErrorMessage.value = null
    }

    fun writeJsonToUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val permsMap = app.permissionChecker.inspectAll().toSummaryMap()
                val json = app.repository.buildBenchmarkReportJson(permsMap)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                _statusBanner.value = "Benchmark JSON saved successfully."
            }.onFailure { e ->
                _statusBanner.value = "JSON save failed: ${e.message}"
            }
        }
    }

    fun shareBenchmarkJson(onShareIntentReady: (Intent) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val permsMap = app.permissionChecker.inspectAll().toSummaryMap()
                val json = app.repository.buildBenchmarkReportJson(permsMap)
                val exportFile = app.repository.writeBenchmarkExportFile(json)
                val uri = FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    exportFile
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Arrest Me V0.3 Benchmark Report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onShareIntentReady(Intent.createChooser(sendIntent, "Export Benchmark JSON"))
            }.onFailure { e ->
                _statusBanner.value = "Export failed: ${e.message}"
            }
        }
    }

    fun login(email: String, password: String) {
        if (_isAuthBusy.value) return
        viewModelScope.launch {
            _isAuthBusy.value = true
            _authErrorMessage.value = null
            when (val res = app.backendAuthRepository.login(email, password)) {
                is CloudResult.Success -> {
                    val handle = res.data.username ?: res.data.email ?: "account"
                    _statusBanner.value = "Signed in as @$handle."
                }
                is CloudResult.Failure -> {
                    _authErrorMessage.value = res.error.message
                }
            }
            _isAuthBusy.value = false
        }
    }

    fun register(username: String, email: String, password: String, displayName: String?) {
        if (_isAuthBusy.value) return
        viewModelScope.launch {
            _isAuthBusy.value = true
            _authErrorMessage.value = null
            when (val res = app.backendAuthRepository.register(username, email, password, displayName)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Account @${res.data.username} registered and device linked."
                }
                is CloudResult.Failure -> {
                    _authErrorMessage.value = res.error.message
                }
            }
            _isAuthBusy.value = false
        }
    }

    fun logout() {
        if (_isAuthBusy.value) return
        viewModelScope.launch {
            _isAuthBusy.value = true
            _authErrorMessage.value = null
            app.backendAuthRepository.logout(deregisterDevice = true)
            _statusBanner.value = "Signed out and deregistered device from server."
            _isAuthBusy.value = false
        }
    }

    fun syncAccountAndDevice() {
        if (_isSyncingAccount.value) return
        viewModelScope.launch {
            _isSyncingAccount.value = true
            _authErrorMessage.value = null
            when (val res = app.backendAuthRepository.syncAccountAndDevice()) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Cloud profile and device synchronized."
                }
                is CloudResult.Failure -> {
                    _authErrorMessage.value = res.error.message
                }
            }
            _isSyncingAccount.value = false
        }
    }

    fun updateDisplayName(newDisplayName: String) {
        if (_isAuthBusy.value) return
        viewModelScope.launch {
            _isAuthBusy.value = true
            _authErrorMessage.value = null
            when (val res = app.backendAuthRepository.updateDisplayName(newDisplayName)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Display name updated to ${res.data.displayName}."
                }
                is CloudResult.Failure -> {
                    _authErrorMessage.value = res.error.message
                }
            }
            _isAuthBusy.value = false
        }
    }

    fun setBackendMode(mode: BackendMode) {
        viewModelScope.launch {
            _authErrorMessage.value = null
            _healthCheckStatus.value = null
            app.backendConfigRepository.setMode(mode)
            _statusBanner.value = "Server mode switched to ${mode.name}."
        }
    }

    fun saveCustomBackendUrl(url: String, onResult: (UrlValidationResult) -> Unit) {
        viewModelScope.launch {
            _authErrorMessage.value = null
            _healthCheckStatus.value = null
            val result = app.backendConfigRepository.setCustomUrl(url)
            onResult(result)
            if (result is UrlValidationResult.Valid) {
                _statusBanner.value = "Custom server configured: ${result.normalizedUrl}"
            } else if (result is UrlValidationResult.Invalid) {
                _statusBanner.value = "URL error: ${result.reason}"
            }
        }
    }

    fun testBackendConnection(url: String?) {
        val targetUrl = url ?: backendConfig.value.effectiveBaseUrl
        if (targetUrl.isNullOrBlank()) {
            _healthCheckStatus.value = HealthCheckResult.Error("No server URL configured to test.")
            return
        }

        viewModelScope.launch {
            _isTestingConnection.value = true
            _healthCheckStatus.value = null
            val result = app.backendApiClient.checkHealth(targetUrl)
            _healthCheckStatus.value = result
            _isTestingConnection.value = false
        }
    }

    val permissionChecker get() = app.permissionChecker

    // ========================================================================
    // V0.4 — Advanced Partners, Approval Policies, Negotiations & Schedules
    // ========================================================================

    fun refreshPartnerSystem() {
        viewModelScope.launch {
            app.partnerSystemRepository.refreshAllPartnerData()
        }
    }

    fun dismissPartnerError() {
        app.partnerSystemRepository.clearError()
    }

    fun clearDiscoveryResult() {
        app.partnerSystemRepository.clearDiscoveryResult()
    }

    fun regenerateInviteCode() {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.fetchOrRegenerateInviteCode(regenerate = true)) {
                is CloudResult.Success -> _statusBanner.value = "Invitation code regenerated: ${res.data}"
                is CloudResult.Failure -> _statusBanner.value = res.error.message
            }
        }
    }

    fun discoverPartner(username: String? = null, inviteCode: String? = null) {
        viewModelScope.launch {
            app.partnerSystemRepository.discoverPartner(username = username, inviteCode = inviteCode)
        }
    }

    fun sendPartnerRequest(
        username: String? = null,
        inviteCode: String? = null,
        recipientUserId: String? = null
    ) {
        viewModelScope.launch {
            when (
                val res = app.partnerSystemRepository.sendPartnerRequest(
                    username = username,
                    inviteCode = inviteCode,
                    recipientUserId = recipientUserId
                )
            ) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Partner invitation sent to @${res.data.partner.username}."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun acceptPartnerRequest(partnershipId: String) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.acceptPartnerRequest(partnershipId)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Accepted partnership with @${res.data.partner.username}."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun rejectPartnerRequest(partnershipId: String) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.rejectPartnerRequest(partnershipId)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Declined invitation from @${res.data.partner.username}."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun removeOrCancelPartnership(partnershipId: String, onCompleted: (() -> Unit)? = null) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.removeOrCancelPartnership(partnershipId)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Partnership with @${res.data.partner.username} updated (${res.data.state})."
                    onCompleted?.invoke()
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun blockPartner(partnershipOrUserId: String, onCompleted: (() -> Unit)? = null) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.blockPartner(partnershipOrUserId)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Blocked @${res.data.partner.username}. All authorizations and schedules revoked."
                    onCompleted?.invoke()
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun loadPartnerProfile(partnershipOrUserId: String) {
        viewModelScope.launch {
            app.partnerSystemRepository.loadPartnerProfile(partnershipOrUserId)
        }
    }

    fun updateApprovalPolicy(
        partnershipOrUserId: String,
        requireApprovalBeforeArrest: Boolean,
        notes: String? = null
    ) {
        viewModelScope.launch {
            when (
                val res = app.partnerSystemRepository.updateApprovalPolicy(
                    partnershipOrUserId = partnershipOrUserId,
                    requireApprovalBeforeArrest = requireApprovalBeforeArrest,
                    notes = notes
                )
            ) {
                is CloudResult.Success -> {
                    val modeText = if (res.data.myApprovalPolicyForPartner.requireApprovalBeforeArrest) {
                        "Approval REQUIRED before every arrest"
                    } else {
                        "Pre-authorized starts allowed within standing limits"
                    }
                    _statusBanner.value = "Approval policy updated: $modeText."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun grantStandingPermission(
        request: CreatePartnerPermissionRequestDto,
        partnerIdForRefresh: String? = null
    ) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.grantStandingPermission(request, partnerIdForRefresh)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Standing authorization granted (max ${res.data.maximumSessionDuration} min)."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun revokePermission(permissionId: String, partnerIdForRefresh: String? = null) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.revokePermission(permissionId, partnerIdForRefresh)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Authorization revoked. Future remote sessions prevented."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun loadProposalDetails(proposalId: String) {
        viewModelScope.launch {
            app.partnerSystemRepository.loadProposalDetails(proposalId)
        }
    }

    fun submitProposal(request: CreateProposalRequestDto, onSuccess: (ProposalDto) -> Unit = {}) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.submitProposal(request)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Arrest proposal sent (Rev #${res.data.currentRevision})."
                    onSuccess(res.data)
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun counterofferProposal(
        proposalId: String,
        request: CounterofferProposalRequestDto,
        onSuccess: (ProposalDto) -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.counterofferProposal(proposalId, request)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Counteroffer submitted (Revision #${res.data.currentRevision})."
                    onSuccess(res.data)
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun acceptProposal(
        proposalId: String,
        expectedRevisionNumber: Int,
        startImmediateSession: Boolean = true
    ) {
        viewModelScope.launch {
            when (
                val res = app.partnerSystemRepository.acceptProposal(
                    proposalId = proposalId,
                    expectedRevisionNumber = expectedRevisionNumber,
                    startImmediateSession = startImmediateSession
                )
            ) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Proposal Rev #$expectedRevisionNumber approved!"
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun rejectProposal(proposalId: String, expectedRevisionNumber: Int? = null, reason: String? = null) {
        viewModelScope.launch {
            when (
                val res = app.partnerSystemRepository.rejectProposal(
                    proposalId = proposalId,
                    expectedRevisionNumber = expectedRevisionNumber,
                    reason = reason
                )
            ) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Proposal rejected."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun cancelProposal(proposalId: String, expectedRevisionNumber: Int? = null, reason: String? = null) {
        viewModelScope.launch {
            when (
                val res = app.partnerSystemRepository.cancelProposal(
                    proposalId = proposalId,
                    expectedRevisionNumber = expectedRevisionNumber,
                    reason = reason
                )
            ) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Proposal cancelled."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun cancelScheduledArrest(scheduleId: String) {
        viewModelScope.launch {
            when (val res = app.partnerSystemRepository.cancelScheduledArrest(scheduleId)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Scheduled arrest (${res.data.goal}) cancelled."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    // ========================================================================
    // V0.5 — Remote Arrest Execution, Commands, Catalog & Live Dashboard
    // ========================================================================

    fun syncCommandsAndSchedules() {
        viewModelScope.launch {
            when (val res = app.cloudCommandCoordinator.syncCommandsSchedulesAndDashboard()) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Commands and live dashboard synchronized."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun startRemotePartnerSession(
        targetDeviceId: String,
        authorizationId: String,
        durationMinutes: Int,
        goal: String,
        blockingConfiguration: BlockingConfigurationDto,
        sessionType: String = "PARTNER_ARREST",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (
                val res = app.cloudCommandCoordinator.startRemotePartnerSession(
                    targetDeviceId = targetDeviceId,
                    authorizationId = authorizationId,
                    durationMinutes = durationMinutes,
                    goal = goal,
                    blockingConfiguration = blockingConfiguration,
                    sessionType = sessionType
                )
            ) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Remote session command queued for partner device."
                    onSuccess()
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun requestRemoteEndSession(sessionId: String, reason: String? = null) {
        viewModelScope.launch {
            when (val res = app.cloudCommandCoordinator.requestRemoteEndSession(sessionId, reason)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Remote end command queued."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun requestRemoteSyncSession(sessionId: String) {
        viewModelScope.launch {
            when (val res = app.cloudCommandCoordinator.requestRemoteSyncSession(sessionId)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Sync command queued for target device."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun uploadSelectableAppCatalog() {
        viewModelScope.launch {
            if (_installedApps.value.isEmpty()) {
                loadInstalledApps()
            }
            when (val res = app.cloudCommandCoordinator.uploadSelectableAppCatalog(_installedApps.value)) {
                is CloudResult.Success -> {
                    _statusBanner.value = "Selectable app catalog shared (${res.data.apps.size} apps, ${res.data.filteredOutSystemCount} system apps filtered out)."
                }
                is CloudResult.Failure -> {
                    _statusBanner.value = res.error.message
                }
            }
        }
    }

    fun loadPartnerDeviceAppCatalog(targetDeviceId: String) {
        viewModelScope.launch {
            app.cloudCommandCoordinator.loadPartnerDeviceAppCatalog(targetDeviceId)
        }
    }

    fun dismissCoordinatorBanner() {
        app.cloudCommandCoordinator.dismissBanner()
    }
}
