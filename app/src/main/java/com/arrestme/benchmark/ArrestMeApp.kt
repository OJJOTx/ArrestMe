package com.arrestme.benchmark

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.arrestme.benchmark.core.accessibility.ArrestAccessibilityService
import com.arrestme.benchmark.core.analytics.UsageMonitor
import com.arrestme.benchmark.core.backend.AndroidDeviceMetadataProvider
import com.arrestme.benchmark.core.backend.AndroidKeystoreSecretCipher
import com.arrestme.benchmark.core.backend.BackendApiClient
import com.arrestme.benchmark.core.backend.BackendAuthRepository
import com.arrestme.benchmark.core.backend.BackendAuthState
import com.arrestme.benchmark.core.backend.BackendConfigRepository
import com.arrestme.benchmark.core.backend.BackendPreferencesDataSource
import com.arrestme.benchmark.core.backend.backendDataStore
import com.arrestme.benchmark.core.blocking.BlockingEngine
import com.arrestme.benchmark.core.blocking.BlockingRulesEvaluator
import com.arrestme.benchmark.core.blocking.SystemSafetyAllowlist
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.overlay.OverlayManager
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.persistence.PersistenceManager
import com.arrestme.benchmark.core.session.SessionController
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.data.local.ArrestDatabase
import com.arrestme.benchmark.data.local.SessionSnapshotStore
import com.arrestme.benchmark.data.remote.AndroidNetworkConnectivityChecker
import com.arrestme.benchmark.data.remote.ArrestApiClientFactory
import com.arrestme.benchmark.data.remote.NetworkConnectivityChecker
import com.arrestme.benchmark.data.repository.ArrestRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArrestMeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: ArrestDatabase
        private set

    lateinit var repository: ArrestRepository
        private set

    lateinit var permissionChecker: PermissionChecker
        private set

    lateinit var antiCheatingMonitor: AntiCheatingMonitor
        private set

    lateinit var safetyAllowlist: SystemSafetyAllowlist
        private set

    lateinit var rulesEvaluator: BlockingRulesEvaluator
        private set

    lateinit var usageMonitor: UsageMonitor
        private set

    lateinit var sessionController: SessionController
        private set

    lateinit var overlayManager: OverlayManager
        private set

    lateinit var blockingEngine: BlockingEngine
        private set

    lateinit var persistenceManager: PersistenceManager
        private set

    lateinit var connectivityChecker: NetworkConnectivityChecker
        private set

    lateinit var backendPreferencesDataSource: BackendPreferencesDataSource
        private set

    lateinit var apiClientFactory: ArrestApiClientFactory
        private set

    lateinit var backendConfigRepository: BackendConfigRepository
        private set

    lateinit var backendAuthRepository: BackendAuthRepository
        private set

    lateinit var backendApiClient: BackendApiClient
        private set

    override fun onCreate() {
        super.onCreate()

        database = ArrestDatabase.getInstance(this)
        val snapshotStore = SessionSnapshotStore(this)
        repository = ArrestRepository(this, database, snapshotStore)

        connectivityChecker = AndroidNetworkConnectivityChecker(this)
        backendPreferencesDataSource = BackendPreferencesDataSource(
            dataStore = backendDataStore,
            secretCipher = AndroidKeystoreSecretCipher()
        )
        apiClientFactory = ArrestApiClientFactory(
            preferencesDataSource = backendPreferencesDataSource,
            connectivityChecker = connectivityChecker
        )
        backendConfigRepository = BackendConfigRepository(
            preferencesDataSource = backendPreferencesDataSource,
            buildConfigUrl = BuildConfig.API_BASE_URL
        )
        backendAuthRepository = BackendAuthRepository(
            preferencesDataSource = backendPreferencesDataSource,
            configRepository = backendConfigRepository,
            scope = appScope,
            apiClientFactory = apiClientFactory,
            deviceMetadataProvider = AndroidDeviceMetadataProvider()
        )
        backendApiClient = BackendApiClient(
            apiClientFactory = apiClientFactory,
            connectivityChecker = connectivityChecker
        )

        permissionChecker = PermissionChecker(
            context = this,
            accessibilityClassName = ArrestAccessibilityService::class.java.name,
            isAccessibilityConnectedProvider = { ArrestAccessibilityService.isConnected.value }
        )

        antiCheatingMonitor = AntiCheatingMonitor(repository, appScope)
        safetyAllowlist = SystemSafetyAllowlist(this)
        rulesEvaluator = BlockingRulesEvaluator(this, safetyAllowlist)

        usageMonitor = UsageMonitor(
            context = this,
            repository = repository,
            permissionChecker = permissionChecker,
            rulesEvaluator = rulesEvaluator,
            antiCheatingMonitor = antiCheatingMonitor,
            scope = appScope
        )

        sessionController = SessionController(
            context = this,
            repository = repository,
            permissionChecker = permissionChecker,
            usageMonitor = usageMonitor,
            antiCheatingMonitor = antiCheatingMonitor,
            scope = appScope
        )

        overlayManager = OverlayManager(
            context = this,
            permissionChecker = permissionChecker,
            sessionController = sessionController,
            antiCheatingMonitor = antiCheatingMonitor,
            scope = appScope
        )

        blockingEngine = BlockingEngine(
            context = this,
            repository = repository,
            sessionController = sessionController,
            rulesEvaluator = rulesEvaluator,
            overlayManager = overlayManager,
            usageMonitor = usageMonitor,
            permissionChecker = permissionChecker,
            antiCheatingMonitor = antiCheatingMonitor,
            scope = appScope
        )

        persistenceManager = PersistenceManager(
            context = this,
            repository = repository,
            sessionController = sessionController,
            permissionChecker = permissionChecker,
            usageMonitor = usageMonitor,
            antiCheatingMonitor = antiCheatingMonitor
        )

        appScope.launch {
            persistenceManager.inspectProcessStartupAndRecover()
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    appScope.launch {
                        sessionController.checkAndCompleteIfExpired()
                        val snap = sessionController.activeSession.value
                        if (snap?.state == SessionState.INTERRUPTED &&
                            permissionChecker.inspectAll().isCoreBlockingReady
                        ) {
                            sessionController.resumeSession(
                                "User reopened app with blocking permissions restored"
                            )
                        }
                    }

                    // Non-blocking background cloud profile/device sync when authenticated & online.
                    // Strictly isolated from SessionController so offline/cloud errors never affect blocking.
                    appScope.launch(Dispatchers.IO) {
                        runCatching {
                            if (backendAuthRepository.authState.value is BackendAuthState.Authenticated &&
                                connectivityChecker.isOnline()
                            ) {
                                backendAuthRepository.syncAccountAndDevice()
                            }
                        }
                    }
                }
            }
        )
    }
}
