package com.arrestme.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arrestme.benchmark.ui.BenchmarkViewModel
import com.arrestme.benchmark.ui.account.AccountScreen
import com.arrestme.benchmark.ui.apps.AppSelectionScreen
import com.arrestme.benchmark.ui.benchmark.BenchmarkScreen
import com.arrestme.benchmark.ui.diagnostics.DiagnosticsScreen
import com.arrestme.benchmark.ui.home.HomeScreen
import com.arrestme.benchmark.ui.navigation.BenchmarkRoute
import com.arrestme.benchmark.ui.permissions.PermissionsScreen
import com.arrestme.benchmark.ui.session.ActiveSessionScreen
import com.arrestme.benchmark.ui.theme.ArrestMeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArrestMeTheme {
                ArrestMeBenchmarkApp()
            }
        }
    }
}

@Composable
private fun ArrestMeBenchmarkApp(vm: BenchmarkViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val activeSession by vm.activeSession.collectAsStateWithLifecycle()
    val remainingMs by vm.remainingMs.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()
    val isLoadingApps by vm.isLoadingApps.collectAsStateWithLifecycle()
    val appRules by vm.appRules.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val transitions by vm.transitions.collectAsStateWithLifecycle()
    val diagnosticEvents by vm.diagnosticEvents.collectAsStateWithLifecycle()
    val backendConfig by vm.backendConfig.collectAsStateWithLifecycle()
    val backendAuthState by vm.backendAuthState.collectAsStateWithLifecycle()
    val healthCheckStatus by vm.healthCheckStatus.collectAsStateWithLifecycle()
    val isTestingConnection by vm.isTestingConnection.collectAsStateWithLifecycle()
    val isAuthBusy by vm.isAuthBusy.collectAsStateWithLifecycle()
    val isSyncingAccount by vm.isSyncingAccount.collectAsStateWithLifecycle()
    val authErrorMessage by vm.authErrorMessage.collectAsStateWithLifecycle()
    val statusBanner by vm.statusBanner.collectAsStateWithLifecycle()

    LaunchedEffect(statusBanner) {
        val msg = statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.dismissBanner()
    }

    fun navigateTo(target: BenchmarkRoute) {
        navController.navigate(target.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: BenchmarkRoute.HOME.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                BenchmarkRoute.entries.forEach { dest ->
                    val icon = when (dest) {
                        BenchmarkRoute.HOME -> Icons.Default.Home
                        BenchmarkRoute.PERMISSIONS -> Icons.Default.CheckCircle
                        BenchmarkRoute.APPS -> Icons.Default.List
                        BenchmarkRoute.SESSION -> Icons.Default.Lock
                        BenchmarkRoute.BENCHMARK -> Icons.Default.Info
                        BenchmarkRoute.DIAGNOSTICS -> Icons.Default.Build
                        BenchmarkRoute.ACCOUNT -> Icons.Default.AccountCircle
                    }
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { navigateTo(dest) },
                        icon = { Icon(icon, contentDescription = dest.label) },
                        label = { Text(dest.label, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = BenchmarkRoute.HOME.route
            ) {
                composable(BenchmarkRoute.HOME.route) {
                    HomeScreen(
                        permissions = permissions,
                        activeSession = activeSession,
                        remainingMs = remainingMs,
                        appRules = appRules,
                        sessions = sessions,
                        backendConfig = backendConfig,
                        backendAuthState = backendAuthState,
                        onNavigate = ::navigateTo
                    )
                }
                composable(BenchmarkRoute.PERMISSIONS.route) {
                    PermissionsScreen(
                        status = permissions,
                        permissionChecker = vm.permissionChecker,
                        onRefresh = vm::refreshPermissions
                    )
                }
                composable(BenchmarkRoute.APPS.route) {
                    AppSelectionScreen(
                        installedApps = installedApps,
                        isLoading = isLoadingApps,
                        appRules = appRules,
                        onSetRule = vm::updateAppRule,
                        onReloadApps = vm::loadInstalledApps
                    )
                }
                composable(BenchmarkRoute.SESSION.route) {
                    ActiveSessionScreen(
                        activeSession = activeSession,
                        remainingMs = remainingMs,
                        permissions = permissions,
                        appRules = appRules,
                        onStartSession = vm::startArrestSession,
                        onPauseSession = vm::pauseSession,
                        onResumeSession = vm::resumeSession,
                        onEmergencyStop = vm::emergencyStopSession,
                        onResetIdle = vm::resetFinishedSessionToIdle,
                        onNavigate = ::navigateTo
                    )
                }
                composable(BenchmarkRoute.BENCHMARK.route) {
                    BenchmarkScreen(
                        sessions = sessions,
                        onReconcileUsageStats = vm::reconcileUsageStatsForAllRecent,
                        onSaveJsonToUri = vm::writeJsonToUri,
                        onShareJson = vm::shareBenchmarkJson
                    )
                }
                composable(BenchmarkRoute.DIAGNOSTICS.route) {
                    DiagnosticsScreen(
                        events = diagnosticEvents,
                        transitions = transitions,
                        onClearLogs = vm::clearDiagnosticLogs
                    )
                }
                composable(BenchmarkRoute.ACCOUNT.route) {
                    AccountScreen(
                        config = backendConfig,
                        authState = backendAuthState,
                        healthCheck = healthCheckStatus,
                        isTestingConnection = isTestingConnection,
                        isAuthBusy = isAuthBusy,
                        isSyncingAccount = isSyncingAccount,
                        authErrorMessage = authErrorMessage,
                        onDismissAuthError = vm::dismissAuthError,
                        onLogin = vm::login,
                        onRegister = vm::register,
                        onLogout = vm::logout,
                        onSyncAccount = vm::syncAccountAndDevice,
                        onUpdateDisplayName = vm::updateDisplayName,
                        onSetMode = vm::setBackendMode,
                        onSaveCustomUrl = vm::saveCustomBackendUrl,
                        onTestConnection = vm::testBackendConnection
                    )
                }
            }
        }
    }
}
