package com.arrestme.benchmark.core.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendConfigRepositoryTest {

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

    private fun createRepository(buildConfigUrl: String): BackendConfigRepository {
        val testFile = tmpFolder.newFile("test_backend_prefs_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = ioScope,
            produceFile = { testFile }
        )
        val dataSource = BackendPreferencesDataSource(dataStore)
        return BackendConfigRepository(
            preferencesDataSource = dataSource,
            buildConfigUrl = buildConfigUrl
        )
    }

    @Test
    fun `missing configuration defaults to local-only mode`() = runBlocking {
        val repo = createRepository(buildConfigUrl = "")
        val config = repo.activeConfig.first()

        assertEquals(BackendMode.OFFLINE_LOCAL, config.mode)
        assertNull(config.effectiveBaseUrl)
        assertEquals("offline", config.backendIdentity)
        assertTrue(config.isLocalOnly)
    }

    @Test
    fun `valid build config URL defaults to build default mode`() = runBlocking {
        val repo = createRepository(buildConfigUrl = "https://api.arrestme.org")
        val config = repo.activeConfig.first()

        assertEquals(BackendMode.BUILD_DEFAULT, config.mode)
        assertEquals("https://api.arrestme.org", config.effectiveBaseUrl)
        assertEquals("https://api.arrestme.org", config.backendIdentity)
        assertFalse(config.isLocalOnly)
    }

    @Test
    fun `invalid build config URL safely falls back to local-only mode`() = runBlocking {
        val repo = createRepository(buildConfigUrl = "http://insecure.remote.com")
        val config = repo.activeConfig.first()

        assertNull(config.effectiveBaseUrl)
        assertEquals("offline", config.backendIdentity)
        assertTrue(config.isLocalOnly)
    }

    @Test
    fun `configuring custom self-hosted URL updates active config`() = runBlocking {
        val repo = createRepository(buildConfigUrl = "")
        val result = repo.setCustomUrl("https://my-backend.custom.org")

        assertTrue(result is UrlValidationResult.Valid)
        val config = repo.activeConfig.first()

        assertEquals(BackendMode.CUSTOM_SELF_HOSTED, config.mode)
        assertEquals("https://my-backend.custom.org", config.effectiveBaseUrl)
        assertEquals("https://my-backend.custom.org", config.backendIdentity)
        assertFalse(config.isLocalOnly)
    }

    @Test
    fun `switching to local-only mode overrides build configuration`() = runBlocking {
        val repo = createRepository(buildConfigUrl = "https://api.arrestme.org")

        var config = repo.activeConfig.first()
        assertEquals("https://api.arrestme.org", config.effectiveBaseUrl)

        repo.enableLocalOnly()
        config = repo.activeConfig.first()

        assertEquals(BackendMode.OFFLINE_LOCAL, config.mode)
        assertNull(config.effectiveBaseUrl)
        assertEquals("offline", config.backendIdentity)
        assertTrue(config.isLocalOnly)

        repo.resetToDefault()
        config = repo.activeConfig.first()
        assertEquals(BackendMode.BUILD_DEFAULT, config.mode)
        assertEquals("https://api.arrestme.org", config.effectiveBaseUrl)
    }
}
