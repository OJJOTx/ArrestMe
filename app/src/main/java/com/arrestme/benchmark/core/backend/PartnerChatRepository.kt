package com.arrestme.benchmark.core.backend

import com.arrestme.benchmark.data.remote.ApiErrorMapper
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.ArrestCloudApi
import com.arrestme.benchmark.data.remote.dto.ConversationDto
import com.arrestme.benchmark.data.remote.dto.CreateStructuredRequestDto
import com.arrestme.benchmark.data.remote.dto.MarkConversationReadRequestDto
import com.arrestme.benchmark.data.remote.dto.MessageDto
import com.arrestme.benchmark.data.remote.dto.RespondStructuredRequestDto
import com.arrestme.benchmark.data.remote.dto.SendTextMessageRequestDto
import com.arrestme.benchmark.data.remote.dto.StructuredRequestDto
import com.arrestme.benchmark.data.remote.dto.TemporaryGrantDto
import com.arrestme.benchmark.data.remote.dto.UserSummaryDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * V0.6 Repository for Full Chat, Embedded Structured Requests, and Live Session Overrides.
 * Fully isolated from the local Arrest Engine.
 */
class PartnerChatRepository(
    private val preferencesDataSource: BackendPreferencesDataSource,
    private val configRepository: BackendConfigRepository,
    private val authRepository: BackendAuthRepository,
    private val apiClientFactory: ArrestApiClientFactory,
    private val scope: CoroutineScope,
    private val autoRefreshOnAuth: Boolean = true
) {
    private val _conversations = MutableStateFlow<List<ConversationDto>>(emptyList())
    val conversations: StateFlow<List<ConversationDto>> = _conversations.asStateFlow()

    private val _activeConversation = MutableStateFlow<ConversationDto?>(null)
    val activeConversation: StateFlow<ConversationDto?> = _activeConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageDto>>(emptyList())
    val messages: StateFlow<List<MessageDto>> = _messages.asStateFlow()

    private val _activeTemporaryGrants = MutableStateFlow<List<TemporaryGrantDto>>(emptyList())
    val activeTemporaryGrants: StateFlow<List<TemporaryGrantDto>> = _activeTemporaryGrants.asStateFlow()

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
                            runCatching { refreshConversations() }
                        }
                    }
                    is BackendAuthState.Unauthenticated -> {
                        _conversations.value = emptyList()
                        _activeConversation.value = null
                        _messages.value = emptyList()
                        _activeTemporaryGrants.value = emptyList()
                    }
                    is BackendAuthState.Offline -> {
                        // Do not clear on initial offline transition
                    }
                }
            }
        }
    }

    private suspend fun resolveAuthenticatedApi(): CloudResult<Triple<String, AuthCredentials, ArrestCloudApi>> {
        val config = configRepository.activeConfig.first()
        val baseUrl = config.effectiveBaseUrl
        if (config.isLocalOnly || baseUrl.isNullOrBlank()) {
            return CloudResult.Failure(
                CloudError.Configuration("Chat features require a connected cloud server.")
            )
        }
        val identity = config.backendIdentity
        val creds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        if (!creds.isValid) {
            return CloudResult.Failure(
                CloudError.Unauthorized(message = "Sign in to your account to use partner chat.")
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

    suspend fun refreshConversations(): CloudResult<List<ConversationDto>> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) {
            _errorMessage.value = resolved.error.message
            return@withContext resolved
        }
        val (_, _, api) = (resolved as CloudResult.Success).data
        _isLoading.value = true
        try {
            val res = executeApiCall({ api.listConversations(limit = 50, offset = 0) }) { it.items }
            if (res is CloudResult.Success) {
                _conversations.value = res.data
            }
            res
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun openConversationForPartnership(partnershipId: String): CloudResult<ConversationDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data
            _isLoading.value = true
            try {
                val res = executeApiCall({ api.getOrCreateConversationForPartnership(partnershipId) }) { it.conversation }
                if (res is CloudResult.Success) {
                    _activeConversation.value = res.data
                    refreshMessages(res.data.id)
                }
                res
            } finally {
                _isLoading.value = false
            }
        }

    suspend fun selectConversation(conversationId: String): CloudResult<ConversationDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data
            val res = executeApiCall({ api.getConversation(conversationId) }) { it.conversation }
            if (res is CloudResult.Success) {
                _activeConversation.value = res.data
                refreshMessages(conversationId)
            }
            res
        }

    suspend fun refreshMessages(conversationId: String): CloudResult<List<MessageDto>> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data
            val res = executeApiCall({ api.listMessages(conversationId = conversationId, limit = 100) }) { it.items }
            if (res is CloudResult.Success) {
                _messages.value = res.data
            }
            res
        }

    suspend fun sendTextMessage(
        conversationId: String,
        text: String
    ): CloudResult<MessageDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, creds, api) = (resolved as CloudResult.Success).data

        val clientMsgId = "msg_client_${UUID.randomUUID()}"
        val myUserId = creds.userId ?: ""
        val myUsername = creds.username ?: "me"
        val myDisplayName = creds.displayName ?: myUsername

        // Optimistic local echo
        val optimisticMsg = MessageDto(
            id = clientMsgId,
            conversationId = conversationId,
            sender = UserSummaryDto(id = myUserId, username = myUsername, displayName = myDisplayName),
            clientMessageId = clientMsgId,
            messageType = "TEXT",
            textContent = text,
            status = "SENT",
            createdAt = System.currentTimeMillis().toString(),
            updatedAt = System.currentTimeMillis().toString()
        )
        _messages.value = _messages.value + optimisticMsg

        val res = executeApiCall({
            api.sendTextMessage(
                conversationId = conversationId,
                body = SendTextMessageRequestDto(clientMessageId = clientMsgId, text = text)
            )
        }) { it.message }

        if (res is CloudResult.Success) {
            val confirmed = res.data
            _messages.value = _messages.value.map {
                if (it.clientMessageId == clientMsgId) confirmed else it
            }
            _conversations.value = _conversations.value.map { conv ->
                if (conv.id == conversationId) conv.copy(lastMessage = confirmed) else conv
            }
        }
        res
    }

    suspend fun sendStructuredRequest(
        conversationId: String,
        requestType: String,
        sessionId: String? = null,
        targetPackage: String? = null,
        durationMinutes: Int? = null,
        parameters: JsonObject? = null,
        messageText: String? = null
    ): CloudResult<MessageDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, creds, api) = (resolved as CloudResult.Success).data

        val clientMsgId = "req_client_${UUID.randomUUID()}"
        val res = executeApiCall({
            api.createStructuredRequest(
                conversationId = conversationId,
                body = CreateStructuredRequestDto(
                    clientMessageId = clientMsgId,
                    requestType = requestType,
                    sessionId = sessionId,
                    targetPackage = targetPackage,
                    durationMinutes = durationMinutes,
                    parameters = parameters,
                    messageText = messageText
                )
            )
        }) { it.message }

        if (res is CloudResult.Success) {
            val msg = res.data
            _messages.value = _messages.value + msg
            _conversations.value = _conversations.value.map { conv ->
                if (conv.id == conversationId) conv.copy(lastMessage = msg) else conv
            }
        }
        res
    }

    suspend fun respondToStructuredRequest(
        requestId: String,
        decision: String,
        reason: String? = null
    ): CloudResult<StructuredRequestDto> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, _, api) = (resolved as CloudResult.Success).data

        val res = executeApiCall({
            api.respondToStructuredRequest(
                requestId = requestId,
                body = RespondStructuredRequestDto(decision = decision, reason = reason)
            )
        }) { it.request }

        if (res is CloudResult.Success) {
            val updatedReq = res.data
            _messages.value = _messages.value.map { msg ->
                if (msg.structuredRequest?.id == requestId) {
                    msg.copy(structuredRequest = updatedReq)
                } else msg
            }
        }
        res
    }

    suspend fun cancelStructuredRequest(requestId: String): CloudResult<StructuredRequestDto> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data

            val res = executeApiCall({ api.cancelStructuredRequest(requestId) }) { it.request }
            if (res is CloudResult.Success) {
                _activeConversation.value?.let { refreshMessages(it.id) }
            }
            res
        }

    suspend fun markConversationRead(
        conversationId: String,
        lastReadMessageId: String
    ): CloudResult<Unit> = withContext(Dispatchers.IO) {
        val resolved = resolveAuthenticatedApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (_, _, api) = (resolved as CloudResult.Success).data

        val res = executeApiCall({
            api.markConversationRead(
                conversationId = conversationId,
                body = MarkConversationReadRequestDto(lastReadMessageId = lastReadMessageId)
            )
        }) { Unit }

        if (res is CloudResult.Success) {
            refreshConversations()
        }
        res
    }

    suspend fun loadActiveTemporaryGrants(sessionId: String): CloudResult<List<TemporaryGrantDto>> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data

            val res = executeApiCall({ api.listActiveTemporaryGrants(sessionId) }) { it.items }
            if (res is CloudResult.Success) {
                _activeTemporaryGrants.value = res.data
            }
            res
        }

    suspend fun revokeTemporaryGrant(sessionId: String, grantId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            val resolved = resolveAuthenticatedApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (_, _, api) = (resolved as CloudResult.Success).data

            val res = executeApiCall({ api.revokeTemporaryGrant(sessionId, grantId) }) { Unit }
            if (res is CloudResult.Success) {
                loadActiveTemporaryGrants(sessionId)
            }
            res
        }

    fun clearError() {
        _errorMessage.value = null
    }
}
