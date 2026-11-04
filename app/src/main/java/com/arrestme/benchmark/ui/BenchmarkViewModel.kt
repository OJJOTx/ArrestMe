package com.arrestme.benchmark.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arrestme.benchmark.ArrestMeApp
import com.arrestme.benchmark.core.accessibility.ArrestAccessibilityService
import com.arrestme.benchmark.core.blocking.InstalledAppInfo
import com.arrestme.benchmark.core.permissions.PermissionStatusSnapshot
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.SessionConfig
import com.arrestme.benchmark.core.session.StartSessionResult
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.data.local.DiagnosticEventEntity
import com.arrestme.benchmark.data.local.SessionEntity
import com.arrestme.benchmark.data.local.SessionTransitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ArrestMeApp = application as ArrestMeApp

    val activeSession: StateFlow<ActiveSessionSnapshot?> = app.sessionController.activeSession
    val remainingMs: StateFlow<Long> = app.sessionController.remainingMs

    private val _permissions = MutableStateFlow(app.permissionChecker.inspectAll())
    val permissions: StateFlow<PermissionStatusSnapshot> = _permissions.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val appRules: StateFlow<List<AppRuleEntity>> = app.repository.observeAppRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val sessions: StateFlow<List<SessionEntity>> = app.repository.observeAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val transitions: StateFlow<List<SessionTransitionEntity>> = app.repository.observeRecentTransitions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val diagnosticEvents: StateFlow<List<DiagnosticEventEntity>> = app.repository.observeDiagnosticEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        refreshPermissions()
        loadInstalledApps()

        viewModelScope.launch {
            ArrestAccessibilityService.isConnected.collect {
                _permissions.value = app.permissionChecker.inspectAll()
            }
        }
    }

    fun refreshPermissions() {
        _permissions.value = app.permissionChecker.inspectAll()
        viewModelScope.launch {
            app.sessionController.checkAndCompleteIfExpired()
        }
    }

    fun loadInstalledApps() {
        if (_isLoadingApps.value) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            val list = withContext(Dispatchers.IO) {
                app.rulesEvaluator.queryInstalledLaunchableApps()
            }
            _installedApps.value = list
            _isLoadingApps.value = false
        }
    }

    fun updateAppRule(packageName: String, appLabel: String, ruleType: AppRuleType?) {
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.setAppRule(packageName, appLabel, ruleType)
        }
    }

    fun startArrestSession(config: SessionConfig) {
        viewModelScope.launch {
            refreshPermissions()
            when (val res = app.sessionController.startSession(config)) {
                is StartSessionResult.Started -> {
                    _statusBanner.value = "Session ${res.snapshot.sessionId} started (${config.durationMinutes} min)."
                }
                is StartSessionResult.DuplicatePrevented -> {
                    _statusBanner.value = "Duplicate session prevented: ${res.existingSessionId} is already ${res.state}."
                }
                is StartSessionResult.Error -> {
                    _statusBanner.value = "Error: ${res.message}"
                }
            }
        }
    }

    fun pauseSession() {
        viewModelScope.launch {
            app.sessionController.pauseSession("Paused from Active Session screen")
        }
    }

    fun resumeSession() {
        viewModelScope.launch {
            refreshPermissions()
            app.sessionController.resumeSession("Resumed from Active Session screen")
        }
    }

    fun emergencyStopSession() {
        viewModelScope.launch {
            app.sessionController.emergencyStop("Explicit user emergency exit from Active Session screen")
            _statusBanner.value = "Emergency Exit executed. Session stopped."
        }
    }

    fun resetFinishedSessionToIdle() {
        viewModelScope.launch {
            app.sessionController.resetToIdle()
        }
    }

    fun reconcileUsageStatsForAllRecent() {
        viewModelScope.launch {
            val recent = sessions.value.take(5)
            for (s in recent) {
                app.usageMonitor.reconcileSessionWithUsageStats(s.sessionId)
            }
            _statusBanner.value = "UsageStats reconciliation completed for ${recent.size} session(s)."
        }
    }

    fun clearDiagnosticLogs() {
        viewModelScope.launch {
            app.repository.clearDiagnosticEvents()
            _statusBanner.value = "Diagnostic event history cleared."
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun writeJsonToUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val permsMap = app.permissionChecker.inspectAll().toSummaryMap()
                val json = app.repository.buildBenchmarkReportJson(permsMap)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                _statusBanner.value = "Benchmark JSON saved successfully."
            }.onFailure { e ->
                _statusBanner.value = "JSON save failed: ${e.message}"
            }
        }
    }

    fun shareBenchmarkJson(onShareIntentReady: (Intent) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val permsMap = app.permissionChecker.inspectAll().toSummaryMap()
                val json = app.repository.buildBenchmarkReportJson(permsMap)
                val exportFile = app.repository.writeBenchmarkExportFile(json)
                val uri = FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    exportFile
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Arrest Me V0.1 Benchmark Report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onShareIntentReady(Intent.createChooser(sendIntent, "Export Benchmark JSON"))
            }.onFailure { e ->
                _statusBanner.value = "Export failed: ${e.message}"
            }
        }
    }

    val permissionChecker get() = app.permissionChecker
}
