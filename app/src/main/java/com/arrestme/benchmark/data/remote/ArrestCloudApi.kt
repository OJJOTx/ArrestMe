package com.arrestme.benchmark.data.remote

import com.arrestme.benchmark.data.remote.dto.AcceptProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.AcceptProposalResponseDto
import com.arrestme.benchmark.data.remote.dto.AcknowledgeCommandRequestDto
import com.arrestme.benchmark.data.remote.dto.AcknowledgeCommandResponseDto
import com.arrestme.benchmark.data.remote.dto.AcknowledgeSessionRequestDto
import com.arrestme.benchmark.data.remote.dto.ActiveSessionsResponseDto
import com.arrestme.benchmark.data.remote.dto.ApprovalPoliciesResponseDto
import com.arrestme.benchmark.data.remote.dto.ArrestSessionEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.AuthSessionResponseDto
import com.arrestme.benchmark.data.remote.dto.CounterofferProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.CreatePartnerPermissionRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateRemoteSessionRequestDto
import com.arrestme.benchmark.data.remote.dto.CreateRemoteSessionResponseDto
import com.arrestme.benchmark.data.remote.dto.CurrentUserResponseDto
import com.arrestme.benchmark.data.remote.dto.DeviceAppCatalogEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.DeviceEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.HealthResponseDto
import com.arrestme.benchmark.data.remote.dto.InviteCodeResponseDto
import com.arrestme.benchmark.data.remote.dto.LiveSessionDashboardResponseDto
import com.arrestme.benchmark.data.remote.dto.LoginRequestDto
import com.arrestme.benchmark.data.remote.dto.LogoutRequestDto
import com.arrestme.benchmark.data.remote.dto.LogoutResponseDto
import com.arrestme.benchmark.data.remote.dto.PaginatedDevicesResponseDto
import com.arrestme.benchmark.data.remote.dto.PaginatedPartnerPermissionsDto
import com.arrestme.benchmark.data.remote.dto.PaginatedPartnershipsDto
import com.arrestme.benchmark.data.remote.dto.PaginatedProposalsDto
import com.arrestme.benchmark.data.remote.dto.PaginatedScheduledArrestsDto
import com.arrestme.benchmark.data.remote.dto.PaginatedSessionsResponseDto
import com.arrestme.benchmark.data.remote.dto.PartnerDiscoveryResponseDto
import com.arrestme.benchmark.data.remote.dto.PartnerPermissionEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.PartnerProfileEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.PartnershipEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.PollDeviceCommandsResponseDto
import com.arrestme.benchmark.data.remote.dto.PostSessionEventsRequestDto
import com.arrestme.benchmark.data.remote.dto.PostSessionEventsResponseDto
import com.arrestme.benchmark.data.remote.dto.ProposalEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.RefreshRequestDto
import com.arrestme.benchmark.data.remote.dto.RefreshTokensResponseDto
import com.arrestme.benchmark.data.remote.dto.RegisterDeviceRequestDto
import com.arrestme.benchmark.data.remote.dto.RegisterRequestDto
import com.arrestme.benchmark.data.remote.dto.RejectOrCancelProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.RemoteSessionCommandRequestDto
import com.arrestme.benchmark.data.remote.dto.RemoteSessionCommandResponseDto
import com.arrestme.benchmark.data.remote.dto.ScheduledArrestEnvelopeDto
import com.arrestme.benchmark.data.remote.dto.SendPartnerRequestDto
import com.arrestme.benchmark.data.remote.dto.TriggerScheduledArrestResponseDto
import com.arrestme.benchmark.data.remote.dto.UpdateApprovalPolicyRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateDeviceRequestDto
import com.arrestme.benchmark.data.remote.dto.UpdateProfileRequestDto
import com.arrestme.benchmark.data.remote.dto.UpsertDeviceAppCatalogRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
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

    @GET("api/v1/users/me/invite-code")
    suspend fun getMyInviteCode(): Response<InviteCodeResponseDto>

    @POST("api/v1/users/me/invite-code/regenerate")
    suspend fun regenerateMyInviteCode(): Response<InviteCodeResponseDto>

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

    // ========================================================================
    // V0.4 — Partners, Discovery, Profiles & Approval Policies
    // ========================================================================

    @GET("api/v1/partners/discover")
    suspend fun discoverPartner(
        @Query("username") username: String? = null,
        @Query("inviteCode") inviteCode: String? = null
    ): Response<PartnerDiscoveryResponseDto>

    @POST("api/v1/partners/requests")
    suspend fun sendPartnerRequest(@Body body: SendPartnerRequestDto): Response<PartnershipEnvelopeDto>

    @GET("api/v1/partners/requests")
    suspend fun listPartnerRequests(
        @Query("direction") direction: String = "all",
        @Query("state") state: String? = "PENDING",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedPartnershipsDto>

    @POST("api/v1/partners/requests/{id}/accept")
    suspend fun acceptPartnerRequest(@Path("id") partnershipId: String): Response<PartnershipEnvelopeDto>

    @POST("api/v1/partners/requests/{id}/reject")
    suspend fun rejectPartnerRequest(@Path("id") partnershipId: String): Response<PartnershipEnvelopeDto>

    @GET("api/v1/partners")
    suspend fun listPartners(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedPartnershipsDto>

    @GET("api/v1/partners/{id}/profile")
    suspend fun getPartnerProfile(@Path("id") partnershipOrUserId: String): Response<PartnerProfileEnvelopeDto>

    @GET("api/v1/partners/{id}/approval-policy")
    suspend fun getApprovalPolicy(@Path("id") partnershipOrUserId: String): Response<ApprovalPoliciesResponseDto>

    @PUT("api/v1/partners/{id}/approval-policy")
    suspend fun updateApprovalPolicy(
        @Path("id") partnershipOrUserId: String,
        @Body body: UpdateApprovalPolicyRequestDto
    ): Response<ApprovalPoliciesResponseDto>

    @DELETE("api/v1/partners/{id}")
    suspend fun removeOrCancelPartnership(@Path("id") partnershipId: String): Response<PartnershipEnvelopeDto>

    @POST("api/v1/partners/{id}/block")
    suspend fun blockPartner(@Path("id") partnershipOrUserId: String): Response<PartnershipEnvelopeDto>

    // ========================================================================
    // V0.4 — Partner Permissions & Standing Authorizations
    // ========================================================================

    @POST("api/v1/partner-permissions")
    suspend fun createPartnerPermission(
        @Body body: CreatePartnerPermissionRequestDto
    ): Response<PartnerPermissionEnvelopeDto>

    @GET("api/v1/partner-permissions")
    suspend fun listPartnerPermissions(
        @Query("role") role: String = "all",
        @Query("activeOnly") activeOnly: Boolean = true,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedPartnerPermissionsDto>

    @DELETE("api/v1/partner-permissions/{id}")
    suspend fun revokePartnerPermission(@Path("id") permissionId: String): Response<PartnerPermissionEnvelopeDto>

    // ========================================================================
    // V0.4 — Proposals & Negotiations
    // ========================================================================

    @POST("api/v1/proposals")
    suspend fun createProposal(@Body body: CreateProposalRequestDto): Response<ProposalEnvelopeDto>

    @GET("api/v1/proposals")
    suspend fun listProposals(
        @Query("role") role: String = "all",
        @Query("status") status: String? = null,
        @Query("partnerUserId") partnerUserId: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedProposalsDto>

    @GET("api/v1/proposals/{id}")
    suspend fun getProposal(@Path("id") proposalId: String): Response<ProposalEnvelopeDto>

    @POST("api/v1/proposals/{id}/counteroffer")
    suspend fun counterofferProposal(
        @Path("id") proposalId: String,
        @Body body: CounterofferProposalRequestDto
    ): Response<ProposalEnvelopeDto>

    @POST("api/v1/proposals/{id}/accept")
    suspend fun acceptProposal(
        @Path("id") proposalId: String,
        @Body body: AcceptProposalRequestDto
    ): Response<AcceptProposalResponseDto>

    @POST("api/v1/proposals/{id}/reject")
    suspend fun rejectProposal(
        @Path("id") proposalId: String,
        @Body body: RejectOrCancelProposalRequestDto = RejectOrCancelProposalRequestDto()
    ): Response<ProposalEnvelopeDto>

    @POST("api/v1/proposals/{id}/cancel")
    suspend fun cancelProposal(
        @Path("id") proposalId: String,
        @Body body: RejectOrCancelProposalRequestDto = RejectOrCancelProposalRequestDto()
    ): Response<ProposalEnvelopeDto>

    // ========================================================================
    // V0.4 — Scheduled & Recurring Arrests
    // ========================================================================

    @GET("api/v1/scheduled-arrests")
    suspend fun listScheduledArrests(
        @Query("role") role: String = "all",
        @Query("status") status: String? = null,
        @Query("partnerUserId") partnerUserId: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedScheduledArrestsDto>

    @GET("api/v1/scheduled-arrests/{id}")
    suspend fun getScheduledArrest(@Path("id") scheduleId: String): Response<ScheduledArrestEnvelopeDto>

    @POST("api/v1/scheduled-arrests/{id}/cancel")
    suspend fun cancelScheduledArrest(@Path("id") scheduleId: String): Response<ScheduledArrestEnvelopeDto>

    @POST("api/v1/scheduled-arrests/{id}/trigger")
    suspend fun triggerScheduledArrest(@Path("id") scheduleId: String): Response<TriggerScheduledArrestResponseDto>

    // ========================================================================
    // V0.5 — Selectable App Catalog, Command Queue & Remote Session Coordination
    // ========================================================================

    @PUT("api/v1/devices/{id}/app-catalog")
    suspend fun upsertDeviceAppCatalog(
        @Path("id") deviceId: String,
        @Body body: UpsertDeviceAppCatalogRequestDto
    ): Response<DeviceAppCatalogEnvelopeDto>

    @GET("api/v1/devices/{id}/app-catalog")
    suspend fun getDeviceAppCatalog(
        @Path("id") deviceId: String
    ): Response<DeviceAppCatalogEnvelopeDto>

    @GET("api/v1/devices/{id}/commands")
    suspend fun pollDeviceCommands(
        @Path("id") deviceId: String,
        @Query("afterSequence") afterSequence: Long = 0L,
        @Query("includeDelivered") includeDelivered: Boolean = true,
        @Query("limit") limit: Int = 25
    ): Response<PollDeviceCommandsResponseDto>

    @POST("api/v1/commands/{id}/acknowledge")
    suspend fun acknowledgeCommand(
        @Path("id") commandId: String,
        @Body body: AcknowledgeCommandRequestDto
    ): Response<AcknowledgeCommandResponseDto>

    @POST("api/v1/sessions")
    suspend fun createRemoteSession(
        @Body body: CreateRemoteSessionRequestDto
    ): Response<CreateRemoteSessionResponseDto>

    @GET("api/v1/sessions")
    suspend fun listSessions(
        @Query("role") role: String = "all",
        @Query("status") status: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<PaginatedSessionsResponseDto>

    @GET("api/v1/sessions/active")
    suspend fun listActiveSessions(): Response<ActiveSessionsResponseDto>

    @GET("api/v1/sessions/live-dashboard")
    suspend fun getLiveDashboardSessions(): Response<LiveSessionDashboardResponseDto>

    @GET("api/v1/sessions/{id}")
    suspend fun getSession(@Path("id") sessionId: String): Response<ArrestSessionEnvelopeDto>

    @POST("api/v1/sessions/{id}/acknowledge")
    suspend fun acknowledgeSession(
        @Path("id") sessionId: String,
        @Body body: AcknowledgeSessionRequestDto
    ): Response<ArrestSessionEnvelopeDto>

    @POST("api/v1/sessions/{id}/events")
    suspend fun postSessionEvents(
        @Path("id") sessionId: String,
        @Body body: PostSessionEventsRequestDto
    ): Response<PostSessionEventsResponseDto>

    @POST("api/v1/sessions/{id}/end")
    suspend fun endRemoteSession(
        @Path("id") sessionId: String,
        @Body body: RemoteSessionCommandRequestDto = RemoteSessionCommandRequestDto()
    ): Response<RemoteSessionCommandResponseDto>

    @POST("api/v1/sessions/{id}/sync")
    suspend fun syncRemoteSession(
        @Path("id") sessionId: String,
        @Body body: RemoteSessionCommandRequestDto = RemoteSessionCommandRequestDto()
    ): Response<RemoteSessionCommandResponseDto>
}
