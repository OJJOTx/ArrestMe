package com.arrestme.benchmark.core.backend

import com.arrestme.benchmark.data.remote.ApiErrorMapper
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.ArrestCloudApi
import com.arrestme.benchmark.data.remote.dto.AuthSessionResponseDto
import com.arrestme.benchmark.data.remote.dto.AuthenticatedUserDto
import com.arrestme.benchmark.data.remote.dto.DeviceDto
import com.arrestme.benchmark.data.remote.dto.LoginRequestDto
import com.arrestme.benchmark.data.remote.dto.LogoutRequestDto
import com.arrestme.benchmark.data.remote.dto.RegisterDeviceRequestDto
import com.arrestme.benchmark.data.remote.dto.RegisterRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateDeviceRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateProfileRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class BackendAuthRepository(
    private val preferencesDataSource: BackendPreferencesDataSource,
    private val configRepository: BackendConfigRepository,
    private val scope: CoroutineScope,
    private val apiClientFactory: ArrestApiClientFactory = ArrestApiClientFactory(preferencesDataSource),
    private val deviceMetadataProvider: DeviceMetadataProvider = AndroidDeviceMetadataProvider()
) {
    private var lastObservedIdentity: String? = null
    private val deviceSyncMutex = Mutex()

    init {
        scope.launch {
            configRepository.activeConfig
                .map { it.backendIdentity }
                .distinctUntilChanged()
                .collect { newIdentity ->
                    val old = lastObservedIdentity
                    lastObservedIdentity = newIdentity
                    if (old != null && old != newIdentity) {
                        onServerChanged(oldIdentity = old, newIdentity = newIdentity)
                    } else if (old == null) {
                        preferencesDataSource.saveActiveIdentity(newIdentity)
                    }
                }
        }
    }

    /**
     * Emits the current authentication state scoped strictly to the active backend identity.
     * - Offline mode -> [BackendAuthState.Offline]
     * - Unauthenticated on active server -> [BackendAuthState.Unauthenticated] (including optional session-expired notice)
     * - Authenticated on active server -> [BackendAuthState.Authenticated]
     */
    val authState: StateFlow<BackendAuthState> = configRepository.activeConfig
        .flatMapLatest { config ->
            if (config.isLocalOnly || config.backendIdentity == "offline") {
                flowOf(BackendAuthState.Offline)
            } else {
                val identity = config.backendIdentity
                combine(
                    preferencesDataSource.observeAuthCredentials(identity),
                    preferencesDataSource.observeSessionExpiredReason(identity)
                ) { creds, expiredReason ->
                    if (creds.isValid) {
                        BackendAuthState.Authenticated(
                            backendIdentity = identity,
                            credentials = creds
                        )
                    } else {
                        BackendAuthState.Unauthenticated(
                            backendIdentity = identity,
                            sessionExpiredReason = expiredReason
                        )
                    }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, BackendAuthState.Offline)

    private suspend fun resolveActiveBackendApi(): CloudResult<Pair<String, ArrestCloudApi>> {
        val config = configRepository.activeConfig.first()
        val baseUrl = config.effectiveBaseUrl
        if (config.isLocalOnly || baseUrl.isNullOrBlank()) {
            return CloudResult.Failure(
                CloudError.Configuration(
                    "No cloud backend configured. Select Official or Custom Server first."
                )
            )
        }
        return apiClientFactory.getOrCreateApi(baseUrl).fold(
            onSuccess = { CloudResult.Success(it) },
            onFailure = { err ->
                CloudResult.Failure(
                    CloudError.Configuration(err.message ?: "Invalid server URL.")
                )
            }
        )
    }

    /**
     * Registers a new account (`POST /api/v1/auth/register`), stores encrypted session credentials
     * isolated to the active server, and registers this Android installation (`POST /api/v1/devices`).
     */
    suspend fun register(
        username: String,
        email: String,
        password: String,
        displayName: String? = null
    ): CloudResult<AuthCredentials> = withContext(Dispatchers.IO) {
        ClientInputValidator.validateUsername(username)?.let { msg ->
            return@withContext CloudResult.Failure(CloudError.Validation(message = msg))
        }
        ClientInputValidator.validateEmail(email)?.let { msg ->
            return@withContext CloudResult.Failure(CloudError.Validation(message = msg))
        }
        ClientInputValidator.validateRegistrationPassword(password)?.let { msg ->
            return@withContext CloudResult.Failure(CloudError.Validation(message = msg))
        }
        ClientInputValidator.validateDisplayName(displayName)?.let { msg ->
            return@withContext CloudResult.Failure(CloudError.Validation(message = msg))
        }

        val resolved = resolveActiveBackendApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (identity, api) = (resolved as CloudResult.Success).data

        try {
            val response = api.register(
                RegisterRequestDto(
                    username = username.trim().lowercase(),
                    email = email.trim().lowercase(),
                    password = password,
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            val mapped = ApiErrorMapper.mapResponse(response) { it }
            if (mapped is CloudResult.Failure) return@withContext mapped

            val sessionDto = (mapped as CloudResult.Success).data
            val savedCreds = persistAuthSession(identity, sessionDto, priorDevice = null)

            // Automatically register Android installation after registration
            val withDevice = registerOrRefreshDeviceInternal(identity, api, savedCreds)
                .getOrNull() ?: savedCreds

            CloudResult.Success(withDevice)
        } catch (t: Throwable) {
            CloudResult.Failure(ApiErrorMapper.mapThrowable(t))
        }
    }

    /**
     * Authenticates an existing account (`POST /api/v1/auth/login`), persists encrypted tokens,
     * and registers/refreshes this Android device installation (`POST /api/v1/devices`).
     */
    suspend fun login(
        email: String,
        password: String
    ): CloudResult<AuthCredentials> = withContext(Dispatchers.IO) {
        ClientInputValidator.validateEmail(email)?.let { msg ->
            return@withContext CloudResult.Failure(CloudError.Validation(message = msg))
        }
        ClientInputValidator.validateLoginPassword(password)?.let { msg ->
            return@withContext CloudResult.Failure(CloudError.Validation(message = msg))
        }

        val resolved = resolveActiveBackendApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (identity, api) = (resolved as CloudResult.Success).data

        val existingCreds = preferencesDataSource.readAuthCredentialsSnapshot(identity)

        try {
            val response = api.login(
                LoginRequestDto(
                    email = email.trim().lowercase(),
                    password = password,
                    deviceId = existingCreds.registeredDevice?.id
                )
            )
            val mapped = ApiErrorMapper.mapResponse(response) { it }
            if (mapped is CloudResult.Failure) return@withContext mapped

            val sessionDto = (mapped as CloudResult.Success).data
            val savedCreds = persistAuthSession(
                identity = identity,
                session = sessionDto,
                priorDevice = existingCreds.registeredDevice
            )

            // Register or update current Android installation for this account
            val withDevice = registerOrRefreshDeviceInternal(identity, api, savedCreds)
                .getOrNull() ?: savedCreds

            CloudResult.Success(withDevice)
        } catch (t: Throwable) {
            CloudResult.Failure(ApiErrorMapper.mapThrowable(t))
        }
    }

    /**
     * Logs out of the active server:
     * 1. Deregisters/revokes the registered device (`DELETE /api/v1/devices/:id`) while access token is active.
     * 2. Revokes the refresh token family (`POST /api/v1/auth/logout`).
     * 3. Always clears local encrypted tokens & cached profile for this server even if offline.
     */
    suspend fun logout(deregisterDevice: Boolean = true): CloudResult<Unit> = withContext(Dispatchers.IO) {
        val config = configRepository.activeConfig.first()
        val identity = config.backendIdentity
        if (identity == "offline" || config.effectiveBaseUrl.isNullOrBlank()) {
            return@withContext CloudResult.Success(Unit)
        }

        val currentCreds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        val apiPair = apiClientFactory.getOrCreateApi(config.effectiveBaseUrl).getOrNull()

        if (apiPair != null) {
            val (_, api) = apiPair
            if (deregisterDevice) {
                val deviceId = currentCreds.registeredDevice?.id
                if (!deviceId.isNullOrBlank() && !currentCreds.token.isNullOrBlank()) {
                    runCatching { api.revokeDevice(deviceId) }
                }
            }
            val refresh = currentCreds.refreshToken
            if (!refresh.isNullOrBlank() && refresh.length >= 20) {
                runCatching { api.logout(LogoutRequestDto(refreshToken = refresh)) }
            }
        }

        preferencesDataSource.clearAuthCredentials(identity = identity, expiredReason = null)
        CloudResult.Success(Unit)
    }

    /**
     * Fetches the latest user profile (`GET /api/v1/users/me`) and synchronizes current device registration
     * metadata, automatically rotating expired access tokens via OkHttp Authenticator/Interceptor.
     */
    suspend fun syncAccountAndDevice(): CloudResult<AuthCredentials> = withContext(Dispatchers.IO) {
        val resolved = resolveActiveBackendApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (identity, api) = (resolved as CloudResult.Success).data

        val beforeCreds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        if (!beforeCreds.isValid) {
            return@withContext CloudResult.Failure(
                CloudError.Unauthorized(message = "Not signed in to $identity.")
            )
        }

        try {
            val meResponse = api.getMe()
            val afterAttemptCreds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val wasCleared = !afterAttemptCreds.isValid

            val meResult = ApiErrorMapper.mapResponse(
                response = meResponse,
                sessionWasClearedOnAuthFailure = wasCleared
            ) { it.user }

            if (meResult is CloudResult.Failure) {
                return@withContext meResult
            }

            val userDto = (meResult as CloudResult.Success).data
            val now = System.currentTimeMillis()
            val latestCreds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val merged = latestCreds.copy(
                userId = userDto.id,
                username = userDto.username,
                email = userDto.email,
                displayName = userDto.displayName,
                avatarUrl = userDto.avatarUrl,
                accountStatus = userDto.accountStatus,
                lastSyncAtMs = now,
                updatedAtMs = now
            )
            preferencesDataSource.saveAuthCredentials(identity, merged)

            val syncedWithDevice = syncOrRegisterDeviceForAuthenticatedUser(identity, api, merged)
            CloudResult.Success(syncedWithDevice.getOrNull() ?: merged)
        } catch (t: Throwable) {
            CloudResult.Failure(ApiErrorMapper.mapThrowable(t))
        }
    }

    /**
     * Updates the authenticated user's display name (`PATCH /api/v1/users/me`).
     */
    suspend fun updateDisplayName(newDisplayName: String): CloudResult<AuthCredentials> =
        withContext(Dispatchers.IO) {
            val trimmed = newDisplayName.trim()
            if (trimmed.isEmpty() || trimmed.length > 80) {
                return@withContext CloudResult.Failure(
                    CloudError.Validation(message = "Display name must be between 1 and 80 characters.")
                )
            }

            val resolved = resolveActiveBackendApi()
            if (resolved is CloudResult.Failure) return@withContext resolved
            val (identity, api) = (resolved as CloudResult.Success).data

            try {
                val resp = api.updateMe(UpdateProfileRequestDto(displayName = trimmed))
                val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                val mapped = ApiErrorMapper.mapResponse(
                    response = resp,
                    sessionWasClearedOnAuthFailure = !latest.isValid
                ) { it.user }
                if (mapped is CloudResult.Failure) return@withContext mapped

                val userDto = (mapped as CloudResult.Success).data
                val now = System.currentTimeMillis()
                val updatedCreds = latest.copy(
                    displayName = userDto.displayName,
                    avatarUrl = userDto.avatarUrl,
                    accountStatus = userDto.accountStatus,
                    lastSyncAtMs = now,
                    updatedAtMs = now
                )
                preferencesDataSource.saveAuthCredentials(identity, updatedCreds)
                CloudResult.Success(updatedCreds)
            } catch (t: Throwable) {
                CloudResult.Failure(ApiErrorMapper.mapThrowable(t))
            }
        }

    /**
     * Explicitly registers or updates the current Android installation (`POST /api/v1/devices` or
     * `PATCH /api/v1/devices/:id`), preventing duplicate device records via persistent installationId.
     */
    suspend fun ensureDeviceRegistered(): CloudResult<RegisteredDeviceInfo> = withContext(Dispatchers.IO) {
        val resolved = resolveActiveBackendApi()
        if (resolved is CloudResult.Failure) return@withContext resolved
        val (identity, api) = (resolved as CloudResult.Success).data

        val creds = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        if (!creds.isValid) {
            return@withContext CloudResult.Failure(
                CloudError.Unauthorized(message = "Sign in required before registering this device.")
            )
        }

        val updated = syncOrRegisterDeviceForAuthenticatedUser(identity, api, creds)
        when (updated) {
            is CloudResult.Success -> {
                val dev = updated.data.registeredDevice
                if (dev != null) {
                    CloudResult.Success(dev)
                } else {
                    CloudResult.Failure(
                        CloudError.Server(httpStatus = 500, message = "Device registration metadata missing.")
                    )
                }
            }
            is CloudResult.Failure -> updated
        }
    }

    private suspend fun syncOrRegisterDeviceForAuthenticatedUser(
        identity: String,
        api: ArrestCloudApi,
        creds: AuthCredentials
    ): CloudResult<AuthCredentials> = deviceSyncMutex.withLock {
        val installationId = preferencesDataSource.getOrCreateInstallationId()
        val meta = deviceMetadataProvider.currentMetadata()
        val existingDevice = creds.registeredDevice

        // If already registered with ACTIVE status and same installationId, send lightweight PATCH metadata update
        if (existingDevice != null &&
            existingDevice.installationId == installationId &&
            existingDevice.deviceStatus == "ACTIVE"
        ) {
            try {
                val patchResp = api.updateDevice(
                    deviceId = existingDevice.id,
                    body = UpdateDeviceRequestDto(
                        deviceName = meta.deviceName,
                        manufacturer = meta.manufacturer,
                        model = meta.model,
                        appVersion = meta.appVersion,
                        androidVersion = meta.androidVersion,
                        sdkInt = meta.sdkInt,
                        deviceStatus = "ACTIVE"
                    )
                )
                if (patchResp.isSuccessful) {
                    val dto = patchResp.body()?.device
                    if (dto != null) {
                        return@withLock CloudResult.Success(
                            saveDeviceOntoCredentials(identity, dto, installationId)
                        )
                    }
                }
                // If 404 or 409 (e.g. device was revoked remotely), fall through to POST /api/v1/devices to re-bind cleanly!
                if (patchResp.code() != 404 && patchResp.code() != 409) {
                    val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
                    return@withLock ApiErrorMapper.mapResponse(
                        response = patchResp,
                        sessionWasClearedOnAuthFailure = !latest.isValid
                    ) { latest }
                }
            } catch (t: Throwable) {
                return@withLock CloudResult.Failure(ApiErrorMapper.mapThrowable(t))
            }
        }

        registerOrRefreshDeviceInternalUnlocked(identity, api, installationId, meta)
    }

    private suspend fun registerOrRefreshDeviceInternal(
        identity: String,
        api: ArrestCloudApi,
        fallbackCreds: AuthCredentials
    ): CloudResult<AuthCredentials> = deviceSyncMutex.withLock {
        val installationId = preferencesDataSource.getOrCreateInstallationId()
        val meta = deviceMetadataProvider.currentMetadata()
        registerOrRefreshDeviceInternalUnlocked(identity, api, installationId, meta).let { res ->
            if (res is CloudResult.Failure && fallbackCreds.isValid) res else res
        }
    }

    private suspend fun registerOrRefreshDeviceInternalUnlocked(
        identity: String,
        api: ArrestCloudApi,
        installationId: String,
        meta: LocalDeviceMetadata
    ): CloudResult<AuthCredentials> {
        return try {
            val response = api.registerDevice(
                RegisterDeviceRequestDto(
                    installationId = installationId,
                    deviceName = meta.deviceName,
                    platform = "ANDROID",
                    manufacturer = meta.manufacturer,
                    model = meta.model,
                    appVersion = meta.appVersion,
                    androidVersion = meta.androidVersion,
                    sdkInt = meta.sdkInt
                )
            )
            val latest = preferencesDataSource.readAuthCredentialsSnapshot(identity)
            val mapped = ApiErrorMapper.mapResponse(
                response = response,
                sessionWasClearedOnAuthFailure = !latest.isValid
            ) { it.device }
            if (mapped is CloudResult.Failure) return mapped

            val deviceDto = (mapped as CloudResult.Success).data
            CloudResult.Success(saveDeviceOntoCredentials(identity, deviceDto, installationId))
        } catch (t: Throwable) {
            CloudResult.Failure(ApiErrorMapper.mapThrowable(t))
        }
    }

    private suspend fun persistAuthSession(
        identity: String,
        session: AuthSessionResponseDto,
        priorDevice: RegisteredDeviceInfo?
    ): AuthCredentials {
        val user: AuthenticatedUserDto = session.user
        val tokens = session.tokens
        val now = System.currentTimeMillis()
        val keptDevice = priorDevice?.takeIf { true }
        val creds = AuthCredentials(
            token = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(tokens.accessTokenExpiresAt),
            refreshTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(tokens.refreshTokenExpiresAt),
            userId = user.id,
            username = user.username,
            email = user.email,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            accountStatus = user.accountStatus,
            registeredDevice = keptDevice,
            lastSyncAtMs = now,
            updatedAtMs = now
        )
        preferencesDataSource.saveAuthCredentials(identity.trim().lowercase(), creds)
        return creds
    }

    private suspend fun saveDeviceOntoCredentials(
        identity: String,
        deviceDto: DeviceDto,
        installationId: String
    ): AuthCredentials {
        val now = System.currentTimeMillis()
        val current = preferencesDataSource.readAuthCredentialsSnapshot(identity)
        val deviceInfo = RegisteredDeviceInfo(
            id = deviceDto.id,
            installationId = deviceDto.installationId ?: installationId,
            deviceName = deviceDto.deviceName,
            platform = deviceDto.platform,
            manufacturer = deviceDto.manufacturer,
            model = deviceDto.model,
            appVersion = deviceDto.appVersion,
            androidVersion = deviceDto.androidVersion,
            sdkInt = deviceDto.sdkInt,
            deviceStatus = deviceDto.deviceStatus,
            lastSeenAt = deviceDto.lastSeenAt,
            registeredAtMs = now
        )
        val updated = current.copy(
            registeredDevice = deviceInfo,
            lastSyncAtMs = now,
            updatedAtMs = now
        )
        preferencesDataSource.saveAuthCredentials(identity.trim().lowercase(), updated)
        return updated
    }

    /**
     * Store credentials strictly bound to a specific backend identity.
     */
    suspend fun saveCredentialsForBackend(identity: String, credentials: AuthCredentials) {
        val sanitizedIdentity = identity.trim().lowercase()
        preferencesDataSource.saveAuthCredentials(sanitizedIdentity, credentials)
    }

    /**
     * Clear credentials for the currently active backend without sending network calls.
     */
    suspend fun clearActiveCredentials() {
        val currentIdentity = configRepository.activeConfig.first().backendIdentity
        if (currentIdentity != "offline") {
            preferencesDataSource.clearAuthCredentials(currentIdentity)
        }
    }

    /**
     * Clear credentials for an explicit backend identity.
     */
    suspend fun clearCredentialsForIdentity(identity: String) {
        preferencesDataSource.clearAuthCredentials(identity.trim().lowercase())
    }

    suspend fun getInstallationId(): String = preferencesDataSource.getOrCreateInstallationId()

    /**
     * Updates the active identity pointer when switching servers.
     * Sessions remain strictly isolated per normalized backend identity so Server A tokens
     * are never sent to Server B, and toggling Offline Mode never destroys cloud sessions.
     */
    suspend fun onServerChanged(oldIdentity: String, newIdentity: String) {
        preferencesDataSource.saveActiveIdentity(newIdentity)
    }
}
