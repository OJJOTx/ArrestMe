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
    val inviteCode: String? = null,
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

// ============================================================================
// V0.4 — Advanced Partners, Approval Policies, Negotiations & Schedules DTOs
// ============================================================================

@Serializable
data class InviteCodeResponseDto(
    val inviteCode: String
)

@Serializable
data class PublicUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null
)

@Serializable
data class SafeDeviceSummaryDto(
    val id: String,
    val deviceName: String,
    val platform: String = "ANDROID",
    val deviceStatus: String = "ACTIVE"
)

@Serializable
data class SendPartnerRequestDto(
    val username: String? = null,
    val recipientUserId: String? = null,
    val inviteCode: String? = null
)

@Serializable
data class PartnershipDto(
    val id: String,
    val state: String,
    val sender: PublicUserDto,
    val recipient: PublicUserDto,
    val partner: PublicUserDto,
    val respondedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class PartnershipEnvelopeDto(
    val partnership: PartnershipDto
)

@Serializable
data class PaginatedPartnershipsDto(
    val items: List<PartnershipDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class PendingCountsDto(
    val incomingFriendRequestsCount: Int = 0,
    val outgoingFriendRequestsCount: Int = 0,
    val incomingProposalsCount: Int = 0,
    val outgoingProposalsCount: Int = 0
)

@Serializable
data class PartnerDiscoveryResponseDto(
    val found: Boolean,
    val user: PublicUserDto? = null,
    val relationshipState: String = "NONE",
    val partnershipId: String? = null,
    val pendingCounts: PendingCountsDto = PendingCountsDto()
)

@Serializable
data class TimeWindowRuleDto(
    val daysOfWeek: List<Int>,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int
)

@Serializable
data class AllowedTimeWindowsDto(
    val timezone: String,
    val windows: List<TimeWindowRuleDto>
)

@Serializable
data class BlockingConfigurationDto(
    val schemaVersion: Int = 1,
    val ruleMode: String = "BLOCK_SELECTED",
    val allowedCategories: List<String> = listOf("CALLS", "STUDY_APPS"),
    val overlayStatusBar: Boolean = false,
    val disableEmergencyExit: Boolean = false,
    val blockedPackages: List<String>? = null,
    val allowedStudyPackages: List<String>? = null
)

@Serializable
data class BlockingConfigLimitsDto(
    val schemaVersion: Int = 1,
    val allowedRuleModes: List<String> = listOf("BLOCK_SELECTED", "STRICT_ALLOWLIST"),
    val allowDisableEmergencyExit: Boolean = false,
    val allowOverlayStatusBar: Boolean = false,
    val requiredAllowedCategories: List<String> = listOf("CALLS"),
    val allowCustomPackageLists: Boolean = false,
    val dailyMaxSessions: Int? = null,
    val cooldownMinutes: Int? = null
)

@Serializable
data class ExactApprovedSessionConfigDto(
    val sessionType: String = "PARTNER_ARREST",
    val goal: String = "Focus Session",
    val durationMinutes: Int = 45,
    val blockingConfiguration: BlockingConfigurationDto = BlockingConfigurationDto()
)

@Serializable
data class PartnerPermissionDto(
    val permissionId: String,
    val owner: PublicUserDto,
    val partner: PublicUserDto,
    val targetDevice: SafeDeviceSummaryDto,
    val proposalId: String? = null,
    val approvedRevision: Int? = null,
    val boundSessionId: String? = null,
    val exactBlockingConfiguration: ExactApprovedSessionConfigDto? = null,
    val maximumSessionDuration: Int,
    val allowedTimeWindows: AllowedTimeWindowsDto? = null,
    val allowedSessionTypes: List<String> = listOf("PARTNER_ARREST"),
    val blockingConfigLimits: BlockingConfigLimitsDto = BlockingConfigLimitsDto(),
    val oneTimeOnly: Boolean = false,
    val usedCount: Int = 0,
    val version: Int = 1,
    val isCurrentlyActive: Boolean = true,
    val validFrom: String,
    val expiresAt: String,
    val revokedAt: String? = null,
    val revokedByUserId: String? = null,
    val revokeReason: String? = null,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class PartnerPermissionEnvelopeDto(
    val permission: PartnerPermissionDto
)

@Serializable
data class PaginatedPartnerPermissionsDto(
    val items: List<PartnerPermissionDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class CreatePartnerPermissionRequestDto(
    val partnerUserId: String,
    val targetDeviceId: String,
    val maximumSessionDuration: Int,
    val allowedTimeWindows: AllowedTimeWindowsDto? = null,
    val allowedSessionTypes: List<String> = listOf("PARTNER_ARREST"),
    val blockingConfigLimits: BlockingConfigLimitsDto? = null,
    val oneTimeOnly: Boolean = false,
    val validFrom: String? = null,
    val expiresAt: String
)

@Serializable
data class ApprovalPolicyDto(
    val ownerUserId: String,
    val partnerUserId: String,
    val requireApprovalBeforeArrest: Boolean = true,
    val explicitlyConfigured: Boolean = false,
    val notes: String? = null,
    val activeStandingAuthorizationsCount: Int = 0,
    val activeStandingAuthorizations: List<PartnerPermissionDto> = emptyList(),
    val updatedAt: String? = null
)

@Serializable
data class ApprovalPoliciesResponseDto(
    val myApprovalPolicyForPartner: ApprovalPolicyDto,
    val partnerApprovalPolicyForMe: ApprovalPolicyDto
)

@Serializable
data class UpdateApprovalPolicyRequestDto(
    val requireApprovalBeforeArrest: Boolean,
    val notes: String? = null
)

@Serializable
data class ProposalScheduleDto(
    val localDate: String,
    val localStartTime: String,
    val localEndTime: String? = null,
    val timezone: String,
    val scheduledStartUtc: String? = null,
    val scheduledEndUtc: String? = null
)

@Serializable
data class RecurrenceRuleDto(
    val daysOfWeek: List<Int>,
    val localStartTime: String,
    val localEndTime: String? = null,
    val durationMinutes: Int,
    val timezone: String,
    val endDate: String,
    val maxOccurrences: Int
)

@Serializable
data class ProposalStandingAuthorizationDto(
    val validFromUtc: String? = null,
    val expiresAtUtc: String,
    val maximumSessionDuration: Int,
    val allowedTimeWindows: AllowedTimeWindowsDto? = null,
    val blockingConfigLimits: BlockingConfigLimitsDto? = null
)

@Serializable
data class NormalizedProposalConfigDto(
    val schemaVersion: Int = 1,
    val requestType: String = "IMMEDIATE",
    val targetDeviceId: String,
    val ownerUserId: String,
    val initiatingPartnerUserId: String,
    val sessionType: String = "PARTNER_ARREST",
    val goal: String = "Focus Session",
    val durationMinutes: Int = 45,
    val blockingConfiguration: BlockingConfigurationDto = BlockingConfigurationDto(),
    val requireApprovalBeforeArrest: Boolean = true,
    val schedule: ProposalScheduleDto? = null,
    val recurrence: RecurrenceRuleDto? = null,
    val standingAuthorization: ProposalStandingAuthorizationDto? = null
)

@Serializable
data class ProposalRevisionDto(
    val id: String,
    val proposalId: String,
    val revisionNumber: Int,
    val proposer: PublicUserDto,
    val recipient: PublicUserDto,
    val normalizedConfiguration: NormalizedProposalConfigDto,
    val reason: String? = null,
    val status: String,
    val createdAt: String,
    val expiresAt: String
)

@Serializable
data class ProposalDto(
    val proposalId: String,
    val partnershipId: String,
    val owner: PublicUserDto,
    val partner: PublicUserDto,
    val targetDevice: SafeDeviceSummaryDto,
    val proposer: PublicUserDto,
    val recipient: PublicUserDto,
    val requestType: String,
    val currentRevision: Int,
    val status: String,
    val normalizedConfiguration: NormalizedProposalConfigDto,
    val reason: String? = null,
    val approvedRevision: Int? = null,
    val resultingPermissionId: String? = null,
    val resultingSessionId: String? = null,
    val resultingScheduleId: String? = null,
    val expiresAt: String,
    val respondedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val revisions: List<ProposalRevisionDto> = emptyList()
)

@Serializable
data class ProposalEnvelopeDto(
    val proposal: ProposalDto
)

@Serializable
data class PaginatedProposalsDto(
    val items: List<ProposalDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class CreateProposalRequestDto(
    val targetDeviceId: String,
    val partnerUserId: String? = null,
    val requestType: String = "IMMEDIATE",
    val sessionType: String = "PARTNER_ARREST",
    val goal: String = "Focus Session",
    val durationMinutes: Int = 45,
    val blockingConfiguration: BlockingConfigurationDto = BlockingConfigurationDto(),
    val requireApprovalBeforeArrest: Boolean = true,
    val schedule: ProposalScheduleDto? = null,
    val recurrence: RecurrenceRuleDto? = null,
    val standingAuthorization: ProposalStandingAuthorizationDto? = null,
    val reason: String? = null,
    val ttlMinutes: Int = 1440,
    val idempotencyKey: String? = null
)

@Serializable
data class CounterofferProposalRequestDto(
    val expectedRevisionNumber: Int,
    val requestType: String? = null,
    val sessionType: String? = null,
    val goal: String? = null,
    val durationMinutes: Int? = null,
    val blockingConfiguration: BlockingConfigurationDto? = null,
    val requireApprovalBeforeArrest: Boolean? = null,
    val schedule: ProposalScheduleDto? = null,
    val recurrence: RecurrenceRuleDto? = null,
    val standingAuthorization: ProposalStandingAuthorizationDto? = null,
    val reason: String? = null,
    val ttlMinutes: Int = 1440
)

@Serializable
data class AcceptProposalRequestDto(
    val expectedRevisionNumber: Int,
    val startImmediateSession: Boolean = true
)

@Serializable
data class RejectOrCancelProposalRequestDto(
    val expectedRevisionNumber: Int? = null,
    val reason: String? = null
)

@Serializable
data class ScheduledArrestDto(
    val scheduleId: String,
    val proposalId: String? = null,
    val permissionId: String? = null,
    val owner: PublicUserDto,
    val partner: PublicUserDto,
    val targetDevice: SafeDeviceSummaryDto,
    val scheduleType: String,
    val sessionType: String = "PARTNER_ARREST",
    val goal: String,
    val durationMinutes: Int,
    val blockingConfiguration: BlockingConfigurationDto,
    val timezone: String,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null,
    val recurrenceRule: RecurrenceRuleDto? = null,
    val occurrencesCompleted: Int = 0,
    val maxOccurrences: Int = 1,
    val nextOccurrenceAt: String? = null,
    val lastMaterializedSessionId: String? = null,
    val lastMaterializedAt: String? = null,
    val status: String,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class ScheduledArrestEnvelopeDto(
    val schedule: ScheduledArrestDto
)

@Serializable
data class PaginatedScheduledArrestsDto(
    val items: List<ScheduledArrestDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class ArrestSessionDto(
    val sessionId: String,
    val localSessionId: String? = null,
    val ownerUserId: String = "",
    val targetDeviceId: String = "",
    val initiatedByUserId: String = "",
    val authorizationId: String? = null,
    val sessionType: String = "PARTNER_ARREST",
    val goal: String = "Focus Session",
    val blockingConfiguration: BlockingConfigurationDto = BlockingConfigurationDto(),
    val requestedDurationMinutes: Int = 45,
    val status: String = "REQUESTED",
    val remoteExecutionPhase: String = "QUEUED",
    val localEngineState: String? = null,
    val requestedAt: String = "",
    val acceptedAt: String? = null,
    val startedAt: String? = null,
    val expectedEndAt: String? = null,
    val completedAt: String? = null,
    val completionReason: String? = null,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class AcceptProposalResponseDto(
    val proposal: ProposalDto,
    val permission: PartnerPermissionDto? = null,
    val session: ArrestSessionDto? = null,
    val schedule: ScheduledArrestDto? = null
)

@Serializable
data class PartnerProfileDto(
    val partnership: PartnershipDto,
    val partner: PublicUserDto,
    val relationshipStatus: String,
    val myDevices: List<SafeDeviceSummaryDto> = emptyList(),
    val partnerDevices: List<SafeDeviceSummaryDto> = emptyList(),
    val permissionsGrantedToPartner: List<PartnerPermissionDto> = emptyList(),
    val permissionsReceivedFromPartner: List<PartnerPermissionDto> = emptyList(),
    val myApprovalPolicyForPartner: ApprovalPolicyDto,
    val partnerApprovalPolicyForMe: ApprovalPolicyDto,
    val pendingProposals: List<ProposalDto> = emptyList(),
    val activeSchedules: List<ScheduledArrestDto> = emptyList()
)

@Serializable
data class PartnerProfileEnvelopeDto(
    val profile: PartnerProfileDto
)

// ============================================================================
// V0.5 — Remote Arrest Execution, Scheduling, App Catalog & Live Dashboard DTOs
// ============================================================================

@Serializable
data class SessionCommandDto(
    val commandId: String,
    val sequenceNo: Long = 0L,
    val sessionId: String? = null,
    val targetDeviceId: String = "",
    val issuedByUserId: String = "",
    val commandType: String = "",
    val payload: JsonObject = JsonObject(emptyMap()),
    val status: String = "PENDING",
    val resultMetadata: JsonObject? = null,
    val failureReason: String? = null,
    val pushDeliveryStatus: String = "NOT_ATTEMPTED",
    val createdAt: String = "",
    val expiresAt: String = "",
    val deliveredAt: String? = null,
    val acknowledgedAt: String? = null,
    val executedAt: String? = null,
    val updatedAt: String = ""
)

@Serializable
data class PollDeviceCommandsResponseDto(
    val items: List<SessionCommandDto> = emptyList(),
    val nextSequenceCursor: Long = 0L
)

@Serializable
data class AcknowledgeCommandRequestDto(
    val deviceId: String,
    val status: String,
    val failureReason: String? = null,
    val resultMetadata: JsonObject? = null
)

@Serializable
data class AcknowledgeCommandResponseDto(
    val command: SessionCommandDto,
    val idempotentReplay: Boolean = false
)

@Serializable
data class CreateRemoteSessionRequestDto(
    val sessionType: String = "PARTNER_ARREST",
    val targetDeviceId: String,
    val authorizationId: String? = null,
    val requestedDurationMinutes: Int,
    val goal: String,
    val blockingConfiguration: BlockingConfigurationDto = BlockingConfigurationDto(),
    val commandTtlSeconds: Int? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class CreateRemoteSessionResponseDto(
    val session: ArrestSessionDto,
    val command: SessionCommandDto? = null,
    val idempotentReplay: Boolean = false
)

@Serializable
data class ArrestSessionEnvelopeDto(
    val session: ArrestSessionDto
)

@Serializable
data class PaginatedSessionsResponseDto(
    val items: List<ArrestSessionDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val offset: Int = 0
)

@Serializable
data class ActiveSessionsResponseDto(
    val items: List<ArrestSessionDto> = emptyList()
)

@Serializable
data class AcknowledgeSessionRequestDto(
    val deviceId: String,
    val outcome: String,
    val localSessionId: String? = null,
    val localEngineState: String? = null,
    val reason: String? = null
)

@Serializable
data class SyncEventItemDto(
    val clientEventId: String,
    val eventType: String,
    val fromEngineState: String? = null,
    val toEngineState: String? = null,
    val occurredAtClientMs: Long,
    val localSessionId: String? = null,
    val accumulatedPausedMs: Long? = null,
    val reason: String? = null
)

@Serializable
data class PostSessionEventsRequestDto(
    val deviceId: String,
    val events: List<SyncEventItemDto>
)

@Serializable
data class PostSessionEventsResponseDto(
    val session: ArrestSessionDto,
    val processedCount: Int = 0,
    val duplicateCount: Int = 0
)

@Serializable
data class RemoteSessionCommandRequestDto(
    val reason: String? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class RemoteSessionCommandResponseDto(
    val command: SessionCommandDto
)

@Serializable
data class LiveSessionDashboardDeviceDto(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String? = null,
    val platform: String = "ANDROID",
    val deviceStatus: String = "ACTIVE",
    val lastSeenAt: String? = null
)

@Serializable
data class LiveSessionDashboardItemDto(
    val session: ArrestSessionDto,
    val owner: PublicUserDto,
    val initiator: PublicUserDto,
    val targetDevice: LiveSessionDashboardDeviceDto? = null,
    val remainingSeconds: Int? = null,
    val pendingReleaseRequestsCount: Int = 0
)

@Serializable
data class LiveSessionDashboardResponseDto(
    val items: List<LiveSessionDashboardItemDto> = emptyList()
)

@Serializable
data class SelectableAppCatalogItemDto(
    val packageName: String,
    val appLabel: String,
    val category: String = "USER_APP",
    val isStudyCandidate: Boolean = false
)

@Serializable
data class UpsertDeviceAppCatalogRequestDto(
    val apps: List<SelectableAppCatalogItemDto>
)

@Serializable
data class DeviceAppCatalogDto(
    val deviceId: String,
    val ownerUserId: String,
    val apps: List<SelectableAppCatalogItemDto> = emptyList(),
    val filteredOutSystemCount: Int = 0,
    val updatedAt: String? = null
)

@Serializable
data class DeviceAppCatalogEnvelopeDto(
    val catalog: DeviceAppCatalogDto
)

@Serializable
data class TriggerScheduledArrestResponseDto(
    val schedule: ScheduledArrestDto,
    val sessionId: String,
    val command: SessionCommandDto,
    val idempotentReplay: Boolean = false
)

@Serializable
data class UserSummaryDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null
)

@Serializable
data class ConversationMemberDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val unreadCount: Int = 0
)

@Serializable
data class ConversationDto(
    val id: String,
    val partnershipId: String,
    val partner: UserSummaryDto? = null,
    val members: List<ConversationMemberDto> = emptyList(),
    val unreadCount: Int = 0,
    val lastMessage: MessageDto? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ConversationEnvelopeDto(
    val conversation: ConversationDto
)

@Serializable
data class ConversationListResponseDto(
    val items: List<ConversationDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0
)

@Serializable
data class MessageDto(
    val id: String,
    val conversationId: String,
    val sender: UserSummaryDto,
    val clientMessageId: String,
    val messageType: String = "TEXT",
    val textContent: String? = null,
    val structuredRequest: StructuredRequestDto? = null,
    val status: String = "SENT",
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class MessageEnvelopeDto(
    val message: MessageDto
)

@Serializable
data class MessageListResponseDto(
    val items: List<MessageDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0
)

@Serializable
data class StructuredRequestDto(
    val id: String,
    val messageId: String,
    val conversationId: String,
    val requestType: String,
    val initiator: UserSummaryDto? = null,
    val recipient: UserSummaryDto? = null,
    val sessionId: String? = null,
    val status: String = "PENDING",
    val durationMinutes: Int? = null,
    val targetPackage: String? = null,
    val messageText: String? = null,
    val parameters: JsonObject? = null,
    val decisionReason: String? = null,
    val decidedAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StructuredRequestEnvelopeDto(
    val request: StructuredRequestDto
)

@Serializable
data class SendTextMessageRequestDto(
    val clientMessageId: String,
    val text: String
)

@Serializable
data class CreateStructuredRequestDto(
    val clientMessageId: String,
    val requestType: String,
    val sessionId: String? = null,
    val targetPackage: String? = null,
    val durationMinutes: Int? = null,
    val parameters: JsonObject? = null,
    val messageText: String? = null
)

@Serializable
data class RespondStructuredRequestDto(
    val decision: String,
    val reason: String? = null
)

@Serializable
data class MarkConversationReadRequestDto(
    val lastReadMessageId: String
)

@Serializable
data class TemporaryGrantDto(
    val id: String,
    val sessionId: String,
    val deviceId: String,
    val packageName: String,
    val grantedDurationMinutes: Int,
    val grantedByUserId: String,
    val startsAt: String,
    val expiresAt: String,
    val revokedAt: String? = null,
    val isCurrentlyActive: Boolean = true,
    val createdAt: String
) {
    val targetPackage: String get() = packageName
    val durationMinutes: Int get() = grantedDurationMinutes
}

@Serializable
data class TemporaryGrantsListResponseDto(
    val items: List<TemporaryGrantDto> = emptyList()
)
