package com.arrestme.benchmark.core.backend

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendAuthRepositoryTest {

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

    private data class RepoFixture(
        val dataStore: DataStore<Preferences>,
        val dataSource: BackendPreferencesDataSource,
        val configRepo: BackendConfigRepository,
        val authRepo: BackendAuthRepository
    )

    private fun createRepositories(buildUrl: String = ""): RepoFixture {
        val testFile = tmpFolder.newFile("test_auth_prefs_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { testFile }
        )
        val cipher = InMemoryAesGcmSecretCipher()
        val dataSource = BackendPreferencesDataSource(dataStore, cipher)
        val configRepo = BackendConfigRepository(
            preferencesDataSource = dataSource,
            buildConfigUrl = buildUrl
        )
        val authRepo = BackendAuthRepository(
            preferencesDataSource = dataSource,
            configRepository = configRepo,
            scope = ioScope
        )
        return RepoFixture(dataStore, dataSource, configRepo, authRepo)
    }

    @Test
    fun `tokens are encrypted at rest in DataStore and decrypted transparently`() = runBlocking {
        val fixture = createRepositories(buildUrl = "https://server-a.com")
        val identity = "https://server-a.com"

        fixture.authRepo.saveCredentialsForBackend(
            identity,
            AuthCredentials(
                token = "plaintext_access_jwt_secret",
                refreshToken = "amrt_plaintext_refresh_secret_12345",
                username = "alice"
            )
        )

        val rawPrefs = fixture.dataStore.data.first()
        val rawStoredAccess = rawPrefs[BackendPreferencesDataSource.encryptedTokenKey(identity)]
        val rawStoredRefresh = rawPrefs[BackendPreferencesDataSource.encryptedRefreshTokenKey(identity)]

        assertNotNull(rawStoredAccess)
        assertNotNull(rawStoredRefresh)
        assertFalse(rawStoredAccess!!.contains("plaintext_access_jwt_secret"))
        assertFalse(rawStoredRefresh!!.contains("amrt_plaintext_refresh_secret_12345"))
        assertTrue(rawStoredAccess.startsWith("enc:mem:v1:"))

        val state = fixture.authRepo.authState.first { it is BackendAuthState.Authenticated } as BackendAuthState.Authenticated
        assertEquals("plaintext_access_jwt_secret", state.credentials.token)
        assertEquals("amrt_plaintext_refresh_secret_12345", state.credentials.refreshToken)
    }

    @Test
    fun `credentials are strictly isolated per backend identity across server switches`() = runBlocking {
        val (_, _, configRepo, authRepo) = createRepositories(buildUrl = "https://server-a.com")

        val serverAIdentity = "https://server-a.com"
        val serverBIdentity = "https://server-b.com"

        authRepo.saveCredentialsForBackend(
            serverAIdentity,
            AuthCredentials(token = "token_A_123", username = "alice")
        )

        val stateA = authRepo.authState.first { it is BackendAuthState.Authenticated } as BackendAuthState.Authenticated
        assertEquals(serverAIdentity, stateA.backendIdentity)
        assertEquals("token_A_123", stateA.credentials.token)
        assertEquals("alice", stateA.credentials.username)

        configRepo.setCustomUrl(serverBIdentity)
        val stateBUnauth = authRepo.authState.first {
            it is BackendAuthState.Unauthenticated && it.backendIdentity == serverBIdentity
        }
        assertFalse(stateBUnauth.isAuthenticated)

        authRepo.saveCredentialsForBackend(
            serverBIdentity,
            AuthCredentials(token = "token_B_456", username = "bob")
        )
        val stateBAuth = authRepo.authState.first {
            it is BackendAuthState.Authenticated && it.backendIdentity == serverBIdentity
        } as BackendAuthState.Authenticated
        assertEquals("token_B_456", stateBAuth.credentials.token)
        assertEquals("bob", stateBAuth.credentials.username)

        configRepo.setMode(BackendMode.BUILD_DEFAULT)
        val stateBackToA = authRepo.authState.first {
            it is BackendAuthState.Authenticated && it.backendIdentity == serverAIdentity
        } as BackendAuthState.Authenticated
        assertEquals("token_A_123", stateBackToA.credentials.token)
        assertEquals("alice", stateBackToA.credentials.username)
        assertNotEquals(stateBackToA.credentials.token, stateBAuth.credentials.token)
    }

    @Test
    fun `switching to offline mode transitions auth state to Offline while preserving cloud session`() = runBlocking {
        val (_, _, configRepo, authRepo) = createRepositories(buildUrl = "https://server-a.com")

        authRepo.saveCredentialsForBackend(
            "https://server-a.com",
            AuthCredentials(token = "token_A", username = "alice")
        )

        assertTrue(authRepo.authState.first { it is BackendAuthState.Authenticated }.isAuthenticated)

        configRepo.enableLocalOnly()

        val stateOffline = authRepo.authState.first { it is BackendAuthState.Offline }
        assertTrue(stateOffline is BackendAuthState.Offline)
        assertFalse(stateOffline.isAuthenticated)

        configRepo.resetToDefault()
        val restored = authRepo.authState.first { it is BackendAuthState.Authenticated } as BackendAuthState.Authenticated
        assertEquals("alice", restored.credentials.username)
    }
}
