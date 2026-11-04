package com.arrestme.benchmark.core.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class BackendConfigRepository(
    private val preferencesDataSource: BackendPreferencesDataSource,
    val buildConfigUrl: String
) {
    val activeConfig: Flow<ActiveBackendConfig> = combine(
        preferencesDataSource.modeFlow,
        preferencesDataSource.customUrlFlow
    ) { storedModeStr, storedCustomUrl ->
        val customUrl = storedCustomUrl.orEmpty().trim()
        val storedMode = storedModeStr?.let {
            try {
                BackendMode.valueOf(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        // Determine effective mode:
        // If user explicitly chose a mode, respect it.
        // If not set yet: if buildConfigUrl is configured and valid, use BUILD_DEFAULT, otherwise OFFLINE_LOCAL.
        val effectiveMode = when {
            storedMode != null -> storedMode
            buildConfigUrl.isNotBlank() && BackendUrlValidator.validate(buildConfigUrl) is UrlValidationResult.Valid -> BackendMode.BUILD_DEFAULT
            else -> BackendMode.OFFLINE_LOCAL
        }

        when (effectiveMode) {
            BackendMode.OFFLINE_LOCAL -> {
                ActiveBackendConfig(
                    mode = BackendMode.OFFLINE_LOCAL,
                    effectiveBaseUrl = null,
                    backendIdentity = "offline",
                    customUrl = customUrl,
                    buildConfigUrl = buildConfigUrl
                )
            }
            BackendMode.BUILD_DEFAULT -> {
                val validation = BackendUrlValidator.validate(buildConfigUrl)
                if (validation is UrlValidationResult.Valid) {
                    ActiveBackendConfig(
                        mode = BackendMode.BUILD_DEFAULT,
                        effectiveBaseUrl = validation.normalizedUrl,
                        backendIdentity = validation.normalizedUrl,
                        customUrl = customUrl,
                        buildConfigUrl = buildConfigUrl
                    )
                } else {
                    // Fall back to offline mode gracefully if build URL is missing or invalid
                    ActiveBackendConfig(
                        mode = BackendMode.BUILD_DEFAULT,
                        effectiveBaseUrl = null,
                        backendIdentity = "offline",
                        customUrl = customUrl,
                        buildConfigUrl = buildConfigUrl
                    )
                }
            }
            BackendMode.CUSTOM_SELF_HOSTED -> {
                val validation = BackendUrlValidator.validate(customUrl)
                if (validation is UrlValidationResult.Valid) {
                    ActiveBackendConfig(
                        mode = BackendMode.CUSTOM_SELF_HOSTED,
                        effectiveBaseUrl = validation.normalizedUrl,
                        backendIdentity = validation.normalizedUrl,
                        customUrl = customUrl,
                        buildConfigUrl = buildConfigUrl
                    )
                } else {
                    // Custom URL is invalid or empty; fall back to local-only effective state
                    ActiveBackendConfig(
                        mode = BackendMode.CUSTOM_SELF_HOSTED,
                        effectiveBaseUrl = null,
                        backendIdentity = "offline",
                        customUrl = customUrl,
                        buildConfigUrl = buildConfigUrl
                    )
                }
            }
        }
    }

    suspend fun setMode(mode: BackendMode) {
        preferencesDataSource.saveBackendMode(mode.name)
    }

    suspend fun setCustomUrl(rawUrl: String): UrlValidationResult {
        val validation = BackendUrlValidator.validate(rawUrl)
        if (validation is UrlValidationResult.Valid) {
            preferencesDataSource.saveCustomUrl(validation.normalizedUrl)
            preferencesDataSource.saveBackendMode(BackendMode.CUSTOM_SELF_HOSTED.name)
        }
        return validation
    }

    suspend fun resetToDefault() {
        preferencesDataSource.saveBackendMode(BackendMode.BUILD_DEFAULT.name)
    }

    suspend fun enableLocalOnly() {
        preferencesDataSource.saveBackendMode(BackendMode.OFFLINE_LOCAL.name)
    }
}
