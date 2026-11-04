package com.arrestme.benchmark.core.backend

import kotlinx.serialization.Serializable
import java.time.Instant

enum class BackendMode {
    OFFLINE_LOCAL,
    BUILD_DEFAULT,
    CUSTOM_SELF_HOSTED
}

data class ActiveBackendConfig(
    val mode: BackendMode,
    val effectiveBaseUrl: String?,
    val backendIdentity: String,
    val customUrl: String,
    val buildConfigUrl: String
) {
    val isLocalOnly: Boolean
        get() = effectiveBaseUrl.isNullOrBlank()

    val displayName: String
        get() = when (mode) {
            BackendMode.OFFLINE_LOCAL -> "Local-Only (Offline)"
            BackendMode.BUILD_DEFAULT -> if (effectiveBaseUrl != null) {
                "Official Server ($effectiveBaseUrl)"
            } else {
                "Local-Only (No Official URL Configured)"
            }
            BackendMode.CUSTOM_SELF_HOSTED -> if (effectiveBaseUrl != null) {
                "Self-Hosted ($effectiveBaseUrl)"
            } else {
                "Self-Hosted (Not Configured)"
            }
        }
}

@Serializable
data class RegisteredDeviceInfo(
    val id: String,
    val installationId: String,
    val deviceName: String,
    val platform: String = "ANDROID",
    val manufacturer: String? = null,
    val model: String? = null,
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int? = null,
    val deviceStatus: String = "ACTIVE",
    val lastSeenAt: String? = null,
    val registeredAtMs: Long = 0L
)

data class AuthCredentials(
    val token: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAtMs: Long = 0L,
    val refreshTokenExpiresAtMs: Long = 0L,
    val userId: String? = null,
    val username: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val accountStatus: String? = null,
    val registeredDevice: RegisteredDeviceInfo? = null,
    val lastSyncAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
) {
    val isValid: Boolean
        get() = !token.isNullOrBlank()

    fun isAccessTokenExpired(
        nowMs: Long = System.currentTimeMillis(),
        skewMs: Long = 30_000L
    ): Boolean {
        if (token.isNullOrBlank()) return true
        if (accessTokenExpiresAtMs <= 0L) return false
        return nowMs + skewMs >= accessTokenExpiresAtMs
    }

    fun isRefreshTokenExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (refreshToken.isNullOrBlank()) return true
        if (refreshTokenExpiresAtMs <= 0L) return false
        return nowMs >= refreshTokenExpiresAtMs
    }

    companion object {
        fun parseIsoEpochMs(isoTimestamp: String?): Long {
            if (isoTimestamp.isNullOrBlank()) return 0L
            return runCatching { Instant.parse(isoTimestamp).toEpochMilli() }.getOrDefault(0L)
        }
    }
}

sealed class BackendAuthState {
    data object Offline : BackendAuthState()
    data class Unauthenticated(
        val backendIdentity: String,
        val sessionExpiredReason: String? = null
    ) : BackendAuthState()
    data class Authenticated(
        val backendIdentity: String,
        val credentials: AuthCredentials
    ) : BackendAuthState()

    val isAuthenticated: Boolean
        get() = this is Authenticated && credentials.isValid
}

sealed class HealthCheckResult {
    data class Success(
        val latencyMs: Long,
        val message: String,
        val serviceVersion: String? = null
    ) : HealthCheckResult()
    data class Error(val error: String) : HealthCheckResult()
}

data class ValidationIssue(
    val path: String,
    val message: String
)

sealed class CloudError {
    abstract val message: String
    open val code: String? = null

    data class Offline(
        override val message: String = "Device is offline. Local Arrest Engine remains fully active."
    ) : CloudError() {
        override val code: String = "OFFLINE"
    }

    data class NetworkFailure(
        override val message: String,
        val isTimeout: Boolean = false
    ) : CloudError() {
        override val code: String = if (isTimeout) "TIMEOUT" else "NETWORK_FAILURE"
    }

    data class Unauthorized(
        override val code: String = "UNAUTHORIZED",
        override val message: String,
        val sessionExpired: Boolean = false
    ) : CloudError()

    data class Validation(
        override val code: String = "VALIDATION_ERROR",
        override val message: String,
        val issues: List<ValidationIssue> = emptyList()
    ) : CloudError()

    data class Conflict(
        override val code: String,
        override val message: String
    ) : CloudError()

    data class Forbidden(
        override val code: String = "FORBIDDEN",
        override val message: String
    ) : CloudError()

    data class NotFound(
        override val code: String = "NOT_FOUND",
        override val message: String
    ) : CloudError()

    data class RateLimited(
        override val code: String = "RATE_LIMIT_EXCEEDED",
        override val message: String
    ) : CloudError()

    data class Server(
        val httpStatus: Int,
        override val code: String = "INTERNAL_SERVER_ERROR",
        override val message: String
    ) : CloudError()

    data class Configuration(
        override val message: String
    ) : CloudError() {
        override val code: String = "BACKEND_NOT_CONFIGURED"
    }
}

sealed class CloudResult<out T> {
    data class Success<T>(val data: T) : CloudResult<T>()
    data class Failure(val error: CloudError) : CloudResult<Nothing>()

    val isSuccess: Boolean get() = this is Success<T>
    fun getOrNull(): T? = (this as? Success<T>)?.data
    fun errorOrNull(): CloudError? = (this as? Failure)?.error
}
