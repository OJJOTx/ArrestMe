package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType
import com.arrestme.benchmark.core.session.TemporaryAppAccessOverride
import com.arrestme.benchmark.data.remote.AlwaysOnlineConnectivityChecker
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.NetworkTimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class PartnerChatRepositoryV06Test {

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

    private fun createHarness(): Pair<PartnerChatRepository, String> {
        val baseUrl = "http://127.0.0.1:${server.port}/"
        val dsFile = tmpFolder.newFile("test_chat_prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = ioScope) { dsFile }
        val fakeCipher = InMemoryAesGcmSecretCipher()
        val prefs = BackendPreferencesDataSource(dataStore, fakeCipher)
        val connectivity = AlwaysOnlineConnectivityChecker()
        val clientFactory = ArrestApiClientFactory(prefs, connectivity, NetworkTimeoutConfig(1_000L, 1_000L, 1_000L))

        val configRepo = BackendConfigRepository(prefs, buildConfigUrl = baseUrl)
        val authRepo = BackendAuthRepository(
            preferencesDataSource = prefs,
            configRepository = configRepo,
            scope = ioScope,
            apiClientFactory = clientFactory
        )

        runBlocking {
            val identity = configRepo.activeConfig.first().backendIdentity
            prefs.saveAuthCredentials(
                identity,
                AuthCredentials(
                    token = "mock_chat_token_v06",
                    refreshToken = "mock_chat_refresh_v06",
                    accessTokenExpiresAtMs = Instant.now().plusSeconds(3600).toEpochMilli(),
                    refreshTokenExpiresAtMs = Instant.now().plusSeconds(86400).toEpochMilli(),
                    userId = "usr_alice_111",
                    username = "alice",
                    email = "alice@example.com",
                    displayName = "Alice Wonderland"
                )
            )
        }

        val chatRepo = PartnerChatRepository(
            preferencesDataSource = prefs,
            configRepository = configRepo,
            authRepository = authRepo,
            apiClientFactory = clientFactory,
            scope = ioScope,
            autoRefreshOnAuth = false
        )

        return Pair(chatRepo, baseUrl)
    }

    @Test
    fun testListConversationsAndSelectConversation() = runBlocking {
        val (chatRepo, _) = createHarness()

        val listJson = """
            {
                "items": [
                    {
                        "id": "conv_123",
                        "partnershipId": "part_456",
                        "unreadCount": 2,
                        "partner": {
                            "id": "usr_bob_222",
                            "username": "bob",
                            "displayName": "Bob Builder"
                        },
                        "lastMessage": {
                            "id": "msg_001",
                            "conversationId": "conv_123",
                            "sender": {
                                "id": "usr_bob_222",
                                "username": "bob",
                                "displayName": "Bob Builder"
                            },
                            "clientMessageId": "client_1",
                            "messageType": "TEXT",
                            "textContent": "Hey Alice!",
                            "status": "DELIVERED",
                            "createdAt": "1730000000000",
                            "updatedAt": "1730000000000"
                        },
                        "createdAt": "1730000000000",
                        "updatedAt": "1730000000000"
                    }
                ],
                "total": 1,
                "limit": 50,
                "offset": 0
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(listJson))

        val result = chatRepo.refreshConversations()
        if (result is CloudResult.Failure) {
            System.err.println("DEBUG FAILURE: ${(result as CloudResult.Failure).error.message}")
        }
        assertTrue(result is CloudResult.Success)
        val conversations = (result as CloudResult.Success).data
        assertEquals(1, conversations.size)
        assertEquals("conv_123", conversations[0].id)
        assertEquals(1, chatRepo.conversations.value.size)

        // Select conversation triggers getConversation + listMessages
        val getConvJson = """
            {
                "conversation": {
                    "id": "conv_123",
                    "partnershipId": "part_456",
                    "partner": {
                        "id": "usr_bob_222",
                        "username": "bob",
                        "displayName": "Bob Builder"
                    },
                    "unreadCount": 2,
                    "createdAt": "1730000000000",
                    "updatedAt": "1730000000000"
                }
            }
        """.trimIndent()
        val getMessagesJson = """
            {
                "items": [
                    {
                        "id": "msg_001",
                        "conversationId": "conv_123",
                        "sender": {
                            "id": "usr_bob_222",
                            "username": "bob",
                            "displayName": "Bob Builder"
                        },
                        "clientMessageId": "client_1",
                        "messageType": "TEXT",
                        "textContent": "Hey Alice!",
                        "status": "DELIVERED",
                        "createdAt": "1730000000000",
                        "updatedAt": "1730000000000"
                    }
                ],
                "total": 1,
                "limit": 100,
                "offset": 0
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(getConvJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(getMessagesJson))

        val selectRes = chatRepo.selectConversation("conv_123")
        assertTrue(selectRes is CloudResult.Success)
        assertEquals("conv_123", chatRepo.activeConversation.value?.id)
        assertEquals(1, chatRepo.messages.value.size)
        assertEquals("Hey Alice!", chatRepo.messages.value[0].textContent)
    }

    @Test
    fun testSendTextMessageWithOptimisticEcho() = runBlocking {
        val (chatRepo, _) = createHarness()

        val sendRespJson = """
            {
                "message": {
                    "id": "msg_server_999",
                    "conversationId": "conv_123",
                    "sender": {
                        "id": "usr_alice_111",
                        "username": "alice",
                        "displayName": "Alice Wonderland"
                    },
                    "clientMessageId": "msg_client_test",
                    "messageType": "TEXT",
                    "textContent": "I need help with my study block",
                    "status": "SENT",
                    "createdAt": "1730000005000",
                    "updatedAt": "1730000005000"
                }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(201).setBody(sendRespJson))

        val res = chatRepo.sendTextMessage("conv_123", "I need help with my study block")
        assertTrue(res is CloudResult.Success)
        val msg = (res as CloudResult.Success).data
        assertEquals("msg_server_999", msg.id)
        assertEquals("I need help with my study block", msg.textContent)
    }

    @Test
    fun testSendAndRespondStructuredRequest() = runBlocking {
        val (chatRepo, _) = createHarness()

        val createReqResp = """
            {
                "message": {
                    "id": "msg_req_101",
                    "conversationId": "conv_123",
                    "sender": {
                        "id": "usr_alice_111",
                        "username": "alice",
                        "displayName": "Alice Wonderland"
                    },
                    "clientMessageId": "req_client_test",
                    "messageType": "STRUCTURED_REQUEST",
                    "textContent": "Requested temporary access to com.google.android.gm (10 min)",
                    "structuredRequest": {
                        "id": "req_struct_001",
                        "conversationId": "conv_123",
                        "messageId": "msg_req_101",
                        "requestType": "REQUEST_TEMPORARY_APP_ACCESS",
                        "targetPackage": "com.google.android.gm",
                        "durationMinutes": 10,
                        "status": "PENDING",
                        "messageText": "Need to check two-factor email",
                        "createdAt": "1730000010000",
                        "updatedAt": "1730000010000"
                    },
                    "status": "SENT",
                    "createdAt": "1730000010000",
                    "updatedAt": "1730000010000"
                }
            }
        """.trimIndent()

        val respondResp = """
            {
                "request": {
                    "id": "req_struct_001",
                    "conversationId": "conv_123",
                    "messageId": "msg_req_101",
                    "requestType": "REQUEST_TEMPORARY_APP_ACCESS",
                    "targetPackage": "com.google.android.gm",
                    "durationMinutes": 10,
                    "status": "APPROVED",
                    "decidedAt": "1730000020000",
                    "createdAt": "1730000010000",
                    "updatedAt": "1730000020000"
                }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(201).setBody(createReqResp))
        server.enqueue(MockResponse().setResponseCode(200).setBody(respondResp))

        val sendReqRes = chatRepo.sendStructuredRequest(
            conversationId = "conv_123",
            requestType = "REQUEST_TEMPORARY_APP_ACCESS",
            sessionId = "sess_local_777",
            targetPackage = "com.google.android.gm",
            durationMinutes = 10,
            messageText = "Need to check two-factor email"
        )
        assertTrue(sendReqRes is CloudResult.Success)
        val reqMsg = (sendReqRes as CloudResult.Success).data
        assertEquals("REQUEST_TEMPORARY_APP_ACCESS", reqMsg.structuredRequest?.requestType)
        assertEquals("PENDING", reqMsg.structuredRequest?.status)

        val respondRes = chatRepo.respondToStructuredRequest(
            requestId = "req_struct_001",
            decision = "APPROVED",
            reason = "Granted for 10 minutes"
        )
        assertTrue(respondRes is CloudResult.Success)
        val updatedReq = (respondRes as CloudResult.Success).data
        assertEquals("APPROVED", updatedReq.status)
    }

    @Test
    fun testTemporaryAppAccessOverrideLocalOfflineExpiration() {
        // Deterministic local offline test:
        // Even if the phone loses connection, ActiveSessionSnapshot + isPackageTemporarilyAllowed
        // automatically expires the grant when wall clock exceeds expiresAtWallMs.
        val now = 1_000_000L
        val grant = TemporaryAppAccessOverride(
            grantId = "grant_temp_001",
            packageName = "com.google.android.gm",
            expiresAtWallMs = now + 10 * 60 * 1000L // 10 minutes from now
        )

        val activeSnapshot = ActiveSessionSnapshot(
            sessionId = "sess_offline_test",
            sessionType = SessionType.PARTNER_ARREST,
            ruleMode = BlockingRuleMode.BLOCK_SELECTED,
            state = SessionState.ACTIVE,
            goal = "Focus session",
            allowedCategories = emptySet(),
            overlayStatusBar = false,
            disableEmergencyExit = false,
            configuredDurationMs = 60 * 60 * 1000L,
            startTimestampMs = now - 5_000L,
            endTimestampMs = now + 60 * 60 * 1000L,
            pausedAtTimestampMs = null,
            accumulatedPausedMs = 0L,
            startElapsedRealtimeMs = 1000L,
            bootCountAtStart = 1,
            lastUpdatedWallMs = now,
            temporaryAccessOverrides = listOf(grant)
        )

        // 1. While within the 10 min window: package is temporarily allowed!
        assertTrue(activeSnapshot.isPackageTemporarilyAllowed("com.google.android.gm", nowWallMs = now + 5 * 60 * 1000L))

        // 2. Unrelated package is not allowed:
        assertFalse(activeSnapshot.isPackageTemporarilyAllowed("com.instagram.android", nowWallMs = now + 5 * 60 * 1000L))

        // 3. Exactly at expiration: expired!
        assertFalse(activeSnapshot.isPackageTemporarilyAllowed("com.google.android.gm", nowWallMs = now + 10 * 60 * 1000L))

        // 4. Past expiration: expired! Local safety guaranteed without network.
        assertFalse(activeSnapshot.isPackageTemporarilyAllowed("com.google.android.gm", nowWallMs = now + 15 * 60 * 1000L))
    }
}
