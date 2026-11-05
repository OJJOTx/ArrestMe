package com.arrestme.benchmark.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.data.remote.dto.TemporaryGrantDto

@Composable
fun ActiveArrestCommunicationPanel(
    activeSession: ActiveSessionSnapshot?,
    activeGrants: List<TemporaryGrantDto>,
    isSending: Boolean,
    onOpenFullChat: () -> Unit,
    onRequestEarlyRelease: (plea: String) -> Unit,
    onRequestTemporaryAccess: (packageName: String, minutes: Int, plea: String) -> Unit,
    onRevokeGrant: (grantId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showQuickPleaDialog by rememberSaveable { mutableStateOf(false) }
    var pleaText by rememberSaveable { mutableStateOf("") }
    var showTempAccessDialog by rememberSaveable { mutableStateOf(false) }
    var targetPackage by rememberSaveable { mutableStateOf("") }
    var tempMinutes by rememberSaveable { mutableStateOf(10) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(16.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Partner Control & Requests",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onOpenFullChat,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open Chat", fontSize = 11.sp)
                }
            }

            // Display any active temporary grants running right now
            if (activeGrants.isNotEmpty()) {
                Surface(
                    color = Color(0xFF065F46).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Active Temporary Access Grants:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        activeGrants.forEach { grant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${grant.targetPackage} (${grant.durationMinutes}m)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = { onRevokeGrant(grant.id) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("Revoke", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Quick Request Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showQuickPleaDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Plea Early Release", fontSize = 11.sp)
                }

                Button(
                    onClick = { showTempAccessDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Temp App Access", fontSize = 11.sp)
                }
            }
        }
    }

    if (showQuickPleaDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQuickPleaDialog = false },
            title = { Text("Request Early Release") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Your controlling partner must approve early release before this session will terminate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pleaText,
                        onValueChange = { pleaText = it },
                        label = { Text("Reason / Explanation") },
                        placeholder = { Text("Why do you need release now?") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRequestEarlyRelease(pleaText.trim())
                        showQuickPleaDialog = false
                        pleaText = ""
                    },
                    enabled = !isSending
                ) {
                    if (isSending) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Submit Plea")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showQuickPleaDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTempAccessDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTempAccessDialog = false },
            title = { Text("Request Temporary App Access") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Partner can grant 5 to 30 minutes of temporary access. App automatically locks when expired.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetPackage,
                        onValueChange = { targetPackage = it },
                        label = { Text("Package Name") },
                        placeholder = { Text("e.g. com.google.android.gm") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 30).forEach { mins ->
                            androidx.compose.material3.FilterChip(
                                selected = tempMinutes == mins,
                                onClick = { tempMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRequestTemporaryAccess(targetPackage.trim(), tempMinutes, "Temporary emergency access requested")
                        showTempAccessDialog = false
                        targetPackage = ""
                    },
                    enabled = targetPackage.isNotBlank() && !isSending
                ) {
                    Text("Send Request")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTempAccessDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
