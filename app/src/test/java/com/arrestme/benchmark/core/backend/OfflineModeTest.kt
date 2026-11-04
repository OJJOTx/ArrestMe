package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionConfig
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.NetworkConnectivityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineModeTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var ioScope: CoroutineScope

    @Before
    fun setUp() {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
    }

    @Test
    fun `application operates fully in local-only mode when no server URL is provided`() = runBlocking {
        val testFile = tmpFolder.newFile("test_offline_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { testFile }
        )
        val dataSource = BackendPreferencesDataSource(dataStore)
        val configRepo = BackendConfigRepository(
            preferencesDataSource = dataSource,
            buildConfigUrl = ""
        )
        val authRepo = BackendAuthRepository(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            scope = ioScope
        )

        val config = configRepo.activeConfig.first()
        assertEquals(BackendMode.OFFLINE_LOCAL, config.mode)
        assertNull(config.effectiveBaseUrl)
        assertEquals("offline", config.backendIdentity)
        assertTrue(config.isLocalOnly)

        val authState = authRepo.authState.first()
        assertEquals(BackendAuthState.Offline, authState)
        assertFalse(authState.isAuthenticated)
    }

    @Test
    fun `offline connectivity precheck prevents network sockets and keeps local session models deterministic`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString()
            val offlineChecker = object : NetworkConnectivityChecker {
                override fun isOnline(): Boolean = false
            }

            val testFile = tmpFolder.newFile("test_offline_net_${System.nanoTime()}.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(
                scope = ioScope,
                produceFile = { testFile }
            )
            val dataSource = BackendPreferencesDataSource(dataStore)
            val factory = ArrestApiClientFactory(
                preferencesDataSource = dataSource,
                connectivityChecker = offlineChecker
            )
            val configRepo = BackendConfigRepository(dataSource, buildConfigUrl = baseUrl)
            val authRepo = BackendAuthRepository(
                preferencesDataSource = dataSource,
                configRepository = configRepo,
                scope = ioScope,
                apiClientFactory = factory
            )

            val loginRes = authRepo.login("user@example.com", "ValidPassword!123")
            assertTrue(loginRes is CloudResult.Failure)
            assertTrue((loginRes as CloudResult.Failure).error is CloudError.Offline)
            assertEquals(0, server.requestCount)

            val sessionCfg = SessionConfig(
                sessionType = SessionType.SELF_ARREST,
                durationMinutes = 25,
                goal = "Offline Deep Work",
                allowedCategories = setOf(AllowedCategory.CALLS, AllowedCategory.STUDY_APPS),
                ruleMode = BlockingRuleMode.STRICT_ALLOWLIST,
                overlayStatusBar = true,
                disableEmergencyExit = true
            )
            val startMs = 1_700_000_000_000L
            val snapshot = ActiveSessionSnapshot(
                sessionId = "arrest-offline1",
                sessionType = sessionCfg.sessionType,
                state = SessionState.ACTIVE,
                goal = sessionCfg.goal,
                allowedCategories = sessionCfg.allowedCategories,
                ruleMode = sessionCfg.ruleMode,
                overlayStatusBar = sessionCfg.overlayStatusBar,
                disableEmergencyExit = sessionCfg.disableEmergencyExit,
                configuredDurationMs = sessionCfg.durationMs,
                startTimestampMs = startMs,
                endTimestampMs = startMs + sessionCfg.durationMs,
                pausedAtTimestampMs = null,
                accumulatedPausedMs = 0L,
                startElapsedRealtimeMs = 10_000L,
                bootCountAtStart = 1,
                lastUpdatedWallMs = startMs
            )

            assertFalse(snapshot.hasExpired(startMs + 60_000L))
            assertEquals(24 * 60_000L, snapshot.computeRemainingMs(startMs + 60_000L))
            assertTrue(snapshot.hasExpired(startMs + sessionCfg.durationMs))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `API client safely returns error rather than crashing when testing offline or blank URL`() = runBlocking {
        val client = BackendApiClient()

        val nullResult = client.checkHealth("")
        assertTrue(nullResult is HealthCheckResult.Error)
        assertTrue((nullResult as HealthCheckResult.Error).error.contains("cannot be empty"))

        val malformedResult = client.checkHealth("not_a_valid_url")
        assertTrue(malformedResult is HealthCheckResult.Error)

        val insecureResult = client.checkHealth("http://insecure-remote.example.com")
        assertTrue(insecureResult is HealthCheckResult.Error)
        assertTrue((insecureResult as HealthCheckResult.Error).error.contains("HTTPS is required"))
    }
}
