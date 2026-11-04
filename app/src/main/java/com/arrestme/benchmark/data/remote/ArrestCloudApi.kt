package com.arrestme.benchmark.data.remote

import com.arrestme.benchmark.data.remote.dto.AuthSessionResponseDto
import com.arrestme.benchmark.data.remote.dto.CurrentUserResponseDto
import com.arrestme.benchmark.data.remote.dto.DeviceEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.HealthResponseDto
import com.arrestme.benchmark.data.remote.dto.LoginRequestDto
import com.arrestme.benchmark.data.remote.dto.LogoutRequestDto
import com.arrestme.benchmark.data.remote.dto.LogoutResponseDto
import com.arrestme.benchmark.data.remote.dto.PaginatedDevicesResponseDto
import com.arrestme.benchmark.data.remote.dto.RefreshRequestDto
import com.arrestme.benchmark.data.remote.dto.RefreshTokensResponseDto
import com.arrestme.benchmark.data.remote.dto.RegisterDeviceRequestDto
import com.arrestme.benchmark.data.remote.dto.RegisterRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateDeviceRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateProfileRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit API contract matching OJJOTx/ArrestMe-backend Fastify routes.
 */
interface ArrestCloudApi {

    @GET("api/v1/health")
    suspend fun getVersionedHealth(): Response<HealthResponseDto>

    @GET("health")
    suspend fun getRootHealth(): Response<HealthResponseDto>

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): Response<AuthSessionResponseDto>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): Response<AuthSessionResponseDto>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<RefreshTokensResponseDto>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto): Response<LogoutResponseDto>

    @GET("api/v1/users/me")
    suspend fun getMe(): Response<CurrentUserResponseDto>

    @PATCH("api/v1/users/me")
    suspend fun updateMe(@Body body: UpdateProfileRequestDto): Response<CurrentUserResponseDto>

    @POST("api/v1/devices")
    suspend fun registerDevice(@Body body: RegisterDeviceRequestDto): Response<DeviceEnvelopeDto>

    @GET("api/v1/devices")
    suspend fun listDevices(
        @Query("includeRevoked") includeRevoked: Boolean = false,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedDevicesResponseDto>

    @GET("api/v1/devices/{id}")
    suspend fun getDevice(@Path("id") deviceId: String): Response<DeviceEnvelopeDto>

    @PATCH("api/v1/devices/{id}")
    suspend fun updateDevice(
        @Path("id") deviceId: String,
        @Body body: UpdateDeviceRequestDto
    ): Response<DeviceEnvelopeDto>

    @DELETE("api/v1/devices/{id}")
    suspend fun revokeDevice(@Path("id") deviceId: String): Response<DeviceEnvelopeDto>
}
