package com.arrestme.benchmark.core.backend

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

val Context.backendDataStore: DataStore<Preferences> by preferencesDataStore(name = "arrestme_backend_prefs")

class BackendPreferencesDataSource(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher = InMemoryAesGcmSecretCipher()
) {
    private val installationIdMutex = Mutex()

    companion object {
        val KEY_BACKEND_MODE = stringPreferencesKey("backend_mode")
        val KEY_CUSTOM_URL = stringPreferencesKey("custom_url")
        val KEY_ACTIVE_IDENTITY = stringPreferencesKey("active_identity")
        val KEY_INSTALLATION_ID = stringPreferencesKey("installation_id")

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun sanitizeIdentity(identity: String): String {
            val normalized = identity.trim().lowercase()
            val readable = normalized.replace(Regex("[^a-z0-9_]"), "_").take(40)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
            return "${readable}_$digest"
        }

        fun encryptedTokenKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_enc_token")

        fun encryptedRefreshTokenKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_enc_refresh_token")

        fun accessExpiresAtKey(identity: String) =
            longPreferencesKey("auth_${sanitizeIdentity(identity)}_access_exp_ms")

        fun refreshExpiresAtKey(identity: String) =
            longPreferencesKey("auth_${sanitizeIdentity(identity)}_refresh_exp_ms")

        fun userIdKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_user_id")

        fun usernameKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_username")

        fun emailKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_email")

        fun displayNameKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_display_name")

        fun avatarUrlKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_avatar_url")

        fun accountStatusKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_account_status")

        fun registeredDeviceKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_device_json")

        fun lastSyncAtKey(identity: String) =
            longPreferencesKey("auth_${sanitizeIdentity(identity)}_last_sync_ms")

        fun sessionExpiredReasonKey(identity: String) =
            stringPreferencesKey("auth_${sanitizeIdentity(identity)}_expired_reason")

        fun updatedAtKey(identity: String) =
            longPreferencesKey("auth_${sanitizeIdentity(identity)}_updated_at")
    }

    val modeFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_BACKEND_MODE]
    }

    val customUrlFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_URL]
    }

    val activeIdentityFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_IDENTITY]
    }

    suspend fun getOrCreateInstallationId(): String = installationIdMutex.withLock {
        val current = dataStore.data.first()[KEY_INSTALLATION_ID]
        if (!current.isNullOrBlank() && current.length >= 8) {
            return@withLock current
        }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            val existing = prefs[KEY_INSTALLATION_ID]
            if (existing.isNullOrBlank() || existing.length < 8) {
                prefs[KEY_INSTALLATION_ID] = generated
            }
        }
        dataStore.data.first()[KEY_INSTALLATION_ID] ?: generated
    }

    suspend fun saveBackendMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_BACKEND_MODE] = mode
        }
    }

    suspend fun saveCustomUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_URL] = url
        }
    }

    suspend fun saveActiveIdentity(identity: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_IDENTITY] = identity
        }
    }

    fun observeAuthCredentials(identity: String): Flow<AuthCredentials> {
        return dataStore.data.map { prefs ->
            readCredentialsFromPrefs(prefs, identity)
        }
    }

    fun observeSessionExpiredReason(identity: String): Flow<String?> {
        val expReasonKey = sessionExpiredReasonKey(identity)
        return dataStore.data.map { prefs -> prefs[expReasonKey] }
    }

    suspend fun readAuthCredentialsSnapshot(identity: String): AuthCredentials {
        val prefs = dataStore.data.first()
        return readCredentialsFromPrefs(prefs, identity)
    }

    private fun readCredentialsFromPrefs(prefs: Preferences, identity: String): AuthCredentials {
        val encToken = prefs[encryptedTokenKey(identity)]
        val encRefresh = prefs[encryptedRefreshTokenKey(identity)]
        val decryptedToken = encToken?.let { secretCipher.decrypt(it) }
        val decryptedRefresh = encRefresh?.let { secretCipher.decrypt(it) }

        val deviceJson = prefs[registeredDeviceKey(identity)]
        val parsedDevice = deviceJson?.let {
            runCatching { json.decodeFromString<RegisteredDeviceInfo>(it) }.getOrNull()
        }

        return AuthCredentials(
            token = decryptedToken,
            refreshToken = decryptedRefresh,
            accessTokenExpiresAtMs = prefs[accessExpiresAtKey(identity)] ?: 0L,
            refreshTokenExpiresAtMs = prefs[refreshExpiresAtKey(identity)] ?: 0L,
            userId = prefs[userIdKey(identity)],
            username = prefs[usernameKey(identity)],
            email = prefs[emailKey(identity)],
            displayName = prefs[displayNameKey(identity)],
            avatarUrl = prefs[avatarUrlKey(identity)],
            accountStatus = prefs[accountStatusKey(identity)],
            registeredDevice = parsedDevice,
            lastSyncAtMs = prefs[lastSyncAtKey(identity)] ?: 0L,
            updatedAtMs = prefs[updatedAtKey(identity)] ?: 0L
        )
    }

    suspend fun saveAuthCredentials(identity: String, credentials: AuthCredentials) {
        val tKey = encryptedTokenKey(identity)
        val rKey = encryptedRefreshTokenKey(identity)
        val aExpKey = accessExpiresAtKey(identity)
        val rExpKey = refreshExpiresAtKey(identity)
        val uKey = userIdKey(identity)
        val unKey = usernameKey(identity)
        val emKey = emailKey(identity)
        val dnKey = displayNameKey(identity)
        val avKey = avatarUrlKey(identity)
        val stKey = accountStatusKey(identity)
        val devKey = registeredDeviceKey(identity)
        val syncKey = lastSyncAtKey(identity)
        val expReasonKey = sessionExpiredReasonKey(identity)
        val upKey = updatedAtKey(identity)

        val encryptedAccess = credentials.token?.takeIf { it.isNotBlank() }?.let {
            secretCipher.encrypt(it)
        }
        val encryptedRefresh = credentials.refreshToken?.takeIf { it.isNotBlank() }?.let {
            secretCipher.encrypt(it)
        }
        val serializedDevice = credentials.registeredDevice?.let {
            json.encodeToString(it)
        }

        dataStore.edit { prefs ->
            if (encryptedAccess != null) prefs[tKey] = encryptedAccess else prefs.remove(tKey)
            if (encryptedRefresh != null) prefs[rKey] = encryptedRefresh else prefs.remove(rKey)
            prefs[aExpKey] = credentials.accessTokenExpiresAtMs
            prefs[rExpKey] = credentials.refreshTokenExpiresAtMs
            if (credentials.userId != null) prefs[uKey] = credentials.userId else prefs.remove(uKey)
            if (credentials.username != null) prefs[unKey] = credentials.username else prefs.remove(unKey)
            if (credentials.email != null) prefs[emKey] = credentials.email else prefs.remove(emKey)
            if (credentials.displayName != null) prefs[dnKey] = credentials.displayName else prefs.remove(dnKey)
            if (credentials.avatarUrl != null) prefs[avKey] = credentials.avatarUrl else prefs.remove(avKey)
            if (credentials.accountStatus != null) prefs[stKey] = credentials.accountStatus else prefs.remove(stKey)
            if (serializedDevice != null) prefs[devKey] = serializedDevice else prefs.remove(devKey)
            prefs[syncKey] = credentials.lastSyncAtMs
            prefs[upKey] = if (credentials.updatedAtMs > 0L) credentials.updatedAtMs else System.currentTimeMillis()
            if (encryptedAccess != null) {
                prefs.remove(expReasonKey)
            }
        }
    }

    suspend fun clearAuthCredentials(
        identity: String,
        expiredReason: String? = null
    ) {
        dataStore.edit { prefs ->
            prefs.remove(encryptedTokenKey(identity))
            prefs.remove(encryptedRefreshTokenKey(identity))
            prefs.remove(accessExpiresAtKey(identity))
            prefs.remove(refreshExpiresAtKey(identity))
            prefs.remove(userIdKey(identity))
            prefs.remove(usernameKey(identity))
            prefs.remove(emailKey(identity))
            prefs.remove(displayNameKey(identity))
            prefs.remove(avatarUrlKey(identity))
            prefs.remove(accountStatusKey(identity))
            prefs.remove(registeredDeviceKey(identity))
            prefs.remove(lastSyncAtKey(identity))
            prefs.remove(updatedAtKey(identity))
            val expReasonKey = sessionExpiredReasonKey(identity)
            if (!expiredReason.isNullOrBlank()) {
                prefs[expReasonKey] = expiredReason
            } else {
                prefs.remove(expReasonKey)
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
