package com.arrestme.benchmark.ui.home

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arrestme.benchmark.core.backend.ActiveBackendConfig
import com.arrestme.benchmark.core.backend.BackendAuthState
import com.arrestme.benchmark.core.permissions.PermissionStatusSnapshot
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.ui.navigation.BenchmarkRoute
import com.arrestme.benchmark.ui.navigation.formatDurationMmSs

@Composable
fun HomeScreen(
    permissions: PermissionStatusSnapshot,
    activeSession: ActiveSessionSnapshot?,
    remainingMs: Long,
    appRules: List<AppRuleEntity>,
    sessions: List<SessionEntity>,
    backendConfig: ActiveBackendConfig,
    backendAuthState: BackendAuthState,
    onNavigate: (BenchmarkRoute) -> Unit
) {
    val blockedCount = appRules.count { it.ruleType == AppRuleType.BLOCKED.name }
    val allowedCount = appRules.count { it.ruleType == AppRuleType.ALLOWED_STUDY.name }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Arrest Me V0.3 — Focus & Cloud",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Offline-first Android Arrest Engine with optional Fastify cloud account & device integration.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Engine Readiness Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (permissions.isCoreBlockingReady) {
                    Color(0xFF064E3B)
                } else {
                    Color(0xFF7F1D1D)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (permissions.isCoreBlockingReady) {
                        "Core Blocking Engine: READY"
                    } else {
                        "Core Blocking Engine: PERMISSIONS REQUIRED"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Overlay: ${if (permissions.overlayGranted) "Granted" else "Missing"} • " +
                        "Accessibility: ${permissions.accessibilityDisplayLabel} • " +
                        "Usage Access: ${if (permissions.usageAccessGranted) "Granted" else "Missing"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onNavigate(BenchmarkRoute.PERMISSIONS) }) {
                    Text("Open Permissions Dashboard")
                }
            }
        }

        // Cloud Account & Server Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Cloud Account: " + when (backendAuthState) {
                        is BackendAuthState.Offline -> "Local-Only (Offline)"
                        is BackendAuthState.Unauthenticated -> "Not Signed In"
                        is BackendAuthState.Authenticated ->
                            "@${backendAuthState.credentials.username ?: "Signed In"}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (backendConfig.isLocalOnly) {
                        "Operating 100% locally on-device. Zero network traffic."
                    } else {
                        val devLabel = (backendAuthState as? BackendAuthState.Authenticated)
                            ?.credentials?.registeredDevice?.deviceName
                        "Server: ${backendConfig.effectiveBaseUrl}" +
                            (devLabel?.let { " • Device: $it" } ?: "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onNavigate(BenchmarkRoute.ACCOUNT) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (backendAuthState) {
                                is BackendAuthState.Authenticated -> "Account & Server"
                                is BackendAuthState.Unauthenticated -> "Sign In / Server"
                                is BackendAuthState.Offline -> "Cloud Server"
                            }
                        )
                    }
                    Button(
                        onClick = { onNavigate(BenchmarkRoute.PARTNERS) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Partners & Proposals")
                    }
                }
            }
        }

        // Current Session Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val stateLabel = activeSession?.state?.name ?: SessionState.IDLE.name
                Text(
                    text = "Current Session State: $stateLabel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (activeSession != null && activeSession.state.isInProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Goal: ${activeSession.goal}")
                    Text(
                        text = "Remaining: ${formatDurationMmSs(remainingMs)}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Default Benchmark: Self Arrest • 15 min • \"Finish studying chapter 3\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onNavigate(BenchmarkRoute.SESSION) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (activeSession?.state?.isInProgress == true) {
                            "View Active Session"
                        } else {
                            "Configure / Start Arrest Session"
                        }
                    )
                }
            }
        }

        // Rules & History Summary Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Benchmark Configuration & Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Blocked Apps Selected: $blockedCount  |  Whitelisted / Allowed Apps: $allowedCount")
                Text("Recorded Benchmark Sessions: ${sessions.size}")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onNavigate(BenchmarkRoute.APPS) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Select Apps")
                    }
                    OutlinedButton(
                        onClick = { onNavigate(BenchmarkRoute.BENCHMARK) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Results")
                    }
                    OutlinedButton(
                        onClick = { onNavigate(BenchmarkRoute.DIAGNOSTICS) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Logs")
                    }
                }
            }
        }
    }
}
