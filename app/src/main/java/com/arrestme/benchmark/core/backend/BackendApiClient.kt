package com.arrestme.benchmark.core.backend

import com.arrestme.benchmark.data.remote.ApiErrorMapper
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.AlwaysOnlineConnectivityChecker
import com.arrestme.benchmark.data.remote.NetworkConnectivityChecker
import com.arrestme.benchmark.data.remote.NetworkTimeoutConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Health check and lightweight connectivity probe client backed by Retrofit + OkHttp + Kotlinx Serialization.
 */
class BackendApiClient(
    private val apiClientFactory: ArrestApiClientFactory? = null,
    private val connectivityChecker: NetworkConnectivityChecker = AlwaysOnlineConnectivityChecker(),
    private val timeoutConfig: NetworkTimeoutConfig = NetworkTimeoutConfig(
        connectTimeoutMs = 6_000L,
        readTimeoutMs = 8_000L,
        callTimeoutMs = 12_000L,
        maxIdempotentRetries = 1
    )
) {
    private val standaloneClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutConfig.callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val converterFactory by lazy {
        ApiErrorMapper.networkJson.asConverterFactory("application/json; charset=UTF-8".toMediaType())
    }

    suspend fun checkHealth(baseUrl: String): HealthCheckResult = withContext(Dispatchers.IO) {
        val validation = BackendUrlValidator.validate(baseUrl)
        if (validation !is UrlValidationResult.Valid) {
            val reason = (validation as UrlValidationResult.Invalid).reason
            return@withContext HealthCheckResult.Error(reason)
        }

        if (!connectivityChecker.isOnline()) {
            return@withContext HealthCheckResult.Error(
                "Device is offline. Local Arrest Engine remains fully active."
            )
        }

        val api = apiClientFactory?.getOrCreateApi(validation.normalizedUrl)?.getOrNull()?.second
            ?: Retrofit.Builder()
                .baseUrl(BackendUrlValidator.toRetrofitBaseUrl(validation.normalizedUrl))
                .client(standaloneClient)
                .addConverterFactory(converterFactory)
                .build()
                .create(com.arrestme.benchmark.data.remote.ArrestCloudApi::class.java)

        val startTime = System.currentTimeMillis()
        try {
            val v1Resp = api.getVersionedHealth()
            val finalResp = if (v1Resp.isSuccessful) {
                v1Resp
            } else if (v1Resp.code() == 404) {
                api.getRootHealth()
            } else {
                v1Resp
            }
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)

            if (finalResp.isSuccessful) {
                val body = finalResp.body()
                val statusLabel = body?.status ?: "ok"
                val versionSuffix = body?.version?.let { " (v$it)" }.orEmpty()
                HealthCheckResult.Success(
                    latencyMs = latency,
                    message = "Server reachable (${latency}ms) — status: $statusLabel$versionSuffix",
                    serviceVersion = body?.version
                )
            } else {
                val parsed = ApiErrorMapper.parseErrorBody(
                    runCatching { finalResp.errorBody()?.string() }.getOrNull()
                )
                HealthCheckResult.Error(
                    "HTTP ${finalResp.code()}: ${parsed.message ?: "Unexpected server response"}"
                )
            }
        } catch (t: Throwable) {
            val mapped = ApiErrorMapper.mapThrowable(t)
            HealthCheckResult.Error("Connection failed: ${mapped.message}")
        }
    }
}
