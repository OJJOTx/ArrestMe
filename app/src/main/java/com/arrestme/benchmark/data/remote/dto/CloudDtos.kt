package com.arrestme.benchmark.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthResponseDto(
    val status: String = "ok",
    val service: String? = null,
    val version: String? = null
)

@Serializable
data class ApiErrorEnvelopeDto(
    val error: ApiErrorBodyDto
)

@Serializable
data class ApiErrorBodyDto(
    val code: String,
    val message: String,
    val details: JsonObject? = null
)

@Serializable
data class RegisterRequestDto(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String? = null
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
    val deviceId: String? = null
)

@Serializable
data class RefreshRequestDto(
    val refreshToken: String
)

@Serializable
data class LogoutRequestDto(
    val refreshToken: String
)

@Serializable
data class LogoutResponseDto(
    val status: String
)

@Serializable
data class AuthTokensDto(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val expiresInSeconds: Int,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val tokenType: String = "Bearer"
)

@Serializable
data class AuthenticatedUserDto(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val accountStatus: String = "ACTIVE",
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastLoginAt: String? = null
)

@Serializable
data class AuthSessionResponseDto(
    val user: AuthenticatedUserDto,
    val tokens: AuthTokensDto
)

@Serializable
data class RefreshTokensResponseDto(
    val tokens: AuthTokensDto
)

@Serializable
data class CurrentUserResponseDto(
    val user: AuthenticatedUserDto
)

@Serializable
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class RegisterDeviceRequestDto(
    val installationId: String? = null,
    val deviceName: String,
    val platform: String = "ANDROID",
    val manufacturer: String? = null,
    val model: String? = null,
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int? = null
)

@Serializable
data class UpdateDeviceRequestDto(
    val deviceName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
    val deviceStatus: String? = null
)

@Serializable
data class DeviceDto(
    val id: String,
    val userId: String,
    val installationId: String? = null,
    val deviceName: String,
    val platform: String = "ANDROID",
    val manufacturer: String? = null,
    val model: String? = null,
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int? = null,
    val deviceStatus: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class DeviceEnvelopeDto(
    val device: DeviceDto
)

@Serializable
data class PaginatedDevicesResponseDto(
    val items: List<DeviceDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)
