package com.arrestme.benchmark.data.remote

import com.arrestme.benchmark.core.backend.CloudError
import com.arrestme.benchmark.core.backend.CloudResult
import com.arrestme.benchmark.core.backend.ValidationIssue
import com.arrestme.benchmark.data.remote.dto.ApiErrorEnvelopeDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ApiErrorMapper {

    val networkJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
    }

    data class ParsedApiError(
        val code: String?,
        val message: String?,
        val issues: List<ValidationIssue> = emptyList()
    )

    fun parseErrorBody(rawBody: String?): ParsedApiError {
        if (rawBody.isNullOrBlank()) {
            return ParsedApiError(code = null, message = null)
        }
        return runCatching {
            val envelope = networkJson.decodeFromString<ApiErrorEnvelopeDto>(rawBody)
            val issuesList = mutableListOf<ValidationIssue>()
            val detailsObj = envelope.error.details
            val rawIssues = detailsObj?.get("issues") as? JsonArray
            rawIssues?.forEach { elem ->
                val obj = elem as? JsonObject ?: return@forEach
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "/"
                val msg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid value"
                issuesList.add(ValidationIssue(path = path, message = msg))
            }
            ParsedApiError(
                code = envelope.error.code,
                message = envelope.error.message,
                issues = issuesList
            )
        }.getOrElse {
            ParsedApiError(code = null, message = rawBody.take(180))
        }
    }

    fun <T, R> mapResponse(
        response: Response<T>,
        sessionWasClearedOnAuthFailure: Boolean = false,
        transform: (T) -> R
    ): CloudResult<R> {
        if (response.isSuccessful) {
            val body = response.body()
            return if (body != null) {
                CloudResult.Success(transform(body))
            } else {
                CloudResult.Failure(
                    CloudError.Server(
                        httpStatus = response.code(),
                        code = "EMPTY_RESPONSE",
                        message = "Server returned HTTP ${response.code()} with an empty response body."
                    )
                )
            }
        }

        val rawErr = runCatching { response.errorBody()?.string() }.getOrNull()
        val parsed = parseErrorBody(rawErr)
        val status = response.code()
        val code = parsed.code
        val message = parsed.message ?: "HTTP $status request failed."

        val cloudError = when (status) {
            400, 422 -> {
                val formattedMsg = if (parsed.issues.isNotEmpty()) {
                    val detailSummary = parsed.issues.joinToString("; ") { issue ->
                        val cleanField = issue.path.trimStart('/').ifBlank { "field" }
                        "$cleanField: ${issue.message}"
                    }
                    "$message ($detailSummary)"
                } else {
                    message
                }
                CloudError.Validation(
                    code = code ?: "VALIDATION_ERROR",
                    message = formattedMsg,
                    issues = parsed.issues
                )
            }
            401 -> CloudError.Unauthorized(
                code = code ?: "UNAUTHORIZED",
                message = message,
                sessionExpired = sessionWasClearedOnAuthFailure ||
                    code in setOf(
                        "REFRESH_TOKEN_EXPIRED",
                        "REFRESH_TOKEN_REVOKED",
                        "REFRESH_TOKEN_REUSED",
                        "INVALID_REFRESH_TOKEN"
                    )
            )
            403 -> CloudError.Forbidden(
                code = code ?: "FORBIDDEN",
                message = message
            )
            404 -> CloudError.NotFound(
                code = code ?: "NOT_FOUND",
                message = message
            )
            409 -> CloudError.Conflict(
                code = code ?: "CONFLICT",
                message = message
            )
            429 -> CloudError.RateLimited(
                code = code ?: "RATE_LIMIT_EXCEEDED",
                message = message
            )
            else -> CloudError.Server(
                httpStatus = status,
                code = code ?: "INTERNAL_SERVER_ERROR",
                message = message
            )
        }
        return CloudResult.Failure(cloudError)
    }

    fun mapThrowable(t: Throwable): CloudError {
        return when (t) {
            is OfflineIoException -> CloudError.Offline(
                message = t.message ?: "Device is offline. Local Arrest Engine remains fully active."
            )
            is SocketTimeoutException, is InterruptedIOException -> CloudError.NetworkFailure(
                message = "Request timed out while contacting the server.",
                isTimeout = true
            )
            is UnknownHostException -> CloudError.NetworkFailure(
                message = "Unable to resolve server address. Check your internet connection or server URL."
            )
            is ConnectException -> CloudError.NetworkFailure(
                message = "Unable to connect to the server. Verify the server is running and reachable."
            )
            is IOException -> CloudError.NetworkFailure(
                message = "Network error: ${t.message ?: t.javaClass.simpleName}"
            )
            else -> CloudError.Server(
                httpStatus = 0,
                code = "CLIENT_ERROR",
                message = t.message ?: "Unexpected client error (${t.javaClass.simpleName})."
            )
        }
    }
}
