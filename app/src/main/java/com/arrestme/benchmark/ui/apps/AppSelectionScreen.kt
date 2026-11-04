package com.arrestme.benchmark.ui.apps

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
import com.arrestme.benchmark.core.blocking.InstalledAppInfo
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType

private enum class AppListFilter(val label: String) {
    ALL("All"),
    BLOCKED("Blocked"),
    ALLOWED_STUDY("Study Allowlist"),
    PROTECTED("System Safe")
}

@Composable
fun AppSelectionScreen(
    installedApps: List<InstalledAppInfo>,
    isLoading: Boolean,
    appRules: List<AppRuleEntity>,
    onSetRule: (packageName: String, label: String, rule: AppRuleType?) -> Unit,
    onReloadApps: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(AppListFilter.ALL) }

    val rulesMap = remember(appRules) {
        appRules.associate { it.packageName to it.ruleType }
    }

    val filteredApps = remember(installedApps, rulesMap, searchQuery, selectedFilter) {
        installedApps.filter { app ->
            val matchesQuery = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            if (!matchesQuery) return@filter false

            val rule = rulesMap[app.packageName]
            when (selectedFilter) {
                AppListFilter.ALL -> true
                AppListFilter.BLOCKED -> rule == AppRuleType.BLOCKED.name
                AppListFilter.ALLOWED_STUDY -> rule == AppRuleType.ALLOWED_STUDY.name
                AppListFilter.PROTECTED -> app.isEssentialProtected || app.isHomeLauncher
            }
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
                text = "Application Selection",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onReloadApps) {
                Text(if (isLoading) "Loading..." else "Refresh")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Mark apps as Blocked (triggers overlay) or Study Allowlist (openable from overlay). Phone Dialer, Settings, and Launcher remain permanently protected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search installed apps or package") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AppListFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                val currentRule = rulesMap[app.packageName]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        if (app.isEssentialProtected || app.isHomeLauncher) {
                            Text(
                                text = if (app.isHomeLauncher) {
                                    "Protected Home Launcher (Never Blocked)"
                                } else {
                                    "Protected System / Dialer / Safety App (Never Blocked)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = currentRule == null,
                                    onClick = { onSetRule(app.packageName, app.label, null) },
                                    label = { Text("Neutral") }
                                )
                                FilterChip(
                                    selected = currentRule == AppRuleType.BLOCKED.name,
                                    onClick = {
                                        onSetRule(app.packageName, app.label, AppRuleType.BLOCKED)
                                    },
                                    label = { Text("Block") }
                                )
                                FilterChip(
                                    selected = currentRule == AppRuleType.ALLOWED_STUDY.name,
                                    onClick = {
                                        onSetRule(app.packageName, app.label, AppRuleType.ALLOWED_STUDY)
                                    },
                                    label = { Text("Study Allow") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
