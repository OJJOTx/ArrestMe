package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.arrestme.benchmark.data.remote.AlwaysOnlineConnectivityChecker
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.NetworkTimeoutConfig
import com.arrestme.benchmark.data.remote.dto.BlockingConfigurationDto
import com.arrestme.benchmark.data.remote.dto.CounterofferProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateProposalRequestDto
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class PartnerSystemV04Test {

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

    private data class V04Harness(
        val dataSource: BackendPreferencesDataSource,
        val configRepo: BackendConfigRepository,
        val authRepo: BackendAuthRepository,
        val partnerRepo: PartnerSystemRepository
    )

    private suspend fun buildAuthenticatedHarness(): V04Harness {
        val baseUrl = "http://127.0.0.1:${server.port}/"
        val file = tmpFolder.newFile("v04_test_${System.nanoTime()}.preferences_pb")
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
        val identity = configRepo.activeConfig.first().backendIdentity
        dataSource.saveAuthCredentials(
            identity,
            AuthCredentials(
                token = "v04_jwt_token_alice",
                refreshToken = "amrt_v04_refresh_token_alice_123456",
                accessTokenExpiresAtMs = Instant.now().plusSeconds(900).toEpochMilli(),
                refreshTokenExpiresAtMs = Instant.now().plusSeconds(86400).toEpochMilli(),
                userId = "11111111-1111-4111-8111-111111111111",
                username = "alice_v04",
                email = "alice@example.com",
                displayName = "Alice V04",
                registeredDevice = RegisteredDeviceInfo(
                    id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    installationId = "inst-alice-1",
                    deviceName = "Alice Pixel",
                    appVersion = "0.4.0",
                    androidVersion = "15",
                    deviceStatus = "ACTIVE"
                )
            )
        )
        val partnerRepo = PartnerSystemRepository(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            authRepository = authRepo,
            apiClientFactory = factory,
            scope = ioScope,
            autoRefreshOnAuth = false
        )
        return V04Harness(dataSource, configRepo, authRepo, partnerRepo)
    }

    @Test
    fun `discover partner by username and invite code and send partner request`() = runBlocking {
        val harness = buildAuthenticatedHarness()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "found": true,
                      "user": {
                        "id": "22222222-2222-4222-8222-222222222222",
                        "username": "bob_v04",
                        "displayName": "Bob V04",
                        "avatarUrl": null
                      },
                      "relationshipState": "NONE",
                      "partnershipId": null,
                      "pendingCounts": {
                        "incomingFriendRequestsCount": 1,
                        "outgoingFriendRequestsCount": 0,
                        "incomingProposalsCount": 2,
                        "outgoingProposalsCount": 0
                      }
                    }
                    """.trimIndent()
                )
        )

        val discoverRes = harness.partnerRepo.discoverPartner(username = "bob_v04")
        assertTrue("Expected success, got $discoverRes", discoverRes is CloudResult.Success)
        val dto = (discoverRes as CloudResult.Success).data
        assertTrue(dto.found)
        assertEquals("bob_v04", dto.user?.username)
        assertEquals("NONE", dto.relationshipState)
        assertEquals(2, harness.partnerRepo.pendingCounts.value.incomingProposalsCount)
    }

    @Test
    fun `partner profile loads directional approval policy defaulting to requireApprovalBeforeArrest true`() = runBlocking {
        val harness = buildAuthenticatedHarness()
        val partnershipId = "33333333-3333-4333-8333-333333333333"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "profile": {
                        "partnership": {
                          "id": "$partnershipId",
                          "state": "ACCEPTED",
                          "sender": {"id": "11111111-1111-4111-8111-111111111111", "username": "alice_v04", "displayName": "Alice V04"},
                          "recipient": {"id": "22222222-2222-4222-8222-222222222222", "username": "bob_v04", "displayName": "Bob V04"},
                          "partner": {"id": "22222222-2222-4222-8222-222222222222", "username": "bob_v04", "displayName": "Bob V04"},
                          "createdAt": "2026-11-04T20:00:00.000Z",
                          "updatedAt": "2026-11-04T20:00:00.000Z"
                        },
                        "partner": {"id": "22222222-2222-4222-8222-222222222222", "username": "bob_v04", "displayName": "Bob V04"},
                        "relationshipStatus": "ACCEPTED",
                        "myDevices": [{"id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "deviceName": "Alice Pixel", "platform": "ANDROID", "deviceStatus": "ACTIVE"}],
                        "partnerDevices": [{"id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "deviceName": "Bob Galaxy", "platform": "ANDROID", "deviceStatus": "ACTIVE"}],
                        "permissionsGrantedToPartner": [],
                        "permissionsReceivedFromPartner": [],
                        "myApprovalPolicyForPartner": {
                          "ownerUserId": "11111111-1111-4111-8111-111111111111",
                          "partnerUserId": "22222222-2222-4222-8222-222222222222",
                          "requireApprovalBeforeArrest": true,
                          "explicitlyConfigured": false,
                          "activeStandingAuthorizationsCount": 0,
                          "activeStandingAuthorizations": []
                        },
                        "partnerApprovalPolicyForMe": {
                          "ownerUserId": "22222222-2222-4222-8222-222222222222",
                          "partnerUserId": "11111111-1111-4111-8111-111111111111",
                          "requireApprovalBeforeArrest": true,
                          "explicitlyConfigured": false,
                          "activeStandingAuthorizationsCount": 0,
                          "activeStandingAuthorizations": []
                        },
                        "pendingProposals": [],
                        "activeSchedules": []
                      }
                    }
                    """.trimIndent()
                )
        )

        val profileRes = harness.partnerRepo.loadPartnerProfile(partnershipId)
        assertTrue("Expected profile success, got $profileRes", profileRes is CloudResult.Success)
        val profile = (profileRes as CloudResult.Success).data
        assertEquals("bob_v04", profile.partner.username)
        assertTrue(profile.myApprovalPolicyForPartner.requireApprovalBeforeArrest)
        assertTrue(profile.partnerApprovalPolicyForMe.requireApprovalBeforeArrest)
        assertEquals(0, profile.permissionsGrantedToPartner.size)
        assertEquals(0, profile.permissionsReceivedFromPartner.size)
    }

    @Test
    fun `proposal lifecycle supports submit, counteroffer, stale revision 409 conflict, and accept`() = runBlocking {
        val harness = buildAuthenticatedHarness()
        val proposalId = "44444444-4444-4444-8444-444444444444"

        // 1. Submit Proposal (Revision 1) + background refresh empty responses
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleProposalEnvelopeJson(proposalId, revision = 1, status = "PENDING", duration = 45))
        )
        enqueueEmptyRefreshBatch()

        val createRes = harness.partnerRepo.submitProposal(
            CreateProposalRequestDto(
                targetDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                partnerUserId = "22222222-2222-4222-8222-222222222222",
                requestType = "IMMEDIATE",
                goal = "Deep Focus Chapter 4",
                durationMinutes = 45,
                blockingConfiguration = BlockingConfigurationDto(
                    ruleMode = "BLOCK_SELECTED",
                    blockedPackages = listOf("com.instagram.android")
                )
            )
        )
        assertTrue("Expected createProposal success, got $createRes", createRes is CloudResult.Success)
        assertEquals(1, (createRes as CloudResult.Success).data.currentRevision)
        assertEquals("PENDING", createRes.data.status)

        // 2. Counteroffer Proposal -> Revision 2 (COUNTEROFFERED)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleProposalEnvelopeJson(proposalId, revision = 2, status = "COUNTEROFFERED", duration = 30))
        )
        enqueueEmptyRefreshBatch()

        val counterRes = harness.partnerRepo.counterofferProposal(
            proposalId = proposalId,
            request = CounterofferProposalRequestDto(
                expectedRevisionNumber = 1,
                durationMinutes = 30,
                reason = "Let's do 30 minutes instead"
            )
        )
        assertTrue("Expected counteroffer success, got $counterRes", counterRes is CloudResult.Success)
        assertEquals(2, (counterRes as CloudResult.Success).data.currentRevision)
        assertEquals("COUNTEROFFERED", counterRes.data.status)
        assertEquals(30, counterRes.data.normalizedConfiguration.durationMinutes)

        // 3. Stale Revision Acceptance Attempt -> 409 STALE_PROPOSAL_REVISION
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "code": "STALE_PROPOSAL_REVISION",
                        "message": "Proposal is now at revision #2, but revision #1 was submitted."
                      }
                    }
                    """.trimIndent()
                )
        )

        val staleAccept = harness.partnerRepo.acceptProposal(proposalId = proposalId, expectedRevisionNumber = 1)
        assertTrue("Expected stale revision failure", staleAccept is CloudResult.Failure)
        val staleErr = (staleAccept as CloudResult.Failure).error
        assertEquals("STALE_PROPOSAL_REVISION", staleErr.code)

        // 4. Accept Current Revision (#2) -> 200 APPROVED
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "proposal": ${sampleProposalObjectJson(proposalId, revision = 2, status = "APPROVED", duration = 30)},
                      "permission": null,
                      "session": null,
                      "schedule": null
                    }
                    """.trimIndent()
                )
        )
        enqueueEmptyRefreshBatch()

        val validAccept = harness.partnerRepo.acceptProposal(proposalId = proposalId, expectedRevisionNumber = 2)
        assertTrue("Expected valid revision accept success, got $validAccept", validAccept is CloudResult.Success)
        val accepted = (validAccept as CloudResult.Success).data.proposal
        assertEquals("APPROVED", accepted.status)
        assertEquals(2, accepted.approvedRevision)
    }

    @Test
    fun `offline mode blocks cloud partner calls cleanly without crashing`() = runBlocking {
        val harness = buildAuthenticatedHarness()
        harness.configRepo.enableLocalOnly()

        val res = harness.partnerRepo.discoverPartner(username = "bob_v04")
        assertTrue(res is CloudResult.Failure)
        val err = (res as CloudResult.Failure).error
        assertTrue(err is CloudError.Configuration)
        assertFalse(harness.authRepo.authState.first { it is BackendAuthState.Offline }.isAuthenticated)
    }

    private fun enqueueEmptyRefreshBatch() {
        repeat(6) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"items":[],"total":0,"limit":50,"offset":0,"inviteCode":"ARST-TEST-CODE"}""")
            )
        }
    }

    private fun sampleProposalEnvelopeJson(
        proposalId: String,
        revision: Int,
        status: String,
        duration: Int
    ): String = """{"proposal": ${sampleProposalObjectJson(proposalId, revision, status, duration)}}"""

    private fun sampleProposalObjectJson(
        proposalId: String,
        revision: Int,
        status: String,
        duration: Int
    ): String =
        """
        {
          "proposalId": "$proposalId",
          "partnershipId": "33333333-3333-4333-8333-333333333333",
          "owner": {"id": "22222222-2222-4222-8222-222222222222", "username": "bob_v04", "displayName": "Bob V04"},
          "partner": {"id": "11111111-1111-4111-8111-111111111111", "username": "alice_v04", "displayName": "Alice V04"},
          "targetDevice": {"id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "deviceName": "Bob Galaxy", "platform": "ANDROID", "deviceStatus": "ACTIVE"},
          "proposer": {"id": "11111111-1111-4111-8111-111111111111", "username": "alice_v04", "displayName": "Alice V04"},
          "recipient": {"id": "22222222-2222-4222-8222-222222222222", "username": "bob_v04", "displayName": "Bob V04"},
          "requestType": "IMMEDIATE",
          "currentRevision": $revision,
          "status": "$status",
          "normalizedConfiguration": {
            "schemaVersion": 1,
            "requestType": "IMMEDIATE",
            "targetDeviceId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "ownerUserId": "22222222-2222-4222-8222-222222222222",
            "initiatingPartnerUserId": "11111111-1111-4111-8111-111111111111",
            "sessionType": "PARTNER_ARREST",
            "goal": "Deep Focus Chapter 4",
            "durationMinutes": $duration,
            "blockingConfiguration": {
              "schemaVersion": 1,
              "ruleMode": "BLOCK_SELECTED",
              "allowedCategories": ["CALLS", "STUDY_APPS"],
              "overlayStatusBar": false,
              "disableEmergencyExit": false,
              "blockedPackages": ["com.instagram.android"],
              "allowedStudyPackages": []
            },
            "requireApprovalBeforeArrest": true
          },
          "reason": "Study session",
          "approvedRevision": ${if (status == "APPROVED") revision else "null"},
          "resultingPermissionId": null,
          "resultingSessionId": null,
          "resultingScheduleId": null,
          "expiresAt": "2026-11-05T20:00:00.000Z",
          "respondedAt": null,
          "createdAt": "2026-11-04T20:00:00.000Z",
          "updatedAt": "2026-11-04T20:01:00.000Z",
          "revisions": []
        }
        """.trimIndent()
}
