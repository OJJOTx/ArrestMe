package com.arrestme.benchmark.data.remote

import com.arrestme.benchmark.BuildConfig
import com.arrestme.benchmark.core.backend.AuthCredentials
import com.arrestme.benchmark.core.backend.BackendPreferencesDataSource
import com.arrestme.benchmark.data.remote.dto.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal object AuthPathClassifier {
    fun isPublicEndpoint(encodedPath: String): Boolean {
        val lower = encodedPath.lowercase().trimEnd('/')
        return lower.endsWith("/health") ||
            lower.endsWith("/api/v1/health") ||
            lower.endsWith("/api/v1/auth/register") ||
            lower.endsWith("/api/v1/auth/login") ||
            lower.endsWith("/api/v1/auth/refresh") ||
            lower.endsWith("/api/v1/auth/logout")
    }
}

class OfflinePrecheckInterceptor(
    private val connectivityChecker: NetworkConnectivityChecker
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!connectivityChecker.isOnline()) {
            throw OfflineIoException()
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Coordinates single-flight refresh token rotation per backend identity so concurrent 401s
 * never replay a rotated refresh token (which would trigger Fastify's REFRESH_TOKEN_REUSED family revocation).
 */
class TokenRefreshCoordinator(
    private val preferencesDataSource: BackendPreferencesDataSource
) {
    private val mutexByBackend = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(backendIdentity: String): Mutex {
        val key = backendIdentity.trim().lowercase()
        return mutexByBackend.computeIfAbsent(key) { Mutex() }
    }

    suspend fun refreshAccessToken(
        backendIdentity: String,
        refreshApi: ArrestCloudApi,
        failedAccessToken: String?,
        forceProactive: Boolean = false
    ): String? {
        val identity = backendIdentity.trim().lowercase()
        return mutexFor(identity).withLock {
            val stored = preferencesDataSource.readAuthCredentialsSnapshot(identity)

            // If another in-flight request already refreshed the token, reuse the fresh token immediately.
            if (!forceProactive &&
                !stored.token.isNullOrBlank() &&
                stored.token != failedAccessToken &&
                !stored.isAccessTokenExpired()
            ) {
                return@withLock stored.token
            }

            if (forceProactive && !stored.token.isNullOrBlank() && !stored.isAccessTokenExpired()) {
                return@withLock stored.token
            }

            val currentRefreshToken = stored.refreshToken
            if (currentRefreshToken.isNullOrBlank() || stored.isRefreshTokenExpired()) {
                preferencesDataSource.clearAuthCredentials(
                    identity = identity,
                    expiredReason = "Session expired. Please sign in again."
                )
                return@withLock null
            }

            val refreshResponse = try {
                refreshApi.refresh(RefreshRequestDto(refreshToken = currentRefreshToken))
            } catch (_: IOException) {
                // Network failure during refresh: preserve stored refresh token for when connectivity returns.
                return@withLock null
            } catch (_: Exception) {
                return@withLock null
            }

            if (refreshResponse.isSuccessful) {
                val tokens = refreshResponse.body()?.tokens ?: return@withLock null
                val now = System.currentTimeMillis()
                val updated = stored.copy(
                    token = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    accessTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(tokens.accessTokenExpiresAt),
                    refreshTokenExpiresAtMs = AuthCredentials.parseIsoEpochMs(tokens.refreshTokenExpiresAt),
                    updatedAtMs = now
                )
                preferencesDataSource.saveAuthCredentials(identity, updated)
                return@withLock tokens.accessToken
            }

            val status = refreshResponse.code()
            if (status == 401 || status == 403) {
                val parsed = ApiErrorMapper.parseErrorBody(
                    runCatching { refreshResponse.errorBody()?.string() }.getOrNull()
                )
                val reason = when (parsed.code) {
                    "REFRESH_TOKEN_REUSED" -> "Session invalidated for security (token reuse detected). Please sign in again."
                    "REFRESH_TOKEN_REVOKED" -> "Session has been signed out or revoked. Please sign in again."
                    "ACCOUNT_SUSPENDED" -> "This account has been suspended."
                    else -> "Session expired. Please sign in again."
                }
                preferencesDataSource.clearAuthCredentials(identity = identity, expiredReason = reason)
            }
            null
        }
    }
}

class AuthHeaderInterceptor(
    private val backendIdentity: String,
    private val preferencesDataSource: BackendPreferencesDataSource,
    private val tokenRefreshCoordinator: TokenRefreshCoordinator,
    private val refreshApiProvider: () -> ArrestCloudApi
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "ArrestMe-Android/${BuildConfig.VERSION_NAME}")

        if (original.header("X-Request-Id").isNullOrBlank()) {
            builder.header("X-Request-Id", UUID.randomUUID().toString())
        }

        val path = original.url.encodedPath
        if (!AuthPathClassifier.isPublicEndpoint(path) && original.header("Authorization").isNullOrBlank()) {
            val token = runBlocking {
                val creds = preferencesDataSource.readAuthCredentialsSnapshot(backendIdentity)
                if (creds.token.isNullOrBlank()) {
                    null
                } else if (creds.isAccessTokenExpired() && !creds.refreshToken.isNullOrBlank()) {
                    tokenRefreshCoordinator.refreshAccessToken(
                        backendIdentity = backendIdentity,
                        refreshApi = refreshApiProvider(),
                        failedAccessToken = creds.token,
                        forceProactive = true
                    ) ?: creds.token
                } else {
                    creds.token
                }
            }
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
        }

        return chain.proceed(builder.build())
    }
}

class TokenRefreshAuthenticator(
    private val backendIdentity: String,
    private val tokenRefreshCoordinator: TokenRefreshCoordinator,
    private val refreshApiProvider: () -> ArrestCloudApi
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        if (AuthPathClassifier.isPublicEndpoint(path)) {
            return null
        }
        if (responseCount(response) >= 2) {
            return null
        }

        val failedAuthHeader = response.request.header("Authorization")
        val failedToken = failedAuthHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(" ")
            ?.trim()

        val refreshedToken = runBlocking {
            tokenRefreshCoordinator.refreshAccessToken(
                backendIdentity = backendIdentity,
                refreshApi = refreshApiProvider(),
                failedAccessToken = failedToken,
                forceProactive = false
            )
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

/**
 * Retries strictly idempotent requests (GET/HEAD/OPTIONS/PUT/DELETE or requests carrying an explicit
 * Idempotency-Key header) on transient I/O errors or 502/503/504 gateway failures.
 * Non-idempotent mutations (login, register, token refresh) are never retried automatically.
 */
class IdempotentRetryInterceptor(
    private val maxRetries: Int = 2,
    private val initialBackoffMs: Long = 120L
) : Interceptor {

    companion object {
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "PUT", "DELETE")
        private val RETRYABLE_GATEWAY_CODES = setOf(502, 503, 504)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isSafeIdempotentRequest(request)) {
            return chain.proceed(request)
        }

        var attempt = 0
        var lastException: IOException? = null

        while (true) {
            if (chain.call().isCanceled()) {
                throw IOException("Canceled")
            }

            try {
                val response = chain.proceed(request)
                if (response.code in RETRYABLE_GATEWAY_CODES && attempt < maxRetries) {
                    response.close()
                    attempt++
                    sleepBackoff(attempt)
                    continue
                }
                return response
            } catch (e: IOException) {
                if (!isRetryableIoException(e) || attempt >= maxRetries || chain.call().isCanceled()) {
                    throw e
                }
                lastException = e
                attempt++
                sleepBackoff(attempt)
            }
        }
    }

    private fun isSafeIdempotentRequest(request: Request): Boolean {
        val path = request.url.encodedPath.lowercase()
        if (path.endsWith("/api/v1/auth/refresh")) return false
        if (request.method.uppercase() in IDEMPOTENT_METHODS) return true
        return !request.header("Idempotency-Key").isNullOrBlank()
    }

    private fun isRetryableIoException(e: IOException): Boolean {
        if (e is OfflineIoException) return false
        if (e is SSLHandshakeException || e is SSLPeerUnverifiedException) return false
        return true
    }

    private fun sleepBackoff(attempt: Int) {
        val delayMs = initialBackoffMs * attempt
        if (delayMs > 0L) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
