package com.arrestme.benchmark.core.backend

import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern

sealed class UrlValidationResult {
    data class Valid(
        val normalizedUrl: String,
        val host: String,
        val isLocalOrPrivate: Boolean
    ) : UrlValidationResult()

    data class Invalid(val reason: String) : UrlValidationResult()
}

/**
 * Validates backend endpoints and enforces security rules:
 * - Valid well-formed HTTP/HTTPS URL
 * - Trailing slashes stripped and scheme/host normalized
 * - Remote production endpoints MUST use HTTPS
 * - Plain HTTP is strictly restricted to local and private addresses (localhost, 10.0.2.2, LAN IPs)
 */
object BackendUrlValidator {

    private val IPV4_PATTERN = Pattern.compile(
        "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$"
    )

    fun validate(rawUrl: String?): UrlValidationResult {
        if (rawUrl.isNullOrBlank()) {
            return UrlValidationResult.Invalid("URL cannot be empty.")
        }

        val trimmed = rawUrl.trim()

        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return UrlValidationResult.Invalid("Malformed URL syntax: ${e.reason}")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.Invalid("URL scheme must be http or https (received: '${uri.scheme}').")
        }

        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            return UrlValidationResult.Invalid("URL must contain a valid hostname or IP address.")
        }

        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            return UrlValidationResult.Invalid("Port number must be between 1 and 65535 (received: $port).")
        }

        val isLocalOrPrivate = isLocalOrPrivateAddress(host)

        if (!isLocalOrPrivate && scheme != "https") {
            return UrlValidationResult.Invalid(
                "HTTPS is required for remote production endpoints ($host). " +
                        "Plain HTTP is only permitted for local addresses (localhost, 10.0.2.2, 192.168.x.x)."
            )
        }

        // Normalize URL: strip trailing slashes and strip redundant "/api/v1" suffix if pasted
        val trimmedPath = uri.rawPath?.trimEnd('/') ?: ""
        val rawPath = if (trimmedPath.lowercase().endsWith("/api/v1")) {
            trimmedPath.dropLast("/api/v1".length).trimEnd('/')
        } else {
            trimmedPath
        }
        val portSuffix = if (port != -1 && !isStandardPort(scheme, port)) ":$port" else ""
        val normalized = "$scheme://$host$portSuffix$rawPath"

        return UrlValidationResult.Valid(
            normalizedUrl = normalized,
            host = host,
            isLocalOrPrivate = isLocalOrPrivate
        )
    }

    /**
     * Converts a normalized server base URL (e.g. `https://api.example.com`) into a Retrofit-compatible
     * base URL ending with a trailing slash (e.g. `https://api.example.com/`).
     */
    fun toRetrofitBaseUrl(normalizedBaseUrl: String): String {
        return "${normalizedBaseUrl.trimEnd('/')}/"
    }

    private fun isStandardPort(scheme: String, port: Int): Boolean {
        return (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    }

    fun isLocalOrPrivateAddress(host: String): Boolean {
        val lowerHost = host.lowercase()

        if (lowerHost == "localhost" ||
            lowerHost == "127.0.0.1" ||
            lowerHost == "::1" ||
            lowerHost == "[::1]" ||
            lowerHost == "10.0.2.2" || // Android Emulator Host Loopback
            lowerHost.endsWith(".local")
        ) {
            return true
        }

        val matcher = IPV4_PATTERN.matcher(lowerHost)
        if (matcher.matches()) {
            val octet1 = matcher.group(1)?.toIntOrNull() ?: return false
            val octet2 = matcher.group(2)?.toIntOrNull() ?: return false
            val octet3 = matcher.group(3)?.toIntOrNull() ?: return false
            val octet4 = matcher.group(4)?.toIntOrNull() ?: return false

            if (octet1 in 0..255 && octet2 in 0..255 && octet3 in 0..255 && octet4 in 0..255) {
                // Loopback 127.0.0.0/8
                if (octet1 == 127) return true

                // RFC1918 Private ranges:
                // 10.0.0.0 – 10.255.255.255 (10/8)
                if (octet1 == 10) return true

                // 172.16.0.0 – 172.31.255.255 (172.16/12)
                if (octet1 == 172 && octet2 in 16..31) return true

                // 192.168.0.0 – 192.168.255.255 (192.168/16)
                if (octet1 == 192 && octet2 == 168) return true
            }
        }

        return false
    }
}
