package com.arrestme.benchmark.ui.benchmark

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.ui.navigation.formatWallClockTime
import java.util.Locale

@Composable
fun BenchmarkScreen(
    sessions: List<SessionEntity>,
    onReconcileUsageStats: () -> Unit,
    onSaveJsonToUri: (Uri) -> Unit,
    onShareJson: ((Intent) -> Unit) -> Unit
) {
    val context = LocalContext.current

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            onSaveJsonToUri(uri)
        }
    }

    val totalAttempts = sessions.sumOf { it.blockedAttempts }
    val totalSuccess = sessions.sumOf { it.successfulBlocks }
    val totalFailed = sessions.sumOf { it.failedBlocks }
    val totalActivations = sessions.sumOf { it.overlayActivations }
    val avgLatency = if (totalActivations > 0) {
        sessions.sumOf { it.totalOverlayLatencyMs } / totalActivations
    } else {
        null
    }
    val totalInterruptions = sessions.sumOf { it.serviceInterruptions }
    val totalPermFailures = sessions.sumOf { it.permissionFailures }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Benchmark Results",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        saveDocumentLauncher.launch(
                            "arrest_me_benchmark_${System.currentTimeMillis()}.json"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save JSON")
                }
                OutlinedButton(
                    onClick = {
                        onShareJson { chooser ->
                            runCatching { context.startActivity(chooser) }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Share JSON")
                }
                OutlinedButton(
                    onClick = onReconcileUsageStats,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sync Usage")
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C4A6E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Aggregate Engine Summary (${sessions.size} Sessions)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Blocking Attempts: $totalAttempts")
                    Text("Successful Blocks: $totalSuccess  |  Failed Blocks: $totalFailed")
                    Text(
                        "Avg Overlay Activation Latency: ${
                            avgLatency?.let { String.format(Locale.US, "%.2f ms", it) } ?: "N/A"
                        }"
                    )
                    Text("Service Interruptions: $totalInterruptions  |  Permission Failures: $totalPermFailures")
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No benchmark sessions recorded yet. Start a Self Arrest session to collect telemetry.")
                    }
                }
            }
        } else {
            items(sessions, key = { it.sessionId }) { s ->
                SessionBenchmarkCard(s)
            }
        }
    }
}

@Composable
private fun SessionBenchmarkCard(s: SessionEntity) {
    val configuredMin = s.configuredDurationMs / 60_000L
    val actualDurationSec = ((s.completedOrStoppedAtMs ?: System.currentTimeMillis()) - s.startTimestampMs)
        .coerceAtLeast(0L) / 1000L

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s.sessionId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = s.state,
                    fontWeight = FontWeight.Bold,
                    color = when (s.state) {
                        "COMPLETED" -> Color(0xFF34D399)
                        "ACTIVE" -> Color(0xFF38BDF8)
                        else -> Color(0xFFFBBF24)
                    }
                )
            }
            Text("Goal: ${s.goal}", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Start: ${formatWallClockTime(s.startTimestampMs)} • Configured: ${configuredMin}m • Elapsed: ${actualDurationSec}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("Blocking Attempts: ${s.blockedAttempts}")
            Text("Successful Blocks: ${s.successfulBlocks}  |  Failed Blocks: ${s.failedBlocks}")
            Text("Overlay Activations: ${s.overlayActivations}")
            Text(
                text = "Overlay Activation Latency: avg=${fmtMs(s.avgOverlayLatencyMs)} | min=${fmtMs(s.minOverlayLatencyMs)} | max=${fmtMs(s.maxOverlayLatencyMs)} | last=${fmtMs(s.lastOverlayLatencyMs)}"
            )
            Text("Service Interruptions: ${s.serviceInterruptions}  |  Permission Failures: ${s.permissionFailures}")
            Text("Rapid Switch Bursts: ${s.rapidSwitchEvents}")
            Text("Completion Status: ${s.state}${s.completionReason?.let { " ($it)" } ?: ""}")

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "UsageStats Provenance: ${s.usageDataConfidence}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF38BDF8)
            )
            Text(
                text = "UsageStats Blocked Resumes: ${s.usageStatsConfirmedBlockedLaunches?.toString() ?: "Unavailable (No Usage Access)"} | " +
                    "Discrepancy: ${s.usageStatsDiscrepancyCount?.toString() ?: "N/A"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Perms Start: ${s.permissionsSnapshotStart}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (s.permissionsSnapshotEnd != null) {
                Text(
                    text = "Perms End:   ${s.permissionsSnapshotEnd}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun fmtMs(value: Double?): String {
    return value?.let { String.format(Locale.US, "%.1fms", it) } ?: "-"
}
