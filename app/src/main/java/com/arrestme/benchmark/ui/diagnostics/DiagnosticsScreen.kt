package com.arrestme.benchmark.ui.diagnostics

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arrestme.benchmark.data.local.DiagnosticEventEntity
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import com.arrestme.benchmark.ui.navigation.formatWallClockTime

@Composable
fun DiagnosticsScreen(
    events: List<DiagnosticEventEntity>,
    transitions: List<SessionTransitionEntity>,
    onClearLogs: () -> Unit
) {
    var selectedSeverity by rememberSaveable { mutableStateOf("ALL") }
    var searchText by rememberSaveable { mutableStateOf("") }
    var showTransitionsTab by rememberSaveable { mutableStateOf(false) }

    val filteredEvents = remember(events, selectedSeverity, searchText) {
        events.filter { e ->
            val sevOk = selectedSeverity == "ALL" || e.severity == selectedSeverity
            val queryOk = searchText.isBlank() ||
                e.eventType.contains(searchText, ignoreCase = true) ||
                (e.packageName?.contains(searchText, ignoreCase = true) == true) ||
                e.details.contains(searchText, ignoreCase = true) ||
                (e.sessionId?.contains(searchText, ignoreCase = true) == true)
            sevOk && queryOk
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Diagnostic Logs",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onClearLogs) {
                Text("Clear Events")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showTransitionsTab,
                onClick = { showTransitionsTab = false },
                label = { Text("Engine & Anti-Cheating (${events.size})") }
            )
            FilterChip(
                selected = showTransitionsTab,
                onClick = { showTransitionsTab = true },
                label = { Text("State Transitions (${transitions.size})") }
            )
        }

        if (!showTransitionsTab) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("Filter by event, package, or session ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "INFO", "WARNING", "HIGH", "CRITICAL").forEach { sev ->
                    FilterChip(
                        selected = selectedSeverity == sev,
                        onClick = { selectedSeverity = sev },
                        label = { Text(sev) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredEvents, key = { it.id }) { e ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = e.eventType,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = e.severity,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (e.severity) {
                                        "CRITICAL", "HIGH" -> Color(0xFFF87171)
                                        "WARNING" -> Color(0xFFFBBF24)
                                        else -> Color(0xFF38BDF8)
                                    }
                                )
                            }
                            Text(
                                text = "${formatWallClockTime(e.timestampMs)} • session=${e.sessionId ?: "-"} • pkg=${e.packageName ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = e.details,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transitions, key = { it.id }) { t ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${t.fromState} -> ${t.toState}",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                            Text(
                                text = "${formatWallClockTime(t.timestampMs)} • ${t.sessionId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = t.reason,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
