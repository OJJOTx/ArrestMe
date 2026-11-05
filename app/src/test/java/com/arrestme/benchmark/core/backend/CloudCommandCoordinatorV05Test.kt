package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.arrestme.benchmark.core.blocking.InstalledAppInfo
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionConfig
import com.arrestme.benchmark.core.session.SessionLifecycleObserver
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType
import com.arrestme.benchmark.core.session.StartSessionResult
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import com.arrestme.benchmark.data.remote.AlwaysOnlineConnectivityChecker
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.NetworkTimeoutConfig
import com.arrestme.benchmark.data.remote.dto.BlockingConfigurationDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class CloudCommandCoordinatorV05Test {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var ioScope: CoroutineScope
    private lateinit var server: MockWebServer

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

    private class FakeLocalSessionEngineBridge : LocalSessionEngineBridge {
        private val _activeSession = MutableStateFlow<ActiveSessionSnapshot?>(null)
        override val activeSession: StateFlow<ActiveSessionSnapshot?> = _activeSession.asStateFlow()

        val cloudToLocalMap = mutableMapOf<String, String>()
        val localToCloudMap = mutableMapOf<String, String>()
        var lastCommandSeq: Long = 0L
        var cachedSchedulesJson: String? = null

        var backedUpRules: List<AppRuleEntity>? = null
        val currentAppRules = mutableListOf<AppRuleEntity>(
            AppRuleEntity("com.owner.original.app", "Original Self-Arrest App", AppRuleType.BLOCKED.name, 1000L)
        )
        val transitions = mutableListOf<SessionTransitionEntity>()

        override fun registerObserver(observer: SessionLifecycleObserver) {
            // No-op in fake
        }

        override suspend fun startSession(config: SessionConfig): StartSessionResult {
            val localId = "local_sess_${System.nanoTime()}"
            val snapshot = ActiveSessionSnapshot(
                sessionId = localId,
                sessionType = config.sessionType,
                state = SessionState.ACTIVE,
                goal = config.goal,
                allowedCategories = config.allowedCategories,
                ruleMode = config.ruleMode,
                overlayStatusBar = config.overlayStatusBar,
                disableEmergencyExit = config.disableEmergencyExit,
                configuredDurationMs = config.durationMs,
                startTimestampMs = System.currentTimeMillis(),
                endTimestampMs = System.currentTimeMillis() + config.durationMs,
                pausedAtTimestampMs = null,
                accumulatedPausedMs = 0L,
                startElapsedRealtimeMs = 1000L,
                bootCountAtStart = 1,
                lastUpdatedWallMs = System.currentTimeMillis(),
                cloudSessionId = config.cloudSessionId,
                authorizationId = config.authorizationId,
                controllingPartnerUserId = config.controllingPartnerUserId,
                controllingPartnerUsername = config.controllingPartnerUsername
            )
            _activeSession.value = snapshot
            transitions.add(
                SessionTransitionEntity(
                    id = 1L,
                    sessionId = localId,
                    fromState = "PREPARING",
                    toState = "ACTIVE",
                    timestampMs = System.currentTimeMillis(),
                    reason = "Started via fake engine"
                )
            )
            return StartSessionResult.Started(snapshot)
        }

        override suspend fun forceCompleteRemoteSession(reason: String): Boolean {
            val curr = _activeSession.value ?: return false
            _activeSession.value = curr.copy(state = SessionState.COMPLETED)
            restoreBackedUpLocalRulesIfPresent()
            return true
        }

        override suspend fun applyTemporaryPartnerRulesWithBackup(
            blockedPackages: List<String>?,
            allowedStudyPackages: List<String>?
        ) {
            if (backedUpRules == null) {
                backedUpRules = ArrayList(currentAppRules)
            }
            currentAppRules.clear()
            blockedPackages?.forEach {
                currentAppRules.add(AppRuleEntity(it, it, AppRuleType.BLOCKED.name, System.currentTimeMillis()))
            }
            allowedStudyPackages?.forEach {
                currentAppRules.add(AppRuleEntity(it, it, AppRuleType.ALLOWED_STUDY.name, System.currentTimeMillis()))
            }
        }

        override suspend fun restoreBackedUpLocalRulesIfPresent() {
            backedUpRules?.let {
                currentAppRules.clear()
                currentAppRules.addAll(it)
                backedUpRules = null
            }
        }

        override suspend fun getAllRulesSnapshot(): List<AppRuleEntity> = ArrayList(currentAppRules)

        override suspend fun getTransitionsForSession(localSessionId: String): List<SessionTransitionEntity> =
            transitions.filter { it.sessionId == localSessionId }

        override fun saveCloudToLocalSessionMapping(cloudSessionId: String, localSessionId: String) {
            cloudToLocalMap[cloudSessionId] = localSessionId
            localToCloudMap[localSessionId] = cloudSessionId
        }

        override fun getLocalSessionIdForCloudSession(cloudSessionId: String): String? =
            cloudToLocalMap[cloudSessionId]

        override fun getCloudSessionIdForLocalSession(localSessionId: String): String? =
            localToCloudMap[localSessionId]

        override fun getLastCommandSequenceNo(deviceId: String): Long = lastCommandSeq

        override fun setLastCommandSequenceNo(deviceId: String, sequenceNo: Long) {
            lastCommandSeq = sequenceNo
        }

        override fun saveCachedSchedulesJson(schedulesJson: String) {
            cachedSchedulesJson = schedulesJson
        }

        override fun readCachedSchedulesJson(): String? = cachedSchedulesJson
    }

    private data class V05CoordinatorHarness(
        val coordinator: CloudCommandCoordinator,
        val fakeBridge: FakeLocalSessionEngineBridge,
        val myDeviceId: String,
        val myUserId: String,
        val partnerUserId: String
    )

    private suspend fun buildCoordinatorHarness(clockTimeMs: Long = 1793865600000L): V05CoordinatorHarness {
        val baseUrl = "http://127.0.0.1:${server.port}/"
        val file = tmpFolder.newFile("v05_test_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { file }
        )
        val dataSource = BackendPreferencesDataSource(dataStore, InMemoryAesGcmSecretCipher())
        val configRepo = BackendConfigRepository(dataSource, buildConfigUrl = baseUrl)
        val timeoutConfig = NetworkTimeoutConfig(
            connectTimeoutMs = 2_000L,
            readTimeoutMs = 2_000L,
            writeTimeoutMs = 2_000L,
            callTimeoutMs = 4_000L,
            maxIdempotentRetries = 1,
            retryBackoffMs = 10L
        )
        val factory = ArrestApiClientFactory(dataSource, AlwaysOnlineConnectivityChecker(), timeoutConfig)
        val authRepo = BackendAuthRepository(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            scope = ioScope,
            apiClientFactory = factory
        )

        val myUserId = "22222222-2222-4222-8222-222222222222"
        val partnerUserId = "33333333-3333-4333-8333-333333333333"
        val myDeviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

        val identity = configRepo.activeConfig.first().backendIdentity
        dataSource.saveAuthCredentials(
            identity,
            AuthCredentials(
                token = "jwt_device_owner_token",
                refreshToken = "amrt_owner_token_123456",
                accessTokenExpiresAtMs = Instant.now().plusSeconds(900).toEpochMilli(),
                refreshTokenExpiresAtMs = Instant.now().plusSeconds(86400).toEpochMilli(),
                userId = myUserId,
                username = "owner_bob",
                email = "bob@example.com",
                displayName = "Bob Owner",
                registeredDevice = RegisteredDeviceInfo(
                    id = myDeviceId,
                    installationId = "inst-bob-1",
                    deviceName = "Bob Pixel 8",
                    appVersion = "0.5.0",
                    androidVersion = "15",
                    deviceStatus = "ACTIVE"
                )
            )
        )

        val fakeBridge = FakeLocalSessionEngineBridge()
        val coordinator = CloudCommandCoordinator(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            authRepository = authRepo,
            apiClientFactory = factory,
            connectivityChecker = AlwaysOnlineConnectivityChecker(),
            engineBridge = fakeBridge,
            scope = ioScope,
            enableBackgroundPolling = false,
            clockNowMs = { clockTimeMs }
        )

        return V05CoordinatorHarness(
            coordinator = coordinator,
            fakeBridge = fakeBridge,
            myDeviceId = myDeviceId,
            myUserId = myUserId,
            partnerUserId = partnerUserId
        )
    }

    @Test
    fun `authorized immediate remote arrest command starts local session and preserves local rules backup`() = runBlocking {
        val harness = buildCoordinatorHarness(clockTimeMs = 1793865600000L) // 2026-11-05T08:00:00Z
        val authId = "44444444-4444-4444-8444-444444444444"
        val cloudSessionId = "55555555-5555-4555-8555-555555555555"
        val commandId = "66666666-6666-4666-8666-666666666666"

        // 1. Response for listPartnerPermissions (active granted permissions cache)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "items": [
                    {
                      "permissionId": "$authId",
                      "owner": { "id": "${harness.myUserId}", "username": "owner_bob", "displayName": "Bob Owner" },
                      "partner": { "id": "${harness.partnerUserId}", "username": "partner_alice", "displayName": "Alice Partner" },
                      "targetDevice": { "id": "${harness.myDeviceId}", "deviceName": "Bob Pixel 8", "platform": "ANDROID", "deviceStatus": "ACTIVE" },
                      "maximumSessionDuration": 120,
                      "allowedSessionTypes": ["PARTNER_ARREST"],
                      "blockingConfigLimits": { "schemaVersion": 1, "allowedRuleModes": ["BLOCK_SELECTED"], "allowDisableEmergencyExit": false, "allowOverlayStatusBar": true, "requiredAllowedCategories": ["CALLS"], "allowCustomPackageLists": true },
                      "oneTimeOnly": false,
                      "usedCount": 0,
                      "version": 1,
                      "isCurrentlyActive": true,
                      "validFrom": "2026-11-05T00:00:00.000Z",
                      "expiresAt": "2026-11-12T00:00:00.000Z"
                    }
                  ],
                  "total": 1,
                  "limit": 100,
                  "offset": 0
                }
                """.trimIndent()
            )
        )

        // 2. Response for pollDeviceCommands (START_ARREST command queued)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "items": [
                    {
                      "commandId": "$commandId",
                      "sequenceNo": 1,
                      "sessionId": "$cloudSessionId",
                      "targetDeviceId": "${harness.myDeviceId}",
                      "issuedByUserId": "${harness.partnerUserId}",
                      "commandType": "START_ARREST",
                      "payload": {
                        "schemaVersion": 1,
                        "sessionId": "$cloudSessionId",
                        "sessionType": "PARTNER_ARREST",
                        "durationMinutes": 45,
                        "goal": "Partner Study Focus Block",
                        "ruleMode": "BLOCK_SELECTED",
                        "allowedCategories": ["CALLS", "STUDY_APPS"],
                        "overlayStatusBar": true,
                        "disableEmergencyExit": false,
                        "blockedPackages": ["com.instagram.android"],
                        "allowedStudyPackages": ["org.anki.droid"],
                        "authorizationId": "$authId",
                        "initiatedByUserId": "${harness.partnerUserId}"
                      },
                      "status": "PENDING",
                      "expiresAt": "2026-11-05T08:30:00.000Z"
                    }
                  ],
                  "nextSequenceCursor": 1
                }
                """.trimIndent()
            )
        )

        // 3. Response for acknowledgeSession (ACCEPTED -> PREPARING)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "session": {
                    "sessionId": "$cloudSessionId",
                    "ownerUserId": "${harness.myUserId}",
                    "targetDeviceId": "${harness.myDeviceId}",
                    "initiatedByUserId": "${harness.partnerUserId}",
                    "status": "DELIVERED",
                    "remoteExecutionPhase": "EXECUTING",
                    "requestedDurationMinutes": 45
                  }
                }
                """.trimIndent()
            )
        )

        // 4. Response for acknowledgeSession (STARTED -> ACTIVE)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "session": {
                    "sessionId": "$cloudSessionId",
                    "ownerUserId": "${harness.myUserId}",
                    "targetDeviceId": "${harness.myDeviceId}",
                    "initiatedByUserId": "${harness.partnerUserId}",
                    "status": "ACTIVE",
                    "remoteExecutionPhase": "ACTIVE",
                    "requestedDurationMinutes": 45
                  }
                }
                """.trimIndent()
            )
        )

        // 5. Response for acknowledgeCommand (EXECUTED)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "command": {
                    "commandId": "$commandId",
                    "status": "EXECUTED"
                  },
                  "idempotentReplay": false
                }
                """.trimIndent()
            )
        )

        val result = harness.coordinator.pollAndExecutePendingCommands()
        assertTrue(result is CloudResult.Success)

        // Verify local engine was started with mapped remote config
        val active = harness.fakeBridge.activeSession.value
        assertNotNull(active)
        assertEquals(SessionType.PARTNER_ARREST, active?.sessionType)
        assertEquals("Partner Study Focus Block", active?.goal)
        assertEquals(cloudSessionId, active?.cloudSessionId)
        assertEquals(authId, active?.authorizationId)
        assertEquals(harness.partnerUserId, active?.controllingPartnerUserId)

        // Verify local rules backup: partner rules applied, original owner rules backed up
        assertEquals(2, harness.fakeBridge.currentAppRules.size)
        assertTrue(harness.fakeBridge.currentAppRules.any { it.packageName == "com.instagram.android" })
        assertNotNull(harness.fakeBridge.backedUpRules)

        // Verify cloud-to-local mapping was persisted
        assertEquals(active?.sessionId, harness.fakeBridge.cloudToLocalMap[cloudSessionId])

        // Verify ending the session restores the backed up local Self Arrest rules
        harness.fakeBridge.forceCompleteRemoteSession("Completed")
        assertEquals(1, harness.fakeBridge.currentAppRules.size)
        assertEquals("com.owner.original.app", harness.fakeBridge.currentAppRules[0].packageName)
        assertNull(harness.fakeBridge.backedUpRules)
    }

    @Test
    fun `unauthorized remote arrest is rejected and does not start local engine`() = runBlocking {
        val harness = buildCoordinatorHarness(clockTimeMs = 1793865600000L)
        val cloudSessionId = "77777777-7777-4777-8777-777777777777"
        val commandId = "88888888-8888-4888-8888-888888888888"

        // 1. Empty permissions list (no authorization granted)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{ "items": [], "total": 0, "limit": 100, "offset": 0 }"""
            )
        )

        // 2. Poll commands: unauthorized START_ARREST command
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "items": [
                    {
                      "commandId": "$commandId",
                      "sequenceNo": 2,
                      "sessionId": "$cloudSessionId",
                      "targetDeviceId": "${harness.myDeviceId}",
                      "issuedByUserId": "${harness.partnerUserId}",
                      "commandType": "START_ARREST",
                      "payload": {
                        "sessionId": "$cloudSessionId",
                        "sessionType": "PARTNER_ARREST",
                        "durationMinutes": 60,
                        "goal": "Sneaky Unauthorized Session",
                        "authorizationId": "nonexistent-auth-id"
                      },
                      "status": "PENDING",
                      "expiresAt": "2026-11-05T08:30:00.000Z"
                    }
                  ],
                  "nextSequenceCursor": 2
                }
                """.trimIndent()
            )
        )

        // 3. Response for acknowledgeSession (REJECTED)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{ "session": { "sessionId": "$cloudSessionId", "status": "REJECTED" } }"""))

        // 4. Response for acknowledgeCommand (REJECTED)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{ "command": { "commandId": "$commandId", "status": "REJECTED" }, "idempotentReplay": false }"""))

        val result = harness.coordinator.pollAndExecutePendingCommands()
        assertTrue(result is CloudResult.Success)

        // Local engine MUST NOT be started
        assertNull(harness.fakeBridge.activeSession.value)
    }

    @Test
    fun `expired command is rejected without starting local session`() = runBlocking {
        // Clock is 08:35 UTC, but command expired at 08:30 UTC
        val harness = buildCoordinatorHarness(clockTimeMs = 1793867700000L) // 08:35 UTC
        val cloudSessionId = "99999999-9999-4999-8999-999999999999"
        val commandId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        // 1. Permissions list
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{ "items": [], "total": 0, "limit": 100, "offset": 0 }"""))

        // 2. Poll commands: expired command
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "items": [
                    {
                      "commandId": "$commandId",
                      "sequenceNo": 3,
                      "sessionId": "$cloudSessionId",
                      "targetDeviceId": "${harness.myDeviceId}",
                      "issuedByUserId": "${harness.partnerUserId}",
                      "commandType": "START_ARREST",
                      "payload": {
                        "sessionId": "$cloudSessionId",
                        "sessionType": "PARTNER_ARREST",
                        "durationMinutes": 30
                      },
                      "status": "PENDING",
                      "expiresAt": "2026-11-05T08:30:00.000Z"
                    }
                  ],
                  "nextSequenceCursor": 3
                }
                """.trimIndent()
            )
        )

        // 3. Response for acknowledgeSession (REJECTED)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{ "session": { "sessionId": "$cloudSessionId", "status": "REJECTED" } }"""))

        // 4. Response for acknowledgeCommand (REJECTED)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{ "command": { "commandId": "$commandId", "status": "REJECTED" }, "idempotentReplay": false }"""))

        val result = harness.coordinator.pollAndExecutePendingCommands()
        assertTrue(result is CloudResult.Success)
        assertNull(harness.fakeBridge.activeSession.value)
    }

    @Test
    fun `selectable app catalog filters out internal system apps before upload`() = runBlocking {
        val harness = buildCoordinatorHarness()

        val sampleApps = listOf(
            InstalledAppInfo(packageName = "com.instagram.android", label = "Instagram", isEssentialProtected = false, isHomeLauncher = false),
            InstalledAppInfo(packageName = "org.anki.droid", label = "AnkiDroid", isEssentialProtected = false, isHomeLauncher = false),
            // Protected system apps that must be excluded:
            InstalledAppInfo(packageName = "com.android.systemui", label = "System UI", isEssentialProtected = true, isHomeLauncher = false),
            InstalledAppInfo(packageName = "com.android.settings", label = "Settings", isEssentialProtected = true, isHomeLauncher = false),
            InstalledAppInfo(packageName = "com.arrestme.benchmark", label = "ArrestMe", isEssentialProtected = false, isHomeLauncher = false)
        )

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "catalog": {
                    "deviceId": "${harness.myDeviceId}",
                    "ownerUserId": "${harness.myUserId}",
                    "apps": [
                      { "packageName": "org.anki.droid", "appLabel": "AnkiDroid", "category": "USER_APP", "isStudyCandidate": false },
                      { "packageName": "com.instagram.android", "appLabel": "Instagram", "category": "USER_APP", "isStudyCandidate": false }
                    ],
                    "filteredOutSystemCount": 3,
                    "updatedAt": "2026-11-05T08:00:00.000Z"
                  }
                }
                """.trimIndent()
            )
        )

        val result = harness.coordinator.uploadSelectableAppCatalog(sampleApps)
        assertTrue(result is CloudResult.Success)
        val catalog = (result as CloudResult.Success).data
        assertEquals(2, catalog.apps.size)
        assertEquals(3, catalog.filteredOutSystemCount)

        val recordedRequest = server.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()
        assertFalse(requestBody.contains("com.android.systemui"))
        assertFalse(requestBody.contains("com.android.settings"))
        assertFalse(requestBody.contains("com.arrestme.benchmark"))
        assertTrue(requestBody.contains("com.instagram.android"))
        assertTrue(requestBody.contains("org.anki.droid"))
    }
}
