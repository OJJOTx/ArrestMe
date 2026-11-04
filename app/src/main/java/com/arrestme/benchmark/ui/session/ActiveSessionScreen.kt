package com.arrestme.benchmark.ui.session

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

@OptIn(ExperimentalLayoutApi::class)
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
    var overlayStatusBar by rememberSaveable { mutableStateOf(false) }
    var disableEmergencyExit by rememberSaveable { mutableStateOf(false) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }

    val blockedCount = appRules.count { it.ruleType == AppRuleType.BLOCKED.name }
    val allowedStudyCount = appRules.count { it.ruleType == AppRuleType.ALLOWED_STUDY.name }
    val whitelistedLabels = remember(appRules) {
        appRules.filter { it.ruleType == AppRuleType.ALLOWED_STUDY.name }
            .map { it.appLabel }
            .sorted()
    }
    val selectedRuleMode = runCatching { BlockingRuleMode.valueOf(ruleModeName) }
        .getOrDefault(BlockingRuleMode.BLOCK_SELECTED)

    if (showEmergencyConfirm && activeSession?.disableEmergencyExit != true) {
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
                    Text("Rule Mode: ${activeSession.ruleMode.label}")
                    if (activeSession.ruleMode != BlockingRuleMode.WHITELIST_ONLY) {
                        Text(
                            "Allowed Categories: ${
                                activeSession.allowedCategories.joinToString(", ") { it.label }
                            }"
                        )
                    }
                    Text(
                        "Status Bar & Notification Overlay: ${
                            if (activeSession.overlayStatusBar) "Enabled" else "Standard"
                        }"
                    )
                    Text(
                        "Emergency Exit: ${
                            if (activeSession.disableEmergencyExit) "Disabled (Strict Lock)" else "Enabled"
                        }"
                    )
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
                    if (activeSession.disableEmergencyExit) {
                        if (activeSession.state == SessionState.INTERRUPTED) {
                            Button(
                                onClick = onResumeSession,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Resume Session")
                            }
                        }
                        Text(
                            text = "Emergency Exit and Pause are disabled for this session. Session unlocks automatically at 00:00.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFDE047)
                        )
                    } else {
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
                            text = "Grant Overlay + Accessibility before starting a session so the Arrest UI can enforce restrictions.",
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
                            text = "Select applications to block in the Apps screen, or switch to Whitelist Mode.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(onClick = { onNavigate(BenchmarkRoute.APPS) }) {
                            Text("Select Blocked & Whitelist Apps")
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

                    Text("Duration:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 5, 15, 30, 60).forEach { mins ->
                            FilterChip(
                                selected = durationMinutes == mins,
                                onClick = { durationMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }

                    Text("Blocking Rule Mode:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    if (selectedRuleMode == BlockingRuleMode.WHITELIST_ONLY) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Whitelisted Apps ($allowedStudyCount selected)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = if (whitelistedLabels.isEmpty()) {
                                        "None selected — Arrest UI will cover everything (including Launcher) until timer ends. Go to Apps to whitelist Calculator, Phone, etc."
                                    } else {
                                        "Arrest UI hides ONLY in: ${whitelistedLabels.joinToString(", ")}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { onNavigate(BenchmarkRoute.APPS) }) {
                                    Text("Choose Whitelisted Apps")
                                }
                            }
                        }
                    } else {
                        Text("Allowed Categories:")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowCalls, onCheckedChange = { allowCalls = it })
                            Text("Calls (Phone Dialer always protected)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowStudyApps, onCheckedChange = { allowStudyApps = it })
                            Text("Whitelisted / Study Apps ($allowedStudyCount configured)")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Option: Overlay Status Bar & Notification Shade
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Overlay Status Bar & Notifications",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Extends Arrest UI edge-to-edge over the status bar and hides/collapses the notification shade.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = overlayStatusBar,
                            onCheckedChange = { overlayStatusBar = it }
                        )
                    }

                    // Option: Disable Emergency Exit
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Disable Emergency Exit",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Removes the Emergency Exit option so the session cannot be stopped before the timer ends.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (disableEmergencyExit) Color(0xFFFCA5A5) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = disableEmergencyExit,
                            onCheckedChange = { disableEmergencyExit = it }
                        )
                    }

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
                                    ruleMode = selectedRuleMode,
                                    overlayStatusBar = overlayStatusBar,
                                    disableEmergencyExit = disableEmergencyExit
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
