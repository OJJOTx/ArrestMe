package com.arrestme.benchmark.ui.partners

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.data.remote.dto.LiveSessionDashboardItemDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSessionDashboardScreen(
    currentUserId: String?,
    liveItems: List<LiveSessionDashboardItemDto>,
    localActiveSession: ActiveSessionSnapshot?,
    localRemainingMs: Long,
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onRequestRemoteEnd: (sessionId: String) -> Unit,
    onRequestRemoteSync: (sessionId: String) -> Unit,
    onUploadAppCatalog: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live Arrest Dashboard", fontWeight = FontWeight.Bold)
                        Text(
                            "Real-time partner session state & controls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onUploadAppCatalog) {
                        Icon(Icons.Default.Share, contentDescription = "Share Selectable App Catalog")
                    }
                    IconButton(onClick = onRefresh, enabled = !isSyncing) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Catalog sharing banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Controlled App Catalog",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Share non-system apps with accepted partners so they can configure allowed or blocked lists.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onUploadAppCatalog) {
                            Text("Share Apps", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (localActiveSession != null && localActiveSession.state.isInProgress) {
                item {
                    Text(
                        "THIS DEVICE — ACTIVE SESSION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = localActiveSession.sessionType.label,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = localActiveSession.state.name,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Goal: ${localActiveSession.goal}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            localActiveSession.controllingPartnerUsername?.let { p ->
                                Text(
                                    text = "Controlled by partner: @$p",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Remaining: ${formatDuration(localRemainingMs)}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Rule Mode: ${localActiveSession.ruleMode.name}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "CLOUD-COORDINATED LIVE SESSIONS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (liveItems.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No Active Cloud Sessions",
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "When you or your partner start an authorized remote arrest session, live status and controls appear here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(liveItems, key = { it.session.sessionId }) { item ->
                    LiveSessionDashboardCard(
                        item = item,
                        isCurrentActorInitiator = currentUserId != null && item.initiator.id == currentUserId,
                        onRequestRemoteEnd = { onRequestRemoteEnd(item.session.sessionId) },
                        onRequestRemoteSync = { onRequestRemoteSync(item.session.sessionId) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LiveSessionDashboardCard(
    item: LiveSessionDashboardItemDto,
    isCurrentActorInitiator: Boolean,
    onRequestRemoteEnd: () -> Unit,
    onRequestRemoteSync: () -> Unit
) {
    val phase = item.session.remoteExecutionPhase
    val phaseColor = when (phase) {
        "ACTIVE" -> Color(0xFF2E7D32)
        "QUEUED", "DELIVERED", "EXECUTING" -> Color(0xFFE65100)
        "COMPLETED" -> Color(0xFF1565C0)
        "FAILED", "EXPIRED" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.session.goal.ifBlank { "Focus Session" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Target: @${item.owner.username} • Device: ${item.targetDevice?.deviceName ?: "Android"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Initiated by: @${item.initiator.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .background(phaseColor.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = phase,
                        color = phaseColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Duration", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${item.session.requestedDurationMinutes} min", fontWeight = FontWeight.SemiBold)
                }
                Column {
                    Text("Remaining", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rem = item.remainingSeconds
                    val remText = if (rem != null && rem > 0) {
                        "${rem / 60}m ${rem % 60}s"
                    } else if (phase == "ACTIVE") {
                        "Ending..."
                    } else {
                        "-"
                    }
                    Text(remText, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                }
                Column {
                    Text("Engine State", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.session.localEngineState ?: "PENDING", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Mode: ${item.session.blockingConfiguration.ruleMode} • Status Bar: ${
                    if (item.session.blockingConfiguration.overlayStatusBar) "Blocked" else "Allowed"
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val blocked = item.session.blockingConfiguration.blockedPackages
            if (!blocked.isNullOrEmpty()) {
                Text(
                    text = "Blocked Apps: ${blocked.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            val allowed = item.session.blockingConfiguration.allowedStudyPackages
            if (!allowed.isNullOrEmpty()) {
                Text(
                    text = "Allowed Apps: ${allowed.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isCurrentActorInitiator && (phase == "QUEUED" || phase == "DELIVERED" || phase == "EXECUTING" || phase == "ACTIVE")) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onRequestRemoteSync) {
                        Text("Sync State", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRequestRemoteEnd,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("End Session", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(java.util.Locale.US, "%02d:%02d", min, sec)
}
