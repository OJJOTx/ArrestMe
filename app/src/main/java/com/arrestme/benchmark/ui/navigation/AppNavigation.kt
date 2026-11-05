package com.arrestme.benchmark.ui.navigation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BenchmarkRoute(val route: String, val label: String) {
    HOME("home", "Home"),
    PERMISSIONS("permissions", "Perms"),
    APPS("apps", "Apps"),
    SESSION("session", "Session"),
    PARTNERS("partners", "Partners"),
    BENCHMARK("benchmark", "Results"),
    DIAGNOSTICS("diagnostics", "Logs"),
    ACCOUNT("account", "Account")
}

object PartnerSubRoutes {
    const val PARTNER_REQUESTS = "partner_requests"
    const val SCHEDULED_ARRESTS = "scheduled_arrests"
    const val LIVE_DASHBOARD = "live_dashboard"
    const val CONVERSATIONS = "conversations"
    const val PARTNER_CHAT = "partner_chat/{conversationId}"
    const val PARTNER_DETAILS = "partner_details/{partnershipId}"
    const val PARTNER_APPROVAL_SETTINGS = "partner_approval_settings/{partnershipId}"
    const val PROPOSAL_BUILDER = "proposal_builder/{partnershipId}"
    const val NEGOTIATION_DETAILS = "negotiation_details/{proposalId}"

    fun partnerChat(conversationId: String) = "partner_chat/$conversationId"
    fun partnerDetails(partnershipId: String) = "partner_details/$partnershipId"
    fun partnerApprovalSettings(partnershipId: String) = "partner_approval_settings/$partnershipId"
    fun proposalBuilder(partnershipId: String) = "proposal_builder/$partnershipId"
    fun negotiationDetails(proposalId: String) = "negotiation_details/$proposalId"
}

fun formatDurationMmSs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}

fun formatWallClockTime(epochMs: Long): String {
    if (epochMs <= 0L) return "-"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return sdf.format(Date(epochMs))
}
