package com.arrestme.benchmark.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arrestme.benchmark.core.permissions.PermissionStatusSnapshot
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionConfig
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.core.session.SessionType
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.ui.navigation.BenchmarkRoute
import com.arrestme.benchmark.ui.navigation.formatDurationMmSs
import com.arrestme.benchmark.ui.navigation.formatWallClockTime

@Composable
fun ActiveSessionScreen(
    activeSession: ActiveSessionSnapshot?,
    remainingMs: Long,
    permissions: PermissionStatusSnapshot,
    appRules: List<AppRuleEntity>,
    onStartSession: (SessionConfig) -> Unit,
    onPauseSession: () -> Unit,
    onResumeSession: () -> Unit,
    onEmergencyStop: () -> Unit,
    onResetIdle: () -> Unit,
    onNavigate: (BenchmarkRoute) -> Unit
) {
    var goalText by rememberSaveable { mutableStateOf("Finish studying chapter 3") }
    var durationMinutes by rememberSaveable { mutableIntStateOf(15) }
    var allowCalls by rememberSaveable { mutableStateOf(true) }
    var allowStudyApps by rememberSaveable { mutableStateOf(true) }
    var ruleModeName by rememberSaveable { mutableStateOf(BlockingRuleMode.BLOCK_SELECTED.name) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }

    val blockedCount = appRules.count { it.ruleType == AppRuleType.BLOCKED.name }
    val allowedStudyCount = appRules.count { it.ruleType == AppRuleType.ALLOWED_STUDY.name }
    val selectedRuleMode = runCatching { BlockingRuleMode.valueOf(ruleModeName) }
        .getOrDefault(BlockingRuleMode.BLOCK_SELECTED)

    if (showEmergencyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmergencyConfirm = false },
            title = { Text("Confirm Emergency Exit") },
            text = {
                Text(
                    "Stop the current Self Arrest session immediately? This transition will be logged as EMERGENCY_STOPPED in Benchmark Results."
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    onClick = {
                        showEmergencyConfirm = false
                        onEmergencyStop()
                    }
                ) {
                    Text("Emergency Stop", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Arrest Session Controller",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (activeSession != null && activeSession.state.isInProgress) {
            // ACTIVE / PREPARING / PAUSED / INTERRUPTED VIEW
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (activeSession.state) {
                        SessionState.ACTIVE -> Color(0xFF0C4A6E)
                        SessionState.INTERRUPTED -> Color(0xFF7F1D1D)
                        else -> MaterialTheme.colorScheme.surface
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "State: ${activeSession.state.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatDurationMmSs(remainingMs),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Session ID: ${activeSession.sessionId}")
                    Text("Type: ${activeSession.sessionType.label}")
                    Text("Goal: ${activeSession.goal}")
                    Text(
                        "Allowed Categories: ${
                            activeSession.allowedCategories.joinToString(", ") { it.label }
                        }"
                    )
                    Text("Rule Mode: ${activeSession.ruleMode.label}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Persisted Start: ${formatWallClockTime(activeSession.startTimestampMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Persisted End:   ${formatWallClockTime(activeSession.endTimestampMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (activeSession.state == SessionState.INTERRUPTED) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Session Interrupted: verify Overlay & Accessibility permissions in Permissions Dashboard, then tap Resume.",
                            color = Color(0xFFFDE047),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = { onNavigate(BenchmarkRoute.PERMISSIONS) }) {
                            Text("Fix Permissions")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeSession.state == SessionState.ACTIVE) {
                            OutlinedButton(
                                onClick = onPauseSession,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pause")
                            }
                        } else if (activeSession.state == SessionState.PAUSED ||
                            activeSession.state == SessionState.INTERRUPTED
                        ) {
                            Button(
                                onClick = onResumeSession,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Resume Session")
                            }
                        }

                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            onClick = { showEmergencyConfirm = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Emergency Exit", color = Color.White)
                        }
                    }
                }
            }
        } else {
            // Terminal session banner if just finished
            if (activeSession != null && activeSession.state.isTerminal && activeSession.state != SessionState.IDLE) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Last Session (${activeSession.sessionId}): ${activeSession.state.name}",
                            fontWeight = FontWeight.Bold
                        )
                        Text("Goal: ${activeSession.goal}")
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onNavigate(BenchmarkRoute.BENCHMARK) }) {
                                Text("View Results")
                            }
                            TextButton(onClick = onResetIdle) {
                                Text("Reset to IDLE")
                            }
                        }
                    }
                }
            }

            if (!permissions.isCoreBlockingReady) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Missing Core Blocking Permissions",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Grant Overlay + Accessibility before starting a benchmark session so blocked apps trigger the overlay.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(onClick = { onNavigate(BenchmarkRoute.PERMISSIONS) }) {
                            Text("Configure Permissions")
                        }
                    }
                }
            }

            if (selectedRuleMode == BlockingRuleMode.BLOCK_SELECTED && blockedCount == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "No Blocked Applications Selected Yet",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFDE047)
                        )
                        Text(
                            text = "Select at least 2 applications to block for testing Scenarios #2–#6.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(onClick = { onNavigate(BenchmarkRoute.APPS) }) {
                            Text("Select Blocked & Study Apps")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Session Configuration (Self Arrest)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        label = { Text("Session Goal") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Duration (Default Benchmark = 15 min; 1 min preset for fast expiry test):")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 5, 15, 30).forEach { mins ->
                            FilterChip(
                                selected = durationMinutes == mins,
                                onClick = { durationMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }

                    Text("Allowed Categories:")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowCalls, onCheckedChange = { allowCalls = it })
                        Text("Calls (Phone Dialer always protected)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowStudyApps, onCheckedChange = { allowStudyApps = it })
                        Text("Study Apps ($allowedStudyCount configured)")
                    }

                    Text("Blocking Rule Mode:")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BlockingRuleMode.entries.forEach { mode ->
                            FilterChip(
                                selected = selectedRuleMode == mode,
                                onClick = { ruleModeName = mode.name },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text(
                        text = selectedRuleMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            val categories = buildSet {
                                if (allowCalls) add(AllowedCategory.CALLS)
                                if (allowStudyApps) add(AllowedCategory.STUDY_APPS)
                            }
                            onStartSession(
                                SessionConfig(
                                    sessionType = SessionType.SELF_ARREST,
                                    durationMinutes = durationMinutes,
                                    goal = goalText.ifBlank { "Finish studying chapter 3" },
                                    allowedCategories = categories,
                                    ruleMode = selectedRuleMode
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Self Arrest ($durationMinutes min)")
                    }
                }
            }
        }
    }
}
