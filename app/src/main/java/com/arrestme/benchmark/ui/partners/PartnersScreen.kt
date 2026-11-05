package com.arrestme.benchmark.ui.partners

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.core.backend.BackendAuthState
import com.arrestme.benchmark.data.remote.dto.PartnerDiscoveryResponseDto
import com.arrestme.benchmark.data.remote.dto.PartnershipDto
import com.arrestme.benchmark.data.remote.dto.PendingCountsDto
import com.arrestme.benchmark.data.remote.dto.ProposalDto
import com.arrestme.benchmark.data.remote.dto.ScheduledArrestDto

@Composable
fun PartnersScreen(
    authState: BackendAuthState,
    partners: List<PartnershipDto>,
    pendingCounts: PendingCountsDto,
    myInviteCode: String?,
    discoveryResult: PartnerDiscoveryResponseDto?,
    proposals: List<ProposalDto>,
    scheduledArrests: List<ScheduledArrestDto>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onRegenerateInviteCode: () -> Unit,
    onDiscoverPartner: (username: String?, inviteCode: String?) -> Unit,
    onClearDiscovery: () -> Unit,
    onSendPartnerRequest: (username: String?, inviteCode: String?, recipientUserId: String?) -> Unit,
    onOpenPartnerRequests: () -> Unit,
    onOpenPartnerDetails: (partnershipId: String) -> Unit,
    onOpenNegotiationDetails: (proposalId: String) -> Unit,
    onOpenScheduledArrests: () -> Unit,
    onOpenLiveDashboard: () -> Unit,
    onOpenConversations: () -> Unit = {},
    onOpenChatForPartnership: (partnershipId: String) -> Unit = {},
    onOpenAccountSettings: () -> Unit
) {
    val scrollState = rememberScrollState()
    var searchByInviteCode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var localSearchError by remember { mutableStateOf<String?>(null) }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Partners & Negotiations",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Directional accountability, explicit arrest authorizations, and versioned proposals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading && authState is BackendAuthState.Authenticated
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Refresh")
            }
        }

        // Core Principle Notice Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Friendship alone never grants device-control permissions. Every arrest requires an explicit directional authorization or approved proposal.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Offline / Unauthenticated state handling
        when (authState) {
            is BackendAuthState.Offline -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F).copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Offline Local-Only Mode Active",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Self Arrest works 100% offline. To discover partners, negotiate arrest proposals, or sync schedules, connect to a server in Account settings.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = onOpenAccountSettings) {
                            Text("Configure Cloud Server")
                        }
                    }
                }
                return@Column
            }
            is BackendAuthState.Unauthenticated -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sign In Required for Partner Features",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Sign in or create an ArrestMe account to add partners, configure approval policies, and negotiate arrest sessions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onOpenAccountSettings) {
                            Text("Sign In / Register")
                        }
                    }
                }
                return@Column
            }
            is BackendAuthState.Authenticated -> Unit
        }

        if (!errorMessage.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFCA5A5))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = errorMessage, style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss", color = Color(0xFFFCA5A5))
                    }
                }
            }
        }

        // Quick Navigation & Pending Counts Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val totalFriendReq = pendingCounts.incomingFriendRequestsCount + pendingCounts.outgoingFriendRequestsCount
            OutlinedButton(
                onClick = onOpenPartnerRequests,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Invites ($totalFriendReq)", fontSize = 11.sp)
            }

            val activeScheduleCount = scheduledArrests.count { it.status == "SCHEDULED" || it.status == "ACTIVE" }
            OutlinedButton(
                onClick = onOpenScheduledArrests,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Schedules ($activeScheduleCount)", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = onOpenConversations,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chat", fontSize = 11.sp)
            }

            Button(
                onClick = onOpenLiveDashboard,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Live", fontSize = 11.sp)
            }
        }

        // My Invitation Code Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Your Partner Invitation Code",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = myInviteCode ?: "Loading...",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Share this code with a trusted partner so they can invite you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRegenerateInviteCode, enabled = !isLoading) {
                    Text("Regenerate")
                }
            }
        }

        // Partner Discovery Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Discover & Add Partner",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !searchByInviteCode,
                        onClick = {
                            searchByInviteCode = false
                            localSearchError = null
                            onClearDiscovery()
                        },
                        label = { Text("Exact Username") }
                    )
                    FilterChip(
                        selected = searchByInviteCode,
                        onClick = {
                            searchByInviteCode = true
                            localSearchError = null
                            onClearDiscovery()
                        },
                        label = { Text("Invitation Code") }
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        localSearchError = null
                    },
                    label = {
                        Text(if (searchByInviteCode) "Invitation Code (e.g. ARST-XXXX-XXXX)" else "Exact Username")
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    isError = localSearchError != null,
                    supportingText = localSearchError?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val trimmed = searchQuery.trim()
                            if (trimmed.length < 3) {
                                localSearchError = "Enter at least 3 characters."
                            } else if (searchByInviteCode) {
                                onDiscoverPartner(null, trimmed.uppercase())
                            } else {
                                onDiscoverPartner(trimmed.lowercase(), null)
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Search")
                    }

                    OutlinedButton(
                        onClick = {
                            val trimmed = searchQuery.trim()
                            if (trimmed.length < 3) {
                                localSearchError = "Enter a valid username or invite code first."
                            } else if (searchByInviteCode) {
                                onSendPartnerRequest(null, trimmed.uppercase(), null)
                            } else {
                                onSendPartnerRequest(trimmed.lowercase(), null, null)
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send Invite")
                    }
                }

                if (discoveryResult != null) {
                    HorizontalDivider()
                    if (!discoveryResult.found || discoveryResult.user == null) {
                        Text(
                            text = "No user found matching that query.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val user = discoveryResult.user
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${user.displayName} (@${user.username})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Relationship State: ${discoveryResult.relationshipState}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            when (discoveryResult.relationshipState) {
                                "NONE", "REJECTED", "REMOVED" -> {
                                    Button(
                                        onClick = {
                                            onSendPartnerRequest(user.username, null, user.id)
                                        },
                                        enabled = !isLoading
                                    ) {
                                        Text("Invite Partner")
                                    }
                                }
                                "ACCEPTED" -> {
                                    val pid = discoveryResult.partnershipId
                                    if (pid != null) {
                                        OutlinedButton(onClick = { onOpenPartnerDetails(pid) }) {
                                            Text("View Profile")
                                        }
                                    }
                                }
                                "PENDING" -> {
                                    OutlinedButton(onClick = onOpenPartnerRequests) {
                                        Text("Pending")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pending / Recent Negotiations Section
        if (proposals.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Arrest Proposals & Negotiations",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val activePropCount = pendingCounts.incomingProposalsCount + pendingCounts.outgoingProposalsCount
                        if (activePropCount > 0) {
                            Badge { Text("$activePropCount active") }
                        }
                    }

                    proposals.take(6).forEach { prop ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenNegotiationDetails(prop.proposalId) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${prop.normalizedConfiguration.goal} (${prop.requestType})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Owner: @${prop.owner.username} • Controller: @${prop.partner.username} • Rev #${prop.currentRevision}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StatusChip(status = prop.status)
                            }
                        }
                    }
                }
            }
        }

        // Accepted Partners List Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Accepted Partners (${partners.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (partners.isEmpty()) {
                    Text(
                        text = "You have no accepted partners yet. Search by username or invitation code above to send a partner request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    partners.forEach { p ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPartnerDetails(p.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = p.partner.displayName.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = p.partner.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "@${p.partner.username} • Relationship: ${p.state}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { onOpenChatForPartnership(p.id) }) {
                                        Text("Chat")
                                    }
                                    OutlinedButton(onClick = { onOpenPartnerDetails(p.id) }) {
                                        Text("Profile")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val bgColor = when (status.uppercase()) {
        "APPROVED", "ACCEPTED", "ACTIVE" -> Color(0xFF065F46)
        "PENDING", "COUNTEROFFERED", "SCHEDULED" -> Color(0xFF92400E)
        "REJECTED", "CANCELLED", "EXPIRED", "REVOKED", "BLOCKED" -> Color(0xFF7F1D1D)
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = status,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
