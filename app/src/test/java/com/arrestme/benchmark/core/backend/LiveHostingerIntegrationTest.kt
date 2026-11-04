package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.arrestme.benchmark.data.remote.AlwaysOnlineConnectivityChecker
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveHostingerIntegrationTest {

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
    fun `end-to-end cloud verification against deployed Hostinger backend`() = runBlocking {
        val liveUrl = (
            System.getProperty("ARRESTME_LIVE_TEST_URL")
                ?: System.getenv("ARRESTME_LIVE_TEST_URL")
                ?: ""
            ).trim()

        assumeTrue(
            "Skipping live Hostinger smoke test because ARRESTME_LIVE_TEST_URL is not set",
            liveUrl.isNotEmpty()
        )

        val file = tmpFolder.newFile("live_hostinger_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { file }
        )
        val dataSource = BackendPreferencesDataSource(dataStore, InMemoryAesGcmSecretCipher())
        val configRepo = BackendConfigRepository(dataSource, buildConfigUrl = liveUrl)
        val factory = ArrestApiClientFactory(dataSource, AlwaysOnlineConnectivityChecker())
        val healthClient = BackendApiClient(factory, AlwaysOnlineConnectivityChecker())
        val authRepo = BackendAuthRepository(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            scope = ioScope,
            apiClientFactory = factory,
            deviceMetadataProvider = object : DeviceMetadataProvider {
                override fun currentMetadata() = LocalDeviceMetadata(
                    deviceName = "ArrestMe V0.3 Integration Smoke Device",
                    manufacturer = "Google",
                    model = "Pixel 9",
                    appVersion = "0.3.0",
                    androidVersion = "15",
                    sdkInt = 35
                )
            }
        )

        val healthRes = healthClient.checkHealth(liveUrl)
        assertTrue("Expected health check success, got: $healthRes", healthRes is HealthCheckResult.Success)

        val uniqueSuffix = (System.currentTimeMillis() % 1_000_000L).toString()
        val username = "v03_test_$uniqueSuffix"
        val email = "v03_test_$uniqueSuffix@example.com"
        val password = "V03Cloud#Verify$uniqueSuffix"

        val regRes = authRepo.register(
            username = username,
            email = email,
            password = password,
            displayName = "V0.3 Smoke User"
        )
        assertTrue("Expected register success, got: $regRes", regRes is CloudResult.Success)
        val initialCreds = (regRes as CloudResult.Success).data
        assertEquals(username, initialCreds.username)
        assertNotNull("Expected device registered automatically on Hostinger", initialCreds.registeredDevice)
        val registeredDeviceId = initialCreds.registeredDevice!!.id
        val initialAccessToken = initialCreds.token!!
        val initialRefreshToken = initialCreds.refreshToken!!

        val identity = configRepo.activeConfig.first().backendIdentity
        dataSource.saveAuthCredentials(
            identity,
            initialCreds.copy(accessTokenExpiresAtMs = System.currentTimeMillis() - 60_000L)
        )

        val syncRes = authRepo.syncAccountAndDevice()
        assertTrue("Expected sync + token rotation success, got: $syncRes", syncRes is CloudResult.Success)
        val syncedCreds = (syncRes as CloudResult.Success).data
        assertNotEquals("Access token should rotate", initialAccessToken, syncedCreds.token)
        assertNotEquals("Refresh token should rotate", initialRefreshToken, syncedCreds.refreshToken)
        assertEquals(registeredDeviceId, syncedCreds.registeredDevice?.id)

        val logoutRes = authRepo.logout(deregisterDevice = true)
        assertTrue(logoutRes is CloudResult.Success)
        assertFalse(authRepo.authState.first { it is BackendAuthState.Unauthenticated }.isAuthenticated)

        val invalidLogin = authRepo.login(email = email, password = "WrongPassword#0000")
        assertTrue(invalidLogin is CloudResult.Failure)
        assertEquals("INVALID_CREDENTIALS", (invalidLogin as CloudResult.Failure).error.code)

        val relogin = authRepo.login(email = email, password = password)
        assertTrue("Expected re-login success, got: $relogin", relogin is CloudResult.Success)
        assertEquals(registeredDeviceId, (relogin as CloudResult.Success).data.registeredDevice?.id)
        authRepo.logout(deregisterDevice = true)
    }
}
