package com.arrestme.benchmark.ui.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.data.remote.dto.BlockingConfigurationDto
import com.arrestme.benchmark.data.remote.dto.CounterofferProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.ProposalDto

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NegotiationDetailsScreen(
    proposalId: String,
    currentUserId: String?,
    proposal: ProposalDto?,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadProposal: (String) -> Unit,
    onAcceptProposal: (proposalId: String, expectedRevisionNumber: Int, startImmediateSession: Boolean) -> Unit,
    onRejectProposal: (proposalId: String, expectedRevisionNumber: Int, reason: String?) -> Unit,
    onCancelProposal: (proposalId: String, expectedRevisionNumber: Int, reason: String?) -> Unit,
    onCounterofferProposal: (proposalId: String, CounterofferProposalRequestDto) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(proposalId) {
        onLoadProposal(proposalId)
    }

    val scrollState = rememberScrollState()
    var showCounterofferForm by rememberSaveable { mutableStateOf(false) }

    val cfg = proposal?.normalizedConfiguration
    var counterGoal by remember(proposal?.currentRevision) {
        mutableStateOf(cfg?.goal ?: "Focus Session")
    }
    var counterDurationText by remember(proposal?.currentRevision) {
        mutableStateOf((cfg?.durationMinutes ?: 45).toString())
    }
    var counterRuleMode by remember(proposal?.currentRevision) {
        mutableStateOf(cfg?.blockingConfiguration?.ruleMode ?: "BLOCK_SELECTED")
    }
    var counterOverlay by remember(proposal?.currentRevision) {
        mutableStateOf(cfg?.blockingConfiguration?.overlayStatusBar ?: false)
    }
    var counterDisableEmergency by remember(proposal?.currentRevision) {
        mutableStateOf(cfg?.blockingConfiguration?.disableEmergencyExit ?: false)
    }
    var counterBlockedText by remember(proposal?.currentRevision) {
        mutableStateOf(cfg?.blockingConfiguration?.blockedPackages?.joinToString(", ").orEmpty())
    }
    var counterAllowedText by remember(proposal?.currentRevision) {
        mutableStateOf(cfg?.blockingConfiguration?.allowedStudyPackages?.joinToString(", ").orEmpty())
    }
    var counterReason by rememberSaveable { mutableStateOf("") }
    var actionNote by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Negotiation Details",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = proposal?.let { "Revision #${it.currentRevision} • ${it.requestType}" }
                            ?: "Loading proposal...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = { onLoadProposal(proposalId) },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (proposal == null || cfg == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isLoading) "Loading proposal details..." else "Proposal not found.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        val isPendingOrCountered = proposal.status == "PENDING" || proposal.status == "COUNTEROFFERED"
        val isRecipient = currentUserId != null && proposal.recipient.id == currentUserId
        val isProposer = currentUserId != null && proposal.proposer.id == currentUserId

        // 1. Current Proposal Overview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cfg.goal,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    StatusChip(status = proposal.status)
                }

                Text(
                    text = "Proposal ID: ${proposal.proposalId.take(18)}… • Current Revision: #${proposal.currentRevision}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                DetailLine("Request Type", proposal.requestType)
                DetailLine("Device Owner (Arrested)", "@${proposal.owner.username} (${proposal.owner.displayName})")
                DetailLine("Controlling Partner", "@${proposal.partner.username} (${proposal.partner.displayName})")
                DetailLine("Target Device", "${proposal.targetDevice.deviceName} (${proposal.targetDevice.platform})")
                DetailLine("Latest Revision By", "@${proposal.proposer.username} → Awaiting @${proposal.recipient.username}")
                DetailLine("Duration", "${cfg.durationMinutes} minutes")
                DetailLine("Blocking Mode", cfg.blockingConfiguration.ruleMode)
                DetailLine(
                    "Engine Flags",
                    "overlayStatusBar=${cfg.blockingConfiguration.overlayStatusBar}, disableEmergencyExit=${cfg.blockingConfiguration.disableEmergencyExit}"
                )

                if (!cfg.blockingConfiguration.blockedPackages.isNullOrEmpty()) {
                    DetailLine("Blocked Packages", cfg.blockingConfiguration.blockedPackages.joinToString(", "))
                }
                if (!cfg.blockingConfiguration.allowedStudyPackages.isNullOrEmpty()) {
                    DetailLine("Allowed Study Packages", cfg.blockingConfiguration.allowedStudyPackages.joinToString(", "))
                }

                cfg.schedule?.let { sched ->
                    DetailLine(
                        "Scheduled Time",
                        "${sched.localDate} ${sched.localStartTime} (${sched.timezone}) → UTC: ${sched.scheduledStartUtc?.take(19) ?: "-"}"
                    )
                }
                cfg.recurrence?.let { rec ->
                    DetailLine(
                        "Recurrence Rule",
                        "Days=${rec.daysOfWeek.joinToString(",")} @ ${rec.localStartTime} (${rec.timezone}) until ${rec.endDate} (Max ${rec.maxOccurrences})"
                    )
                }
                cfg.standingAuthorization?.let { st ->
                    DetailLine(
                        "Standing Auth Limits",
                        "Max ${st.maximumSessionDuration} min/session • Expires ${st.expiresAtUtc.take(19)} UTC"
                    )
                }

                if (!proposal.reason.isNullOrBlank()) {
                    DetailLine("Proposer Note", "\"${proposal.reason}\"")
                }

                DetailLine("Expires At", "${proposal.expiresAt.take(19).replace('T', ' ')} UTC")

                if (proposal.status == "APPROVED") {
                    HorizontalDivider()
                    Text(
                        text = "Approved on Revision #${proposal.approvedRevision ?: proposal.currentRevision}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.Bold
                    )
                    proposal.resultingPermissionId?.let { DetailLine("Generated Permission ID", it) }
                    proposal.resultingSessionId?.let { DetailLine("Generated Session ID", it) }
                    proposal.resultingScheduleId?.let { DetailLine("Generated Schedule ID", it) }
                }
            }
        }

        // 2. Action Buttons for Active Negotiation
        if (isPendingOrCountered) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isRecipient) {
                            "Your Response Required (Revision #${proposal.currentRevision})"
                        } else {
                            "Awaiting @${proposal.recipient.username}'s Response (Revision #${proposal.currentRevision})"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = actionNote,
                        onValueChange = { actionNote = it },
                        label = { Text("Response Note / Reason (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isRecipient) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onAcceptProposal(
                                        proposal.proposalId,
                                        proposal.currentRevision,
                                        true
                                    )
                                },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Approve Rev #${proposal.currentRevision}", color = Color.White)
                            }

                            OutlinedButton(
                                onClick = {
                                    onRejectProposal(
                                        proposal.proposalId,
                                        proposal.currentRevision,
                                        actionNote.trim().takeIf { it.isNotEmpty() }
                                    )
                                },
                                enabled = !isLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reject")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCounterofferForm = !showCounterofferForm },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (showCounterofferForm) "Hide Counteroffer" else "Make Counteroffer")
                        }

                        if (isProposer || isRecipient) {
                            OutlinedButton(
                                onClick = {
                                    onCancelProposal(
                                        proposal.proposalId,
                                        proposal.currentRevision,
                                        actionNote.trim().takeIf { it.isNotEmpty() }
                                    )
                                },
                                enabled = !isLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel Proposal", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // 3. Counteroffer Builder Card
        if (showCounterofferForm && isPendingOrCountered) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Create Immutable Counteroffer (Rev #${proposal.currentRevision + 1})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = counterGoal,
                        onValueChange = { counterGoal = it },
                        label = { Text("Counteroffer Goal") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = counterDurationText,
                        onValueChange = { counterDurationText = it },
                        label = { Text("Counteroffer Duration (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("BLOCK_SELECTED", "STRICT_ALLOWLIST", "WHITELIST_ONLY").forEach { mode ->
                            FilterChip(
                                selected = counterRuleMode == mode,
                                onClick = { counterRuleMode = mode },
                                label = { Text(mode) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = counterBlockedText,
                        onValueChange = { counterBlockedText = it },
                        label = { Text("Blocked Packages (comma-separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = counterAllowedText,
                        onValueChange = { counterAllowedText = it },
                        label = { Text("Allowed Study Packages (comma-separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = counterOverlay, onCheckedChange = { counterOverlay = it })
                        Text("overlayStatusBar", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = counterDisableEmergency,
                            onCheckedChange = { counterDisableEmergency = it }
                        )
                        Text("disableEmergencyExit", style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedTextField(
                        value = counterReason,
                        onValueChange = { counterReason = it },
                        label = { Text("Counteroffer Explanation") },
                        placeholder = { Text("e.g. Let's do 30 minutes instead of 45") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (localError != null) {
                        Text(
                            text = localError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            val dur = counterDurationText.toIntOrNull()
                            if (dur == null || dur !in 5..720) {
                                localError = "Duration must be between 5 and 720 minutes."
                                return@Button
                            }
                            if (counterGoal.trim().isEmpty()) {
                                localError = "Goal cannot be empty."
                                return@Button
                            }
                            localError = null
                            val blockedList = counterBlockedText.split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                            val allowedList = counterAllowedText.split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()

                            onCounterofferProposal(
                                proposal.proposalId,
                                CounterofferProposalRequestDto(
                                    expectedRevisionNumber = proposal.currentRevision,
                                    goal = counterGoal.trim(),
                                    durationMinutes = dur,
                                    blockingConfiguration = BlockingConfigurationDto(
                                        schemaVersion = 1,
                                        ruleMode = counterRuleMode,
                                        allowedCategories = listOf("CALLS", "STUDY_APPS"),
                                        overlayStatusBar = counterOverlay,
                                        disableEmergencyExit = counterDisableEmergency,
                                        blockedPackages = blockedList.takeIf { it.isNotEmpty() },
                                        allowedStudyPackages = allowedList.takeIf { it.isNotEmpty() }
                                    ),
                                    reason = counterReason.trim().takeIf { it.isNotEmpty() }
                                )
                            )
                            showCounterofferForm = false
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Submit Counteroffer (Revision #${proposal.currentRevision + 1})")
                    }
                }
            }
        }

        // 4. Immutable Revision History Timeline
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Immutable Revision History (${proposal.revisions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (proposal.revisions.isEmpty()) {
                    Text("No revision records returned.", style = MaterialTheme.typography.bodySmall)
                } else {
                    proposal.revisions.forEach { rev ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Revision #${rev.revisionNumber} by @${rev.proposer.username}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    StatusChip(status = rev.status)
                                }
                                Text(
                                    text = "Goal: ${rev.normalizedConfiguration.goal} • Duration: ${rev.normalizedConfiguration.durationMinutes} min • Mode: ${rev.normalizedConfiguration.blockingConfiguration.ruleMode}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!rev.reason.isNullOrBlank()) {
                                    Text(
                                        text = "Note: \"${rev.reason}\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "Created: ${rev.createdAt.take(19).replace('T', ' ')} UTC",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.58f)
        )
    }
}
