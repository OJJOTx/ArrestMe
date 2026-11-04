package com.arrestme.benchmark.core.backend

import com.arrestme.benchmark.data.remote.ApiErrorMapper
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.ArrestCloudApi
import com.arrestme.benchmark.data.remote.dto.AcceptProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.AcceptProposalResponseDto
import com.arrestme.benchmark.data.remote.dto.ApprovalPoliciesResponseDto
import com.arrestme.benchmark.data.remote.dto.CounterofferProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.CreatePartnerPermissionRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.PartnerDiscoveryResponseDto
import com.arrestme.benchmark.data.remote.dto.PartnerPermissionDto
import com.arrestme.benchmark.data.remote.dto.PartnerProfileDto
import com.arrestme.benchmark.data.remote.dto.PartnershipDto
import com.arrestme.benchmark.data.remote.dto.PendingCountsDto
import com.arrestme.benchmark.data.remote.dto.ProposalDto
import com.arrestme.benchmark.data.remote.dto.RejectOrCancelProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.ScheduledArrestDto
import com.arrestme.benchmark.data.remote.dto.SendPartnerRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateApprovalPolicyRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V0.4 Repository for Advanced Partners, Approval Policies, Versioned Negotiations, and Scheduled Arrests.
 * Keeps all cloud operations isolated from the local Android Arrest Engine.
 */
class PartnerSystemRepository(
    private val preferencesDataSource: BackendPreferencesDataSource,
    private val configRepository: BackendConfigRepository,
    private val authRepository: BackendAuthRepository,
    private val apiClientFactory: ArrestApiClientFactory,
    private val scope: CoroutineScope,
    private val autoRefreshOnAuth: Boolean = true
) {
    private val _partners = MutableStateFlow<List<PartnershipDto>>(emptyList())
    val partners: StateFlow<List<PartnershipDto>> = _partners.asStateFlow()

    private val _incomingRequests = MutableStateFlow<List<PartnershipDto>>(emptyList())
    val incomingRequests: StateFlow<List<PartnershipDto>> = _incomingRequests.asStateFlow()

    private val _outgoingRequests = MutableStateFlow<List<PartnershipDto>>(emptyList())
    val outgoingRequests: StateFlow<List<PartnershipDto>> = _outgoingRequests.asStateFlow()

    private val _pendingCounts = MutableStateFlow(PendingCountsDto())
    val pendingCounts: StateFlow<PendingCountsDto> = _pendingCounts.asStateFlow()

    private val _myInviteCode = MutableStateFlow<String?>(null)
    val myInviteCode: StateFlow<String?> = _myInviteCode.asStateFlow()

    private val _discoveryResult = MutableStateFlow<PartnerDiscoveryResponseDto?>(null)
    val discoveryResult: StateFlow<PartnerDiscoveryResponseDto?> = _discoveryResult.asStateFlow()

    private val _selectedPartnerProfile = MutableStateFlow<PartnerProfileDto?>(null)
    val selectedPartnerProfile: StateFlow<PartnerProfileDto?> = _selectedPartnerProfile.asStateFlow()

    private val _proposals = MutableStateFlow<List<ProposalDto>>(emptyList())
    val proposals: StateFlow<List<ProposalDto>> = _proposals.asStateFlow()

    private val _selectedProposal = MutableStateFlow<ProposalDto?>(null)
    val selectedProposal: StateFlow<ProposalDto?> = _selectedProposal.asStateFlow()

    private val _scheduledArrests = MutableStateFlow<List<ScheduledArrestDto>>(emptyList())
    val scheduledArrests: StateFlow<List<ScheduledArrestDto>> = _scheduledArrests.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        scope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is BackendAuthState.Authenticated -> {
                        if (autoRefreshOnAuth) {
                            runCatching { refreshAllPartnerData() }
                        }
                    }
                    else -> {
                        clearCachedState()
                    }
                }
            }
        }
    }

    fun clearCachedState() {
        _partners.value = emptyList()
        _incomingRequests.value = emptyList()
        _outgoingRequests.value = emptyList()
        _pendingCounts.value = PendingCountsDto()
        _myInviteCode.value = null
        _discoveryResult.value = null
        _selectedPartnerProfile.value = null
        _proposals.value = emptyList()
        _selectedProposal.value = null
        _scheduledArrests.value = emptyList()
        _errorMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearDiscoveryResult() {
        _discoveryResult.value = null
    }

    private suspend fun resolveAuthenticatedApi(): CloudResult<Triple<String, AuthCredentials, ArrestCloudApi>> {
        val config = configRepository.activeConfig.first()
        val baseUrl = config.effectiveBaseUrl
        if (config.isLocalOnly || baseUrl.isNullOrBlank()) {
            return CloudResult.Failure(
                CloudError.Configuration(
                    "Partner features require a connected cloud server. Switch from Local-Only mode in Account settings."
                )
            )
        }
        val identity = config.backendIdentity
        val creds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        if (!creds.isValid) {
            return CloudResult.Failure(
                CloudError.Unauthorized(
                    message = "Sign in to your ArrestMe account to manage partners, proposals, and schedules."
                )
            )
        }
        return apiClientFactory.getOrCreateApi(baseUrl).fold(
            onSuccess = { (resolvedIdentity, api) ->
                CloudResult.Success(Triple(resolvedIdentity, creds, api))
            },
            onFailure = { err ->
                CloudResult.Failure(
                    CloudError.Configuration(err.message ?: "Invalid server URL.")
                )
            }
        )
    }

    suspend fun refreshAllPartnerData(): CloudResult<Unit> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, creds, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            // 1. Accepted partners
            val partnersResp = api.listPartners(limit = 50, offset = 0)
            val latestCreds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val partnersMapped = ApiErrorMapper.mapResponse(partnersResp, !latestCreds.isValid) { it.items }
            if (partnersMapped is CloudResult.Success) {
                _partners.value = partnersMapped.data
            } else if (partnersMapped is CloudResult.Failure) {
                _errorMessage.value = partnersMapped.error.message
                return@withContext partnersMapped
            }

            // 2. Incoming & outgoing pending requests
            val incomingResp = api.listPartnerRequests(direction = "incoming", state = "PENDING", limit = 50)
            if (incomingResp.isSuccessful) {
                _incomingRequests.value = incomingResp.body()?.items.orEmpty()
            }
            val outgoingResp = api.listPartnerRequests(direction = "outgoing", state = "PENDING", limit = 50)
            if (outgoingResp.isSuccessful) {
                _outgoingRequests.value = outgoingResp.body()?.items.orEmpty()
            }

            // 3. Invite code
            val inviteResp = api.getMyInviteCode()
            if (inviteResp.isSuccessful) {
                _myInviteCode.value = inviteResp.body()?.inviteCode
            }

            // 4. Proposals
            val proposalsResp = api.listProposals(role = "all", limit = 50, offset = 0)
            if (proposalsResp.isSuccessful) {
                val allProposals = proposalsResp.body()?.items.orEmpty()
                _proposals.value = allProposals
                val myUserId = creds.userId
                val incomingPropCount = allProposals.count {
                    (it.status == "PENDING" || it.status == "COUNTEROFFERED") && it.recipient.id == myUserId
                }
                val outgoingPropCount = allProposals.count {
                    (it.status == "PENDING" || it.status == "COUNTEROFFERED") && it.proposer.id == myUserId
                }
                _pendingCounts.value = PendingCountsDto(
                    incomingFriendRequestsCount = _incomingRequests.value.size,
                    outgoingFriendRequestsCount = _outgoingRequests.value.size,
                    incomingProposalsCount = incomingPropCount,
                    outgoingProposalsCount = outgoingPropCount
                )
            }

            // 5. Scheduled arrests
            val schedulesResp = api.listScheduledArrests(role = "all", limit = 50, offset = 0)
            if (schedulesResp.isSuccessful) {
                _scheduledArrests.value = schedulesResp.body()?.items.orEmpty()
            }

            CloudResult.Success(Unit)
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchOrRegenerateInviteCode(regenerate: Boolean = false): CloudResult<String> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (identity, _, api) = (resolved as CloudResult.Success).data

            try {
                val resp = if (regenerate) api.regenerateMyInviteCode() else api.getMyInviteCode()
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.inviteCode }
                if (mapped is CloudResult.Success) {
                    _myInviteCode.value = mapped.data
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            }
        }

    suspend fun discoverPartner(
        username: String? = null,
        inviteCode: String? = null
    ): CloudResult<PartnerDiscoveryResponseDto> = withContext(Dispatchers.IO) {
        val cleanUsername = username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val cleanInvite = inviteCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (cleanUsername == null && cleanInvite == null) {
            val err = CloudError.Validation(message = "Enter an exact username or invitation code to search.")
            _errorMessage.value = err.message
            return@withContext CloudResult.Failure(err)
        }

        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.discoverPartner(username = cleanUsername, inviteCode = cleanInvite)
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it }
            if (mapped is CloudResult.Success) {
                _discoveryResult.value = mapped.data
                _pendingCounts.value = mapped.data.pendingCounts
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun sendPartnerRequest(
        username: String? = null,
        inviteCode: String? = null,
        recipientUserId: String? = null
    ): CloudResult<PartnershipDto> = withContext(Dispatchers.IO) {
        val cleanUsername = username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val cleanInvite = inviteCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val cleanRecipient = recipientUserId?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanUsername == null && cleanInvite == null && cleanRecipient == null) {
            val err = CloudError.Validation(message = "Specify a username or invitation code to send a partner request.")
            _errorMessage.value = err.message
            return@withContext CloudResult.Failure(err)
        }

        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.sendPartnerRequest(
                SendPartnerRequestDto(
                    username = cleanUsername,
                    inviteCode = cleanInvite,
                    recipientUserId = cleanRecipient
                )
            )
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.partnership }
            if (mapped is CloudResult.Success) {
                refreshAllPartnerData()
                _discoveryResult.value = _discoveryResult.value?.copy(
                    relationshipState = mapped.data.state,
                    partnershipId = mapped.data.id
                )
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun acceptPartnerRequest(partnershipId: String): CloudResult<PartnershipDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.acceptPartnerRequest(partnershipId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.partnership }
                if (mapped is CloudResult.Success) {
                    refreshAllPartnerData()
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun rejectPartnerRequest(partnershipId: String): CloudResult<PartnershipDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.rejectPartnerRequest(partnershipId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.partnership }
                if (mapped is CloudResult.Success) {
                    refreshAllPartnerData()
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun removeOrCancelPartnership(partnershipId: String): CloudResult<PartnershipDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.removeOrCancelPartnership(partnershipId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.partnership }
                if (mapped is CloudResult.Success) {
                    if (_selectedPartnerProfile.value?.partnership?.id == partnershipId) {
                        _selectedPartnerProfile.value = null
                    }
                    refreshAllPartnerData()
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun blockPartner(partnershipOrUserId: String): CloudResult<PartnershipDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.blockPartner(partnershipOrUserId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.partnership }
                if (mapped is CloudResult.Success) {
                    _selectedPartnerProfile.value = null
                    refreshAllPartnerData()
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun loadPartnerProfile(partnershipOrUserId: String): CloudResult<PartnerProfileDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) {
                _errorMessage.value = resolved.error.message
                return@withContext resolved
            }
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.getPartnerProfile(partnershipOrUserId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.profile }
                if (mapped is CloudResult.Success) {
                    _selectedPartnerProfile.value = mapped.data
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun updateApprovalPolicy(
        partnershipOrUserId: String,
        requireApprovalBeforeArrest: Boolean,
        notes: String? = null
    ): CloudResult<ApprovalPoliciesResponseDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.updateApprovalPolicy(
                partnershipOrUserId = partnershipOrUserId,
                body = UpdateApprovalPolicyRequestDto(
                    requireApprovalBeforeArrest = requireApprovalBeforeArrest,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it }
            if (mapped is CloudResult.Success) {
                loadPartnerProfile(partnershipOrUserId)
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun grantStandingPermission(
        request: CreatePartnerPermissionRequestDto,
        partnerIdForRefresh: String? = null
    ): CloudResult<PartnerPermissionDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.createPartnerPermission(request)
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.permission }
            if (mapped is CloudResult.Success) {
                val targetId = partnerIdForRefresh ?: request.partnerUserId
                loadPartnerProfile(targetId)
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun revokePermission(
        permissionId: String,
        partnerIdForRefresh: String? = null
    ): CloudResult<PartnerPermissionDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.revokePartnerPermission(permissionId)
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.permission }
            if (mapped is CloudResult.Success) {
                if (!partnerIdForRefresh.isNullOrBlank()) {
                    loadPartnerProfile(partnerIdForRefresh)
                }
                refreshAllPartnerData()
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun loadProposals(
        role: String = "all",
        status: String? = null,
        partnerUserId: String? = null
    ): CloudResult<List<ProposalDto>> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.listProposals(role = role, status = status, partnerUserId = partnerUserId, limit = 50)
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.items }
            if (mapped is CloudResult.Success) {
                _proposals.value = mapped.data
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun loadProposalDetails(proposalId: String): CloudResult<ProposalDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) {
                _errorMessage.value = resolved.error.message
                return@withContext resolved
            }
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.getProposal(proposalId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.proposal }
                if (mapped is CloudResult.Success) {
                    _selectedProposal.value = mapped.data
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun submitProposal(request: CreateProposalRequestDto): CloudResult<ProposalDto> =
        withContext(Dispatchers.IO) {
            if (request.targetDeviceId.isBlank()) {
                val err = CloudError.Validation(message = "Please select a target device.")
                _errorMessage.value = err.message
                return@withContext CloudResult.Failure(err)
            }
            if (request.goal.trim().isEmpty()) {
                val err = CloudError.Validation(message = "Please enter a session goal.")
                _errorMessage.value = err.message
                return@withContext CloudResult.Failure(err)
            }
            if (request.durationMinutes !in 5..720) {
                val err = CloudError.Validation(message = "Duration must be between 5 and 720 minutes.")
                _errorMessage.value = err.message
                return@withContext CloudResult.Failure(err)
            }

            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) {
                _errorMessage.value = resolved.error.message
                return@withContext resolved
            }
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.createProposal(request)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.proposal }
                if (mapped is CloudResult.Success) {
                    _selectedProposal.value = mapped.data
                    refreshAllPartnerData()
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun counterofferProposal(
        proposalId: String,
        request: CounterofferProposalRequestDto
    ): CloudResult<ProposalDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.counterofferProposal(proposalId, request)
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.proposal }
            if (mapped is CloudResult.Success) {
                _selectedProposal.value = mapped.data
                refreshAllPartnerData()
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun acceptProposal(
        proposalId: String,
        expectedRevisionNumber: Int,
        startImmediateSession: Boolean = true
    ): CloudResult<AcceptProposalResponseDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.acceptProposal(
                proposalId = proposalId,
                body = AcceptProposalRequestDto(
                    expectedRevisionNumber = expectedRevisionNumber,
                    startImmediateSession = startImmediateSession
                )
            )
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it }
            if (mapped is CloudResult.Success) {
                _selectedProposal.value = mapped.data.proposal
                refreshAllPartnerData()
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun rejectProposal(
        proposalId: String,
        expectedRevisionNumber: Int? = null,
        reason: String? = null
    ): CloudResult<ProposalDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.rejectProposal(
                proposalId = proposalId,
                body = RejectOrCancelProposalRequestDto(
                    expectedRevisionNumber = expectedRevisionNumber,
                    reason = reason?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.proposal }
            if (mapped is CloudResult.Success) {
                _selectedProposal.value = mapped.data
                refreshAllPartnerData()
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun cancelProposal(
        proposalId: String,
        expectedRevisionNumber: Int? = null,
        reason: String? = null
    ): CloudResult<ProposalDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.cancelProposal(
                proposalId = proposalId,
                body = RejectOrCancelProposalRequestDto(
                    expectedRevisionNumber = expectedRevisionNumber,
                    reason = reason?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.proposal }
            if (mapped is CloudResult.Success) {
                _selectedProposal.value = mapped.data
                refreshAllPartnerData()
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun loadScheduledArrests(
        role: String = "all",
        status: String? = null
    ): CloudResult<List<ScheduledArrestDto>> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (identity, _, api) = (resolved as CloudResult.Success).data

        _isLoading.value = true
        _errorMessage.value = null
        try {
            val resp = api.listScheduledArrests(role = role, status = status, limit = 50)
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.items }
            if (mapped is CloudResult.Success) {
                _scheduledArrests.value = mapped.data
            } else if (mapped is CloudResult.Failure) {
                _errorMessage.value = mapped.error.message
            }
            mapped
        } catch (t: Throwable) {
            val err = ApiErrorMapper.mapThrowable(t)
            _errorMessage.value = err.message
            CloudResult.Failure(err)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun cancelScheduledArrest(scheduleId: String): CloudResult<ScheduledArrestDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) {
                _errorMessage.value = resolved.error.message
                return@withContext resolved
            }
            val (identity, _, api) = (resolved as CloudResult.Success).data

            _isLoading.value = true
            _errorMessage.value = null
            try {
                val resp = api.cancelScheduledArrest(scheduleId)
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(resp, !latest.isValid) { it.schedule }
                if (mapped is CloudResult.Success) {
                    refreshAllPartnerData()
                } else if (mapped is CloudResult.Failure) {
                    _errorMessage.value = mapped.error.message
                }
                mapped
            } catch (t: Throwable) {
                val err = ApiErrorMapper.mapThrowable(t)
                _errorMessage.value = err.message
                CloudResult.Failure(err)
            } finally {
                _isLoading.value = false
            }
        }
}
