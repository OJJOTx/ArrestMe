package com.arrestme.benchmark.data.remote

import com.arrestme.benchmark.core.backend.BackendPreferencesDataSource
import com.arrestme.benchmark.core.backend.BackendUrlValidator
import com.arrestme.benchmark.core.backend.UrlValidationResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class NetworkTimeoutConfig(
    val connectTimeoutMs: Long = 10_000L,
    val readTimeoutMs: Long = 15_000L,
    val writeTimeoutMs: Long = 15_000L,
    val callTimeoutMs: Long = 25_000L,
    val maxIdempotentRetries: Int = 2,
    val retryBackoffMs: Long = 120L
)

/**
 * Builds and caches isolated Retrofit/OkHttp clients per normalized backend identity.
 * Ensures credentials, token refresh locks, and base URLs never bleed across servers.
 */
class ArrestApiClientFactory(
    private val preferencesDataSource: BackendPreferencesDataSource,
    private val connectivityChecker: NetworkConnectivityChecker = AlwaysOnlineConnectivityChecker(),
    private val timeoutConfig: NetworkTimeoutConfig = NetworkTimeoutConfig()
) {
    private val tokenRefreshCoordinator = TokenRefreshCoordinator(preferencesDataSource)
    private val clientsByBaseUrl = ConcurrentHashMap<String, ArrestCloudApi>()

    private val sharedPoolClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutConfig.callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // Handled explicitly and safely by IdempotentRetryInterceptor
            .build()
    }

    private val converterFactory by lazy {
        ApiErrorMapper.networkJson.asConverterFactory("application/json; charset=UTF-8".toMediaType())
    }

    fun getOrCreateApi(rawOrNormalizedBaseUrl: String): Result<Pair<String, ArrestCloudApi>> {
        val validation = BackendUrlValidator.validate(rawOrNormalizedBaseUrl)
        if (validation !is UrlValidationResult.Valid) {
            val reason = (validation as UrlValidationResult.Invalid).reason
            return Result.failure(IllegalArgumentException(reason))
        }

        val normalizedIdentity = validation.normalizedUrl
        val api = clientsByBaseUrl.computeIfAbsent(normalizedIdentity) { baseUrl ->
            buildAuthenticatedApiForIdentity(baseUrl)
        }
        return Result.success(normalizedIdentity to api)
    }

    private fun buildAuthenticatedApiForIdentity(normalizedIdentity: String): ArrestCloudApi {
        val retrofitBaseUrl = BackendUrlValidator.toRetrofitBaseUrl(normalizedIdentity)

        // Bare client exclusively for /api/v1/auth/refresh (no Authenticator, no Bearer header, no retries)
        val bareRefreshClient = sharedPoolClient.newBuilder()
            .addInterceptor(OfflinePrecheckInterceptor(connectivityChecker))
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()

        val refreshApi = Retrofit.Builder()
            .baseUrl(retrofitBaseUrl)
            .client(bareRefreshClient)
            .addConverterFactory(converterFactory)
            .build()
            .create(ArrestCloudApi::class.java)

        val authedClient = sharedPoolClient.newBuilder()
            .addInterceptor(OfflinePrecheckInterceptor(connectivityChecker))
            .addInterceptor(
                AuthHeaderInterceptor(
                    backendIdentity = normalizedIdentity,
                    preferencesDataSource = preferencesDataSource,
                    tokenRefreshCoordinator = tokenRefreshCoordinator,
                    refreshApiProvider = { refreshApi }
                )
            )
            .addInterceptor(
                IdempotentRetryInterceptor(
                    maxRetries = timeoutConfig.maxIdempotentRetries,
                    initialBackoffMs = timeoutConfig.retryBackoffMs
                )
            )
            .authenticator(
                TokenRefreshAuthenticator(
                    backendIdentity = normalizedIdentity,
                    tokenRefreshCoordinator = tokenRefreshCoordinator,
                    refreshApiProvider = { refreshApi }
                )
            )
            .build()

        return Retrofit.Builder()
            .baseUrl(retrofitBaseUrl)
            .client(authedClient)
            .addConverterFactory(converterFactory)
            .build()
            .create(ArrestCloudApi::class.java)
    }
}
