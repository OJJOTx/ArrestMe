package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.arrestme.benchmark.data.remote.AlwaysOnlineConnectivityChecker
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.NetworkConnectivityChecker
import com.arrestme.benchmark.data.remote.NetworkTimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CloudIntegrationTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var ioScope: CoroutineScope
    private lateinit var server: MockWebServer

    private val fakeMetadataProvider = object : DeviceMetadataProvider {
        override fun currentMetadata(): LocalDeviceMetadata = LocalDeviceMetadata(
            deviceName = "Google Pixel 9",
            manufacturer = "Google",
            model = "Pixel 9",
            appVersion = "0.3.0",
            androidVersion = "15",
            sdkInt = 35
        )
    }

    @Before
    fun setUp() {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        server.shutdown()
    }

    private fun buildHarness(
        baseUrl: String = "http://127.0.0.1:${server.port}/",
        connectivityChecker: NetworkConnectivityChecker = AlwaysOnlineConnectivityChecker(),
        timeoutConfig: NetworkTimeoutConfig = NetworkTimeoutConfig(
            connectTimeoutMs = 2_000L,
            readTimeoutMs = 2_000L,
            writeTimeoutMs = 2_000L,
            callTimeoutMs = 4_000L,
            maxIdempotentRetries = 2,
            retryBackoffMs = 15L
        )
    ): Triple<BackendPreferencesDataSource, BackendConfigRepository, BackendAuthRepository> {
        val file = tmpFolder.newFile("cloud_test_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { file }
        )
        val dataSource = BackendPreferencesDataSource(dataStore, InMemoryAesGcmSecretCipher())
        val configRepo = BackendConfigRepository(dataSource, buildConfigUrl = baseUrl)
        val factory = ArrestApiClientFactory(dataSource, connectivityChecker, timeoutConfig)
        val authRepo = BackendAuthRepository(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            scope = ioScope,
            apiClientFactory = factory,
            deviceMetadataProvider = fakeMetadataProvider
        )
        return Triple(dataSource, configRepo, authRepo)
    }

    private fun futureIso(secondsAhead: Long = 900L): String =
        Instant.now().plusSeconds(secondsAhead).toString()

    private fun pastIso(secondsAgo: Long = 120L): String =
        Instant.now().minusSeconds(secondsAgo).toString()

    @Test
    fun `successful registration and login persist profile and register installation device`() = runBlocking {
        val (_, _, authRepo) = buildHarness()

        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "user": {
                        "id": "11111111-1111-4111-8111-111111111111",
                        "username": "omar_focus",
                        "email": "omar@example.com",
                        "displayName": "Omar",
                        "avatarUrl": null,
                        "accountStatus": "ACTIVE",
                        "createdAt": "2026-11-04T19:00:00.000Z",
                        "updatedAt": "2026-11-04T19:00:00.000Z",
                        "lastLoginAt": "2026-11-04T19:00:00.000Z"
                      },
                      "tokens": {
                        "accessToken": "jwt_access_initial_123",
                        "accessTokenExpiresAt": "${futureIso(900)}",
                        "expiresInSeconds": 900,
                        "refreshToken": "amrt_refresh_initial_1234567890",
                        "refreshTokenExpiresAt": "${futureIso(86400)}",
                        "tokenType": "Bearer"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "device": {
                        "id": "22222222-2222-4222-8222-222222222222",
                        "userId": "11111111-1111-4111-8111-111111111111",
                        "installationId": "inst-uuid-12345678",
                        "deviceName": "Google Pixel 9",
                        "platform": "ANDROID",
                        "manufacturer": "Google",
                        "model": "Pixel 9",
                        "appVersion": "0.3.0",
                        "androidVersion": "15",
                        "sdkInt": 35,
                        "deviceStatus": "ACTIVE",
                        "lastSeenAt": "2026-11-04T19:00:01.000Z"
                      }
                    }
                    """.trimIndent()
                )
        )

        val regRes = authRepo.register(
            username = "Omar_Focus",
            email = "Omar@Example.com",
            password = "StrongPassword#2026",
            displayName = "Omar"
        )
        assertTrue("Expected Success, got: $regRes", regRes is CloudResult.Success)
        val creds = (regRes as CloudResult.Success).data
        assertEquals("omar_focus", creds.username)
        assertEquals("Omar", creds.displayName)
        assertEquals("jwt_access_initial_123", creds.token)
        assertNotNull(creds.registeredDevice)
        assertEquals("22222222-2222-4222-8222-222222222222", creds.registeredDevice?.id)

        val req1 = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("Expected register request", req1)
        assertEquals("/api/v1/auth/register", req1!!.path)
        val req2 = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("Expected devices request", req2)
        assertEquals("/api/v1/devices", req2!!.path)
        assertEquals("Bearer jwt_access_initial_123", req2.getHeader("Authorization"))
        val installId1 = authRepo.getInstallationId()
        assertTrue(req2.body.readUtf8().contains(installId1))
    }

    @Test
    fun `invalid credentials return structured 401 error without authenticating`() = runBlocking {
        val (_, _, authRepo) = buildHarness()

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid email or password."}}""")
        )

        val res = authRepo.login("wrong@example.com", "BadPassword!123")
        assertTrue(res is CloudResult.Failure)
        val err = (res as CloudResult.Failure).error
        assertTrue(err is CloudError.Unauthorized)
        assertEquals("INVALID_CREDENTIALS", err.code)
        assertEquals("Invalid email or password.", err.message)

        val state = authRepo.authState.first { it is BackendAuthState.Unauthenticated }
        assertFalse(state.isAuthenticated)
    }

    @Test
    fun `expired access token triggers automatic single-flight refresh and retries request`() = runBlocking {
        val (dataSource, configRepo, authRepo) = buildHarness()
        val identity = configRepo.activeConfig.first().backendIdentity
        val installId = authRepo.getInstallationId()

        dataSource.saveAuthCredentials(
            identity,
            AuthCredentials(
                token = "expired_access_jwt_001",
                refreshToken = "amrt_valid_refresh_token_0001",
                accessTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(pastIso(300)),
                refreshTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(86400)),
                userId = "11111111-1111-4111-8111-111111111111",
                username = "nour",
                email = "nour@example.com",
                displayName = "Nour",
                registeredDevice = RegisteredDeviceInfo(
                    id = "33333333-3333-4333-8333-333333333333",
                    installationId = installId,
                    deviceName = "Google Pixel 9",
                    appVersion = "0.3.0",
                    androidVersion = "15",
                    sdkInt = 35,
                    deviceStatus = "ACTIVE"
                )
            )
        )

        val refreshCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/api/v1/auth/refresh" -> {
                        val callNum = refreshCount.incrementAndGet()
                        if (callNum > 1) {
                            MockResponse().setResponseCode(401).setBody(
                                """{"error":{"code":"REFRESH_TOKEN_REUSED","message":"Reused refresh token"}}"""
                            )
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    {
                                      "tokens": {
                                        "accessToken": "rotated_access_jwt_002",
                                        "accessTokenExpiresAt": "${futureIso(900)}",
                                        "expiresInSeconds": 900,
                                        "refreshToken": "amrt_rotated_refresh_token_0002",
                                        "refreshTokenExpiresAt": "${futureIso(86400)}",
                                        "tokenType": "Bearer"
                                      }
                                    }
                                    """.trimIndent()
                                )
                        }
                    }
                    "/api/v1/users/me" -> {
                        if (request.getHeader("Authorization") == "Bearer rotated_access_jwt_002") {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    {
                                      "user": {
                                        "id": "11111111-1111-4111-8111-111111111111",
                                        "username": "nour",
                                        "email": "nour@example.com",
                                        "displayName": "Nour Synced",
                                        "avatarUrl": null,
                                        "accountStatus": "ACTIVE",
                                        "createdAt": "2026-11-04T19:00:00.000Z",
                                        "updatedAt": "2026-11-04T19:05:00.000Z"
                                      }
                                    }
                                    """.trimIndent()
                                )
                        } else {
                            MockResponse()
                                .setResponseCode(401)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Expired token"}}""")
                        }
                    }
                    "/api/v1/devices/33333333-3333-4333-8333-333333333333" -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "device": {
                                    "id": "33333333-3333-4333-8333-333333333333",
                                    "userId": "11111111-1111-4111-8111-111111111111",
                                    "installationId": "$installId",
                                    "deviceName": "Google Pixel 9",
                                    "platform": "ANDROID",
                                    "appVersion": "0.3.0",
                                    "androidVersion": "15",
                                    "sdkInt": 35,
                                    "deviceStatus": "ACTIVE"
                                  }
                                }
                                """.trimIndent()
                            )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val results = listOf(
            async(Dispatchers.IO) { authRepo.syncAccountAndDevice() },
            async(Dispatchers.IO) { authRepo.syncAccountAndDevice() }
        ).awaitAll()

        assertTrue(results.all { it is CloudResult.Success })
        assertEquals("Refresh endpoint must be called exactly once", 1, refreshCount.get())

        val storedAfter = dataSource.readAuthCredentialsSnapshot(identity)
        assertEquals("rotated_access_jwt_002", storedAfter.token)
        assertEquals("amrt_rotated_refresh_token_0002", storedAfter.refreshToken)
        assertEquals("Nour Synced", storedAfter.displayName)
        assertTrue(storedAfter.lastSyncAtMs > 0L)
    }

    @Test
    fun `expired refresh token clears session cleanly and exposes sessionExpiredReason`() = runBlocking {
        val (dataSource, configRepo, authRepo) = buildHarness()
        val identity = configRepo.activeConfig.first().backendIdentity

        dataSource.saveAuthCredentials(
            identity,
            AuthCredentials(
                token = "stale_access_token",
                refreshToken = "amrt_expired_refresh_token_9999",
                accessTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(600)),
                refreshTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(3600)),
                username = "karim"
            )
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Access token expired"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"REFRESH_TOKEN_EXPIRED","message":"Refresh token has expired."}}""")
        )

        val syncRes = authRepo.syncAccountAndDevice()
        assertTrue(syncRes is CloudResult.Failure)
        val err = (syncRes as CloudResult.Failure).error as CloudError.Unauthorized
        assertTrue(err.sessionExpired)

        val state = authRepo.authState.first { it is BackendAuthState.Unauthenticated } as BackendAuthState.Unauthenticated
        assertNotNull(state.sessionExpiredReason)
        assertTrue(state.sessionExpiredReason!!.contains("Session expired"))
    }

    @Test
    fun `logout deregisters device before revoking refresh token and clears local credentials`() = runBlocking {
        val (dataSource, configRepo, authRepo) = buildHarness()
        val identity = configRepo.activeConfig.first().backendIdentity
        val installId = authRepo.getInstallationId()

        dataSource.saveAuthCredentials(
            identity,
            AuthCredentials(
                token = "active_access_token_xyz",
                refreshToken = "amrt_active_refresh_token_xyz_1234",
                accessTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(900)),
                refreshTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(86400)),
                username = "sara",
                registeredDevice = RegisteredDeviceInfo(
                    id = "44444444-4444-4444-8444-444444444444",
                    installationId = installId,
                    deviceName = "Google Pixel 9",
                    appVersion = "0.3.0",
                    androidVersion = "15",
                    deviceStatus = "ACTIVE"
                )
            )
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "device": {
                        "id": "44444444-4444-4444-8444-444444444444",
                        "userId": "11111111-1111-4111-8111-111111111111",
                        "deviceName": "Google Pixel 9",
                        "platform": "ANDROID",
                        "appVersion": "0.3.0",
                        "androidVersion": "15",
                        "deviceStatus": "REVOKED"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"logged_out"}""")
        )

        val logoutRes = authRepo.logout(deregisterDevice = true)
        assertTrue(logoutRes is CloudResult.Success)

        val deleteReq = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("Expected delete device request", deleteReq)
        assertEquals("DELETE", deleteReq!!.method)
        assertEquals("/api/v1/devices/44444444-4444-4444-8444-444444444444", deleteReq.path)
        assertEquals("Bearer active_access_token_xyz", deleteReq.getHeader("Authorization"))

        val logoutReq = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("Expected logout request", logoutReq)
        assertEquals("POST", logoutReq!!.method)
        assertEquals("/api/v1/auth/logout", logoutReq.path)
        assertTrue(logoutReq.body.readUtf8().contains("amrt_active_refresh_token_xyz_1234"))

        val postLogoutCreds = dataSource.readAuthCredentialsSnapshot(identity)
        assertFalse(postLogoutCreds.isValid)
    }

    @Test
    fun `idempotent GET retries transient 503 while non-idempotent POST login does not retry`() = runBlocking {
        val (dataSource, configRepo, authRepo) = buildHarness()
        val identity = configRepo.activeConfig.first().backendIdentity
        val installId = authRepo.getInstallationId()

        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Temporarily busy"}}""")
        )
        val loginRes = authRepo.login("user@example.com", "ValidPassword!123")
        assertTrue(loginRes is CloudResult.Failure)
        assertEquals("POST login must not be retried", 1, server.requestCount)
        val loginReq = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("Expected login request", loginReq)

        dataSource.saveAuthCredentials(
            identity,
            AuthCredentials(
                token = "valid_token_retry_test",
                refreshToken = "amrt_valid_refresh_retry_test_123",
                accessTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(900)),
                refreshTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(futureIso(86400)),
                username = "retry_user",
                registeredDevice = RegisteredDeviceInfo(
                    id = "55555555-5555-4555-8555-555555555555",
                    installationId = installId,
                    deviceName = "Google Pixel 9",
                    appVersion = "0.3.0",
                    androidVersion = "15",
                    deviceStatus = "ACTIVE"
                )
            )
        )

        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "user": {
                        "id": "11111111-1111-4111-8111-111111111111",
                        "username": "retry_user",
                        "email": "retry@example.com",
                        "displayName": "Retry User",
                        "accountStatus": "ACTIVE",
                        "createdAt": "2026-11-04T19:00:00.000Z",
                        "updatedAt": "2026-11-04T19:00:00.000Z"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "device": {
                        "id": "55555555-5555-4555-8555-555555555555",
                        "userId": "11111111-1111-4111-8111-111111111111",
                        "installationId": "$installId",
                        "deviceName": "Google Pixel 9",
                        "platform": "ANDROID",
                        "appVersion": "0.3.0",
                        "androidVersion": "15",
                        "deviceStatus": "ACTIVE"
                      }
                    }
                    """.trimIndent()
                )
        )

        val syncRes = authRepo.syncAccountAndDevice()
        assertTrue(syncRes is CloudResult.Success)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `abrupt network disconnect maps cleanly to CloudError NetworkFailure`() = runBlocking {
        val (_, _, authRepo) = buildHarness()

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val res = authRepo.login("user@example.com", "ValidPassword!123")
        assertTrue(res is CloudResult.Failure)
        val err = (res as CloudResult.Failure).error
        assertTrue("Expected NetworkFailure, got $err", err is CloudError.NetworkFailure)
    }
}
