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
import androidx.compose.material.icons.filled.Person
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arrestme.benchmark.core.backend.BackendAuthState
import com.arrestme.benchmark.ui.BenchmarkViewModel
import com.arrestme.benchmark.ui.account.AccountScreen
import com.arrestme.benchmark.ui.apps.AppSelectionScreen
import com.arrestme.benchmark.ui.benchmark.BenchmarkScreen
import com.arrestme.benchmark.ui.chat.ActiveArrestCommunicationPanel
import com.arrestme.benchmark.ui.chat.ConversationListScreen
import com.arrestme.benchmark.ui.chat.PartnerChatScreen
import com.arrestme.benchmark.ui.diagnostics.DiagnosticsScreen
import com.arrestme.benchmark.ui.home.HomeScreen
import com.arrestme.benchmark.ui.navigation.BenchmarkRoute
import com.arrestme.benchmark.ui.navigation.PartnerSubRoutes
import com.arrestme.benchmark.ui.partners.ArrestProposalBuilderScreen
import com.arrestme.benchmark.ui.partners.LiveSessionDashboardScreen
import com.arrestme.benchmark.ui.partners.NegotiationDetailsScreen
import com.arrestme.benchmark.ui.partners.PartnerApprovalSettingsScreen
import com.arrestme.benchmark.ui.partners.PartnerDetailsScreen
import com.arrestme.benchmark.ui.partners.PartnerRequestsScreen
import com.arrestme.benchmark.ui.partners.PartnersScreen
import com.arrestme.benchmark.ui.partners.ScheduledArrestsScreen
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

    // V0.4 Partner System state
    val partners by vm.partners.collectAsStateWithLifecycle()
    val incomingRequests by vm.incomingPartnerRequests.collectAsStateWithLifecycle()
    val outgoingRequests by vm.outgoingPartnerRequests.collectAsStateWithLifecycle()
    val pendingCounts by vm.pendingCounts.collectAsStateWithLifecycle()
    val myInviteCode by vm.myInviteCode.collectAsStateWithLifecycle()
    val discoveryResult by vm.discoveryResult.collectAsStateWithLifecycle()
    val selectedPartnerProfile by vm.selectedPartnerProfile.collectAsStateWithLifecycle()
    val proposals by vm.proposals.collectAsStateWithLifecycle()
    val selectedProposal by vm.selectedProposal.collectAsStateWithLifecycle()
    val scheduledArrests by vm.scheduledArrests.collectAsStateWithLifecycle()
    val isPartnerLoading by vm.isPartnerSystemLoading.collectAsStateWithLifecycle()
    val partnerErrorMessage by vm.partnerErrorMessage.collectAsStateWithLifecycle()

    // V0.5 Live Dashboard & Coordinator state
    val liveDashboardItems by vm.liveDashboardItems.collectAsStateWithLifecycle()
    val isSyncingCommands by vm.isSyncingCommands.collectAsStateWithLifecycle()
    val coordinatorBanner by vm.coordinatorBanner.collectAsStateWithLifecycle()

    // V0.6 Full Chat & Structured Requests state
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeConversation by vm.activeConversation.collectAsStateWithLifecycle()
    val chatMessages by vm.chatMessages.collectAsStateWithLifecycle()
    val activeTemporaryGrants by vm.activeTemporaryGrants.collectAsStateWithLifecycle()
    val isChatLoading by vm.isChatLoading.collectAsStateWithLifecycle()
    val chatErrorMessage by vm.chatErrorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(coordinatorBanner) {
        val msg = coordinatorBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.dismissCoordinatorBanner()
    }

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
                        BenchmarkRoute.PARTNERS -> Icons.Default.Person
                        BenchmarkRoute.BENCHMARK -> Icons.Default.Info
                        BenchmarkRoute.DIAGNOSTICS -> Icons.Default.Build
                        BenchmarkRoute.ACCOUNT -> Icons.Default.AccountCircle
                    }
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { navigateTo(dest) },
                        icon = { Icon(icon, contentDescription = dest.label) },
                        label = { Text(dest.label, fontSize = 9.sp, maxLines = 1) }
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
                        onNavigate = ::navigateTo,
                        onOpenPartnerChat = {
                            navController.navigate(PartnerSubRoutes.CONVERSATIONS)
                        },
                        onRequestEarlyRelease = { plea ->
                            val sId = activeSession?.sessionId
                            val conv = conversations.firstOrNull()
                            if (conv != null) {
                                vm.sendStructuredRequest(conv.id, "REQUEST_EARLY_RELEASE", sId, null, null, plea)
                            }
                        },
                        onRequestTemporaryAccess = { pkg, mins, plea ->
                            val sId = activeSession?.sessionId
                            val conv = conversations.firstOrNull()
                            if (conv != null) {
                                vm.sendStructuredRequest(conv.id, "REQUEST_TEMPORARY_APP_ACCESS", sId, pkg, mins, plea)
                            }
                        },
                        onRevokeGrant = { grantId ->
                            val sId = activeSession?.sessionId ?: ""
                            vm.revokeTemporaryGrant(sId, grantId)
                        },
                        activeGrants = activeTemporaryGrants
                    )
                }
                composable(BenchmarkRoute.PARTNERS.route) {
                    PartnersScreen(
                        authState = backendAuthState,
                        partners = partners,
                        pendingCounts = pendingCounts,
                        myInviteCode = myInviteCode,
                        discoveryResult = discoveryResult,
                        proposals = proposals,
                        scheduledArrests = scheduledArrests,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onRefresh = vm::refreshPartnerSystem,
                        onDismissError = vm::dismissPartnerError,
                        onRegenerateInviteCode = vm::regenerateInviteCode,
                        onDiscoverPartner = vm::discoverPartner,
                        onClearDiscovery = vm::clearDiscoveryResult,
                        onSendPartnerRequest = vm::sendPartnerRequest,
                        onOpenPartnerRequests = {
                            navController.navigate(PartnerSubRoutes.PARTNER_REQUESTS)
                        },
                        onOpenPartnerDetails = { partnershipId ->
                            navController.navigate(PartnerSubRoutes.partnerDetails(partnershipId))
                        },
                        onOpenNegotiationDetails = { proposalId ->
                            navController.navigate(PartnerSubRoutes.negotiationDetails(proposalId))
                        },
                        onOpenScheduledArrests = {
                            navController.navigate(PartnerSubRoutes.SCHEDULED_ARRESTS)
                        },
                        onOpenLiveDashboard = {
                            navController.navigate(PartnerSubRoutes.LIVE_DASHBOARD)
                        },
                        onOpenConversations = {
                            navController.navigate(PartnerSubRoutes.CONVERSATIONS)
                        },
                        onOpenChatForPartnership = { partnershipId ->
                            vm.openConversationForPartnership(partnershipId) { convId ->
                                navController.navigate(PartnerSubRoutes.partnerChat(convId))
                            }
                        },
                        onOpenAccountSettings = {
                            navigateTo(BenchmarkRoute.ACCOUNT)
                        }
                    )
                }
                composable(PartnerSubRoutes.PARTNER_REQUESTS) {
                    PartnerRequestsScreen(
                        incomingRequests = incomingRequests,
                        outgoingRequests = outgoingRequests,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onAcceptRequest = vm::acceptPartnerRequest,
                        onRejectRequest = vm::rejectPartnerRequest,
                        onCancelOutgoingRequest = { id -> vm.removeOrCancelPartnership(id) },
                        onBlockUser = { id -> vm.blockPartner(id) },
                        onRefresh = vm::refreshPartnerSystem,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = PartnerSubRoutes.PARTNER_DETAILS,
                    arguments = listOf(navArgument("partnershipId") { type = NavType.StringType })
                ) { entry ->
                    val partnershipId = entry.arguments?.getString("partnershipId").orEmpty()
                    PartnerDetailsScreen(
                        partnershipId = partnershipId,
                        profile = selectedPartnerProfile,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onLoadProfile = vm::loadPartnerProfile,
                        onOpenApprovalSettings = { id ->
                            navController.navigate(PartnerSubRoutes.partnerApprovalSettings(id))
                        },
                        onOpenCreateProposal = { id ->
                            navController.navigate(PartnerSubRoutes.proposalBuilder(id))
                        },
                        onOpenNegotiationDetails = { propId ->
                            navController.navigate(PartnerSubRoutes.negotiationDetails(propId))
                        },
                        onOpenChat = { id ->
                            vm.openConversationForPartnership(id) { convId ->
                                navController.navigate(PartnerSubRoutes.partnerChat(convId))
                            }
                        },
                        onRevokePermission = vm::revokePermission,
                        onRemovePartner = { id ->
                            vm.removeOrCancelPartnership(id) {
                                navController.popBackStack()
                            }
                        },
                        onBlockPartner = { id ->
                            vm.blockPartner(id) {
                                navController.popBackStack()
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = PartnerSubRoutes.PARTNER_APPROVAL_SETTINGS,
                    arguments = listOf(navArgument("partnershipId") { type = NavType.StringType })
                ) { entry ->
                    val partnershipId = entry.arguments?.getString("partnershipId").orEmpty()
                    PartnerApprovalSettingsScreen(
                        partnershipId = partnershipId,
                        profile = selectedPartnerProfile,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onLoadProfile = vm::loadPartnerProfile,
                        onUpdateApprovalPolicy = vm::updateApprovalPolicy,
                        onGrantStandingPermission = vm::grantStandingPermission,
                        onRevokePermission = vm::revokePermission,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = PartnerSubRoutes.PROPOSAL_BUILDER,
                    arguments = listOf(navArgument("partnershipId") { type = NavType.StringType })
                ) { entry ->
                    val partnershipId = entry.arguments?.getString("partnershipId").orEmpty()
                    ArrestProposalBuilderScreen(
                        partnershipId = partnershipId,
                        profile = selectedPartnerProfile,
                        localAppRules = appRules,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onLoadProfile = vm::loadPartnerProfile,
                        onSubmitProposal = vm::submitProposal,
                        onProposalCreated = { proposalId ->
                            navController.navigate(PartnerSubRoutes.negotiationDetails(proposalId)) {
                                popUpTo(PartnerSubRoutes.PROPOSAL_BUILDER) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = PartnerSubRoutes.NEGOTIATION_DETAILS,
                    arguments = listOf(navArgument("proposalId") { type = NavType.StringType })
                ) { entry ->
                    val proposalId = entry.arguments?.getString("proposalId").orEmpty()
                    val currentUserId = (backendAuthState as? BackendAuthState.Authenticated)?.credentials?.userId
                    NegotiationDetailsScreen(
                        proposalId = proposalId,
                        currentUserId = currentUserId,
                        proposal = selectedProposal,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onLoadProposal = vm::loadProposalDetails,
                        onAcceptProposal = vm::acceptProposal,
                        onRejectProposal = vm::rejectProposal,
                        onCancelProposal = vm::cancelProposal,
                        onCounterofferProposal = { id, req -> vm.counterofferProposal(id, req) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(PartnerSubRoutes.SCHEDULED_ARRESTS) {
                    ScheduledArrestsScreen(
                        schedules = scheduledArrests,
                        isLoading = isPartnerLoading,
                        errorMessage = partnerErrorMessage,
                        onCancelSchedule = vm::cancelScheduledArrest,
                        onRefresh = vm::refreshPartnerSystem,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(PartnerSubRoutes.LIVE_DASHBOARD) {
                    val currentUserId = (backendAuthState as? BackendAuthState.Authenticated)?.credentials?.userId
                    LiveSessionDashboardScreen(
                        currentUserId = currentUserId,
                        liveItems = liveDashboardItems,
                        localActiveSession = activeSession,
                        localRemainingMs = remainingMs,
                        isSyncing = isSyncingCommands,
                        onRefresh = vm::syncCommandsAndSchedules,
                        onRequestRemoteEnd = { id -> vm.requestRemoteEndSession(id) },
                        onRequestRemoteSync = { id -> vm.requestRemoteSyncSession(id) },
                        onUploadAppCatalog = vm::uploadSelectableAppCatalog,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(PartnerSubRoutes.CONVERSATIONS) {
                    ConversationListScreen(
                        conversations = conversations,
                        isLoading = isChatLoading,
                        errorMessage = chatErrorMessage,
                        onRefresh = vm::refreshConversations,
                        onConversationClick = { convId ->
                            vm.selectConversation(convId)
                            navController.navigate(PartnerSubRoutes.partnerChat(convId))
                        },
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = PartnerSubRoutes.PARTNER_CHAT,
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                ) { entry ->
                    val convId = entry.arguments?.getString("conversationId").orEmpty()
                    val currentUserId = (backendAuthState as? BackendAuthState.Authenticated)?.credentials?.userId
                    LaunchedEffect(convId) {
                        vm.selectConversation(convId)
                    }
                    PartnerChatScreen(
                        conversation = activeConversation,
                        messages = chatMessages,
                        currentUserId = currentUserId,
                        isLoading = isChatLoading,
                        errorMessage = chatErrorMessage,
                        activeSessionId = activeSession?.sessionId,
                        onRefresh = { vm.refreshChatMessages(convId) },
                        onSendMessage = { text -> vm.sendChatMessage(convId, text) },
                        onSendStructuredRequest = { type, sessId, pkg, dur, msg ->
                            vm.sendStructuredRequest(convId, type, sessId, pkg, dur, msg)
                        },
                        onRespondToRequest = { reqId, decision, reason ->
                            vm.respondToStructuredRequest(reqId, decision, reason)
                        },
                        onCancelRequest = vm::cancelStructuredRequest,
                        onMarkRead = { msgId -> vm.markConversationRead(convId, msgId) },
                        onBack = { navController.popBackStack() }
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
