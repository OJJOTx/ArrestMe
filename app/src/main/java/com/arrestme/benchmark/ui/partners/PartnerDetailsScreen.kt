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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.data.remote.dto.PartnerPermissionDto
import com.arrestme.benchmark.data.remote.dto.PartnerProfileDto

@Composable
fun PartnerDetailsScreen(
    partnershipId: String,
    profile: PartnerProfileDto?,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadProfile: (String) -> Unit,
    onOpenApprovalSettings: (String) -> Unit,
    onOpenCreateProposal: (String) -> Unit,
    onOpenNegotiationDetails: (String) -> Unit,
    onRevokePermission: (permissionId: String, partnershipId: String) -> Unit,
    onRemovePartner: (partnershipId: String) -> Unit,
    onBlockPartner: (partnershipId: String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(partnershipId) {
        onLoadProfile(partnershipId)
    }

    val scrollState = rememberScrollState()

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
                Text(
                    text = "Partner Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = { onLoadProfile(partnershipId) },
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

        if (profile == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading partner profile...")
                    } else {
                        Text("Partner profile unavailable.")
                    }
                }
            }
            return@Column
        }

        val partner = profile.partner

        // 1. Partner Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = partner.displayName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = partner.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "@${partner.username}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusChip(status = profile.relationshipStatus)
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onOpenCreateProposal(partnershipId) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Arrest Proposal")
                    }

                    OutlinedButton(
                        onClick = { onOpenApprovalSettings(partnershipId) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Approval Policy")
                    }
                }
            }
        }

        // 2. Directional Approval Policies Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Directional Approval Policies",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val myPol = profile.myApprovalPolicyForPartner
                val theirPol = profile.partnerApprovalPolicyForMe

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "When @${partner.username} arrests YOUR device:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (myPol.requireApprovalBeforeArrest) {
                                "requireApprovalBeforeArrest = TRUE (Every arrest requires your explicit proposal approval)"
                            } else {
                                "requireApprovalBeforeArrest = FALSE (Pre-authorized starts allowed only within active standing limits • ${myPol.activeStandingAuthorizationsCount} standing auth active)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (myPol.requireApprovalBeforeArrest) Color(0xFF6EE7B7) else Color(0xFFFCD34D)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "When YOU arrest @${partner.username}'s device:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (theirPol.requireApprovalBeforeArrest) {
                                "requireApprovalBeforeArrest = TRUE (Partner must approve each proposal before execution)"
                            } else {
                                "requireApprovalBeforeArrest = FALSE (${theirPol.activeStandingAuthorizationsCount} standing authorization(s) granted to you)"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 3. Authorized Target Devices Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Target Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Your Active Devices (${profile.myDevices.size}):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (profile.myDevices.isEmpty()) {
                    Text("No active registered devices.", style = MaterialTheme.typography.bodySmall)
                } else {
                    profile.myDevices.forEach { d ->
                        Text(
                            text = "• ${d.deviceName} (${d.platform} • ${d.deviceStatus})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "@${partner.username}'s Active Devices (${profile.partnerDevices.size}):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (profile.partnerDevices.isEmpty()) {
                    Text(
                        text = "No active devices exposed yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    profile.partnerDevices.forEach { d ->
                        Text(
                            text = "• ${d.deviceName} (${d.platform} • ${d.deviceStatus})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 4. Permissions Granted TO This Partner (You -> Partner)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Authorizations Granted TO @${partner.username} (${profile.permissionsGrantedToPartner.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (profile.permissionsGrantedToPartner.isEmpty()) {
                    Text(
                        text = "You have not granted any active arrest authorizations to @${partner.username}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    profile.permissionsGrantedToPartner.forEach { perm ->
                        PermissionSummaryItem(
                            permission = perm,
                            canRevoke = true,
                            onRevoke = { onRevokePermission(perm.permissionId, partnershipId) }
                        )
                    }
                }
            }
        }

        // 5. Permissions Received FROM This Partner (Partner -> You)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Authorizations Received FROM @${partner.username} (${profile.permissionsReceivedFromPartner.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (profile.permissionsReceivedFromPartner.isEmpty()) {
                    Text(
                        text = "You do not hold any active standing or one-time authorizations over @${partner.username}'s devices. Send an Arrest Proposal to request consent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    profile.permissionsReceivedFromPartner.forEach { perm ->
                        PermissionSummaryItem(
                            permission = perm,
                            canRevoke = false,
                            onRevoke = {}
                        )
                    }
                }
            }
        }

        // 6. Pending Proposals with this Partner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Active Negotiations (${profile.pendingProposals.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (profile.pendingProposals.isEmpty()) {
                    Text(
                        text = "No pending proposals with @${partner.username}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    profile.pendingProposals.forEach { prop ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenNegotiationDetails(prop.proposalId) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                                        text = "Revision #${prop.currentRevision} • Duration: ${prop.normalizedConfiguration.durationMinutes} min",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                StatusChip(status = prop.status)
                            }
                        }
                    }
                }
            }
        }

        // 7. Partner Management Actions (Remove / Block)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Relationship Management",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Removing or blocking a partner immediately revokes all active permissions, cancels pending proposals, and cancels scheduled arrests.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onRemovePartner(partnershipId) },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Remove Partner")
                    }

                    Button(
                        onClick = { onBlockPartner(partnershipId) },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Block Partner", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSummaryItem(
    permission: PartnerPermissionDto,
    canRevoke: Boolean,
    onRevoke: () -> Unit
) {
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
                    text = if (permission.oneTimeOnly) "One-Time Approved Proposal" else "Standing Authorization",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusChip(status = if (permission.isCurrentlyActive) "ACTIVE" else "INACTIVE")
            }
            Text(
                text = "Target Device: ${permission.targetDevice.deviceName} • Max Duration: ${permission.maximumSessionDuration} min",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Modes: ${permission.blockingConfigLimits.allowedRuleModes.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Expires: ${permission.expiresAt.take(19).replace('T', ' ')} UTC",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            if (canRevoke && permission.revokedAt == null) {
                TextButton(onClick = onRevoke) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Revoke Authorization", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
