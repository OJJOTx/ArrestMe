package com.arrestme.benchmark.core.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import com.arrestme.benchmark.MainActivity
import com.arrestme.benchmark.core.accessibility.ArrestAccessibilityService
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
import com.arrestme.benchmark.core.session.BlockingRuleMode
import com.arrestme.benchmark.core.session.SessionController
import com.arrestme.benchmark.core.session.SessionLifecycleObserver
import com.arrestme.benchmark.core.session.SessionState
import com.arrestme.benchmark.data.local.EventSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class OverlayRequest(
    val session: ActiveSessionSnapshot,
    val blockedPackageName: String,
    val blockedAppLabel: String,
    val blockReason: String,
    val allowedStudyApps: List<Pair<String, String>>,
    val detectionElapsedNanos: Long
)

class OverlayManager(
    context: Context,
    private val permissionChecker: PermissionChecker,
    private val sessionController: SessionController,
    private val antiCheatingMonitor: AntiCheatingMonitor,
    private val scope: CoroutineScope
) : SessionLifecycleObserver {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    var onLaunchingPermittedApp: ((fromBlockedPkg: String?, targetPermittedPkg: String) -> Unit)? = null

    private val overlayWindowContext: Context by lazy {
        createOverlayWindowContext(appContext)
    }

    private val windowManager: WindowManager by lazy {
        overlayWindowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val keyguardManager: KeyguardManager? by lazy {
        appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    private var rootOverlayView: OverlayRootContainer? = null
    private var attachedWindowManager: WindowManager? = null
    private var attachedLayoutParams: WindowManager.LayoutParams? = null
    private var attachedCoverStatusBar: Boolean = false
    private var attachedUsingAccessibilityLayer: Boolean = false
    private var receiverRegistered: Boolean = false

    private var currentlyBlockedPackage: String? = null
    private var activeSessionSnapshot: ActiveSessionSnapshot? = null
    private var emergencyArmed = false

    private var tvBadge: TextView? = null
    private var tvTitle: TextView? = null
    private var tvTimer: TextView? = null
    private var tvGoal: TextView? = null
    private var tvSessionMeta: TextView? = null
    private var tvBlockReason: TextView? = null
    private var tvAllowedTitle: TextView? = null
    private var allowedAppsContainer: LinearLayout? = null
    private var btnHome: Button? = null
    private var btnOpenArrestMe: Button? = null
    private var btnEmergency: Button? = null
    private var tvEmergencyDisabledNote: TextView? = null

    private val _isOverlayShowing = MutableStateFlow(false)
    val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

    private val keyguardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.post {
                syncKeyguardVisibility()
            }
        }
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val view = rootOverlayView ?: return

            if (!permissionChecker.hasOverlayPermission() && !attachedUsingAccessibilityLayer) {
                removeOverlayInternal("Overlay permission revoked while overlay active")
                scope.launch {
                    sessionController.markInterrupted("SYSTEM_ALERT_WINDOW permission revoked")
                }
                return
            }

            val snapshot = sessionController.activeSession.value ?: activeSessionSnapshot
            if (snapshot == null || snapshot.state != SessionState.ACTIVE) {
                removeOverlayInternal("Session no longer ACTIVE (${snapshot?.state})")
                return
            }

            syncKeyguardVisibility()
            if (view.visibility == View.VISIBLE && attachedCoverStatusBar) {
                applyImmersiveSystemUi(view)
            }

            val remaining = snapshot.computeRemainingMs()
            tvTimer?.text = formatDuration(remaining)

            if (remaining <= 0L) {
                removeOverlayInternal("Session timer reached 00:00")
                scope.launch {
                    sessionController.checkAndCompleteIfExpired()
                }
                return
            }

            mainHandler.postDelayed(this, 500L)
        }
    }

    init {
        sessionController.registerObserver(this)
    }

    override fun onSessionUpdated(snapshot: ActiveSessionSnapshot?) {
        activeSessionSnapshot = snapshot
        if (snapshot == null || snapshot.state != SessionState.ACTIVE) {
            removeOverlay("Session transitioned to ${snapshot?.state ?: SessionState.IDLE}")
        } else {
            mainHandler.post {
                tvGoal?.text = "Goal: ${snapshot.goal}"
                tvTimer?.text = formatDuration(snapshot.computeRemainingMs())
            }
        }
    }

    fun isShowingForPackage(packageName: String): Boolean {
        return _isOverlayShowing.value && currentlyBlockedPackage == packageName
    }

    fun showOrUpdateOverlay(
        request: OverlayRequest,
        onResult: (succeeded: Boolean, latencyMs: Double?, errorReason: String?) -> Unit
    ) {
        mainHandler.post {
            val a11yService = ArrestAccessibilityService.instance
            val canUseA11yOverlay = request.session.overlayStatusBar && a11yService != null

            if (!permissionChecker.hasOverlayPermission() && !canUseA11yOverlay) {
                onResult(false, null, "SYSTEM_ALERT_WINDOW permission is missing")
                return@post
            }

            if (request.session.state != SessionState.ACTIVE || request.session.hasExpired()) {
                onResult(false, null, "Session is not ACTIVE or already expired")
                return@post
            }

            activeSessionSnapshot = request.session
            emergencyArmed = false
            val coverStatusBar = request.session.overlayStatusBar

            val existingView = rootOverlayView
            val shouldUpgradeToA11y = coverStatusBar && !attachedUsingAccessibilityLayer && a11yService != null
            if (existingView != null &&
                existingView.isAttachedToWindow &&
                attachedCoverStatusBar == coverStatusBar &&
                !shouldUpgradeToA11y
            ) {
                currentlyBlockedPackage = request.blockedPackageName
                bindOverlayData(request)
                syncKeyguardVisibility()
                if (existingView.visibility == View.VISIBLE) {
                    existingView.requestFocus()
                    if (coverStatusBar) {
                        applyImmersiveSystemUi(existingView)
                        a11yService?.dismissNotificationShade()
                    }
                }
                val latencyMs = (SystemClock.elapsedRealtimeNanos() - request.detectionElapsedNanos) / 1_000_000.0
                onResult(true, latencyMs, null)
                return@post
            }

            // Ensure any detached or outdated view reference is cleaned first
            if (existingView != null) {
                val prevWm = attachedWindowManager ?: windowManager
                runCatching { prevWm.removeViewImmediate(existingView) }
                    .onFailure { runCatching { prevWm.removeView(existingView) } }
                clearViewRefs()
            }

            val newView = buildOverlayHierarchy(overlayWindowContext, coverStatusBar)
            rootOverlayView = newView
            currentlyBlockedPackage = request.blockedPackageName
            bindOverlayData(request)

            val reported = AtomicBoolean(false)
            try {
                var addedViaA11y = false
                var usedWm: WindowManager = windowManager
                var usedLp: WindowManager.LayoutParams? = null

                if (coverStatusBar && a11yService != null) {
                    runCatching {
                        val a11yWm = a11yService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        val a11yLp = createOverlayLayoutParams(
                            wm = a11yWm,
                            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            coverStatusBar = true
                        )
                        a11yWm.addView(newView, a11yLp)
                        usedWm = a11yWm
                        usedLp = a11yLp
                        addedViaA11y = true
                    }
                }

                if (!addedViaA11y) {
                    if (!permissionChecker.hasOverlayPermission()) {
                        throw IllegalStateException("SYSTEM_ALERT_WINDOW permission missing for fallback overlay")
                    }
                    val appOverlayLp = createOverlayLayoutParams(
                        wm = windowManager,
                        windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        coverStatusBar = coverStatusBar
                    )
                    windowManager.addView(newView, appOverlayLp)
                    usedWm = windowManager
                    usedLp = appOverlayLp
                }

                attachedWindowManager = usedWm
                attachedLayoutParams = usedLp
                attachedCoverStatusBar = coverStatusBar
                attachedUsingAccessibilityLayer = addedViaA11y
                _isOverlayShowing.value = true

                ensureKeyguardReceiverRegistered()
                syncKeyguardVisibility()

                if (coverStatusBar) {
                    applyImmersiveSystemUi(newView)
                    a11yService?.dismissNotificationShade()
                }
                newView.requestFocus()

                mainHandler.removeCallbacks(countdownRunnable)
                mainHandler.post(countdownRunnable)

                // Measure frame-commit latency via view.post with safe fallback
                newView.post {
                    if (coverStatusBar) {
                        applyImmersiveSystemUi(newView)
                    }
                    newView.requestFocus()
                    if (reported.compareAndSet(false, true)) {
                        val latencyMs =
                            (SystemClock.elapsedRealtimeNanos() - request.detectionElapsedNanos) / 1_000_000.0
                        onResult(true, latencyMs, null)
                    }
                }
                mainHandler.postDelayed({
                    if (reported.compareAndSet(false, true)) {
                        val latencyMs =
                            (SystemClock.elapsedRealtimeNanos() - request.detectionElapsedNanos) / 1_000_000.0
                        onResult(true, latencyMs, null)
                    }
                }, 120L)
            } catch (t: Throwable) {
                clearViewRefs()
                _isOverlayShowing.value = false
                if (reported.compareAndSet(false, true)) {
                    onResult(false, null, "${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    fun removeOverlay(reason: String) {
        mainHandler.post {
            removeOverlayInternal(reason)
        }
    }

    private fun removeOverlayInternal(reason: String) {
        mainHandler.removeCallbacks(countdownRunnable)
        unregisterKeyguardReceiverIfNeeded()
        val view = rootOverlayView ?: return
        val wm = attachedWindowManager ?: windowManager
        val prevPkg = currentlyBlockedPackage
        runCatching {
            wm.removeViewImmediate(view)
        }.onFailure {
            runCatching { wm.removeView(view) }
        }
        clearViewRefs()
        _isOverlayShowing.value = false

        antiCheatingMonitor.recordEvent(
            type = MonitorEventType.OVERLAY_REMOVED,
            packageName = prevPkg,
            severity = EventSeverity.INFO,
            details = "Blocking overlay removed ($reason)"
        )
    }

    private fun clearViewRefs() {
        rootOverlayView = null
        attachedWindowManager = null
        attachedLayoutParams = null
        attachedCoverStatusBar = false
        attachedUsingAccessibilityLayer = false
        currentlyBlockedPackage = null
        emergencyArmed = false
        tvBadge = null
        tvTitle = null
        tvTimer = null
        tvGoal = null
        tvSessionMeta = null
        tvBlockReason = null
        tvAllowedTitle = null
        allowedAppsContainer = null
        btnHome = null
        btnOpenArrestMe = null
        btnEmergency = null
        tvEmergencyDisabledNote = null
    }

    private fun bindOverlayData(request: OverlayRequest) {
        val isWhitelistMode = request.session.ruleMode == BlockingRuleMode.WHITELIST_ONLY

        tvBadge?.text = if (isWhitelistMode) {
            "ARREST ENGINE • WHITELIST MODE ACTIVE"
        } else {
            "ARREST ENGINE • BLOCKING ACTIVE"
        }
        tvTitle?.text = if (isWhitelistMode) {
            "Arrest Lock Active"
        } else {
            "Application Restricted"
        }

        tvTimer?.text = formatDuration(request.session.computeRemainingMs())
        tvGoal?.text = "Goal: ${request.session.goal}"

        val statusExtras = buildList {
            add(request.session.ruleMode.label)
            if (request.session.overlayStatusBar) add("Status Bar Hidden")
            if (request.session.disableEmergencyExit) add("No Emergency Exit")
        }.joinToString(" • ")

        tvSessionMeta?.text = "${request.session.sessionType.label} • ID: ${request.session.sessionId}\n$statusExtras"
        tvBlockReason?.text = request.blockReason

        // Emergency Exit visibility & state
        if (request.session.disableEmergencyExit) {
            btnEmergency?.visibility = View.GONE
            tvEmergencyDisabledNote?.visibility = View.VISIBLE
        } else {
            btnEmergency?.visibility = View.VISIBLE
            btnEmergency?.text = "Emergency Exit Session"
            btnEmergency?.background = roundedBg("#7F1D1D", strokeHex = "#EF4444")
            tvEmergencyDisabledNote?.visibility = View.GONE
        }

        // Home & Return to Arrest Me buttons are hidden in Whitelist Mode because Launcher & Arrest Me are covered
        btnHome?.visibility = if (isWhitelistMode) View.GONE else View.VISIBLE
        btnOpenArrestMe?.visibility = if (isWhitelistMode) View.GONE else View.VISIBLE

        tvAllowedTitle?.text = if (isWhitelistMode) {
            "Whitelisted Apps (Tap to Open)"
        } else {
            "Permitted Actions & Allowed Apps"
        }

        val container = allowedAppsContainer ?: return
        container.removeAllViews()

        if (isWhitelistMode) {
            if (request.allowedStudyApps.isEmpty()) {
                val emptyHint = TextView(container.context).apply {
                    text = "No apps whitelisted for this session. Arrest UI remains active until the timer finishes."
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(container.context, 4), 0, dp(container.context, 8))
                }
                container.addView(emptyHint)
            } else {
                request.allowedStudyApps.forEach { (pkg, label) ->
                    val appBtn = createActionButton(
                        context = container.context,
                        label = "Open $label",
                        bgHex = "#065F46"
                    ) {
                        launchPermittedPackage(pkg)
                    }
                    container.addView(appBtn, buttonLayoutParams(container.context))
                }
            }
        } else {
            if (AllowedCategory.CALLS in request.session.allowedCategories) {
                val dialerBtn = createActionButton(
                    context = container.context,
                    label = "Open Phone Dialer (Calls Allowed)",
                    bgHex = "#1E3A8A"
                ) {
                    launchDialer()
                }
                container.addView(dialerBtn, buttonLayoutParams(container.context))
            }

            if (AllowedCategory.STUDY_APPS in request.session.allowedCategories) {
                request.allowedStudyApps.forEach { (pkg, label) ->
                    val studyBtn = createActionButton(
                        context = container.context,
                        label = "Open Allowed App: $label",
                        bgHex = "#065F46"
                    ) {
                        launchPermittedPackage(pkg)
                    }
                    container.addView(studyBtn, buttonLayoutParams(container.context))
                }
            }
        }
    }

    private fun buildOverlayHierarchy(ctx: Context, coverStatusBar: Boolean): OverlayRootContainer {
        val root = OverlayRootContainer(ctx).apply {
            setBackgroundColor(
                Color.parseColor(if (coverStatusBar) "#FF0B1120" else "#F50B1120")
            )
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            onWindowFocusRestored = {
                if (attachedCoverStatusBar) {
                    applyImmersiveSystemUi(this)
                }
            }
        }

        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
        }

        val contentColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = dp(ctx, if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 48 else 24)
            val vPadTop = dp(ctx, if (coverStatusBar) 44 else 32)
            val vPadBottom = dp(ctx, 36)
            setPadding(hPad, vPadTop, hPad, vPadBottom)
        }
        root.onOrientationPaddingUpdate = { config ->
            val hPad = dp(ctx, if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 48 else 24)
            val vPadTop = dp(ctx, if (attachedCoverStatusBar) 36 else 28)
            val vPadBottom = dp(ctx, 32)
            contentColumn.setPadding(hPad, vPadTop, hPad, vPadBottom)
            syncFullScreenBoundsIfNeeded()
        }

        val badge = TextView(ctx).apply {
            text = "ARREST ENGINE • BLOCKING ACTIVE"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg("#450A0A", "#DC2626")
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
        }
        tvBadge = badge
        contentColumn.addView(
            badge,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 16) }
        )

        val title = TextView(ctx).apply {
            text = "Application Restricted"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        tvTitle = title
        contentColumn.addView(title)

        val timerView = TextView(ctx).apply {
            text = "15:00"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        tvTimer = timerView
        contentColumn.addView(
            timerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 8)
            }
        )

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", "#334155")
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }

        val goalView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tvGoal = goalView
        card.addView(goalView)

        val metaView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        }
        tvSessionMeta = metaView
        card.addView(metaView)

        val reasonView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        tvBlockReason = reasonView
        card.addView(reasonView)

        contentColumn.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 18) }
        )

        val allowedTitle = TextView(ctx).apply {
            text = "Permitted Actions & Allowed Apps"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tvAllowedTitle = allowedTitle
        contentColumn.addView(
            allowedTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        )

        val dynamicAllowedBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        allowedAppsContainer = dynamicAllowedBox
        contentColumn.addView(
            dynamicAllowedBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val homeButton = createActionButton(
            context = ctx,
            label = "Go to Phone Home Screen",
            bgHex = "#334155"
        ) {
            navigateHome("User tapped Go to Home Screen on overlay")
        }
        btnHome = homeButton
        contentColumn.addView(homeButton, buttonLayoutParams(ctx))

        val openArrestMeButton = createActionButton(
            context = ctx,
            label = "Return to Arrest Me Session Screen",
            bgHex = "#0F766E"
        ) {
            val intent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            onLaunchingPermittedApp?.invoke(currentlyBlockedPackage, appContext.packageName)
            runCatching { appContext.startActivity(intent) }
            removeOverlayInternal("Navigating back to Arrest Me app")
        }
        btnOpenArrestMe = openArrestMeButton
        contentColumn.addView(openArrestMeButton, buttonLayoutParams(ctx))

        val emergencyButton = createActionButton(
            context = ctx,
            label = "Emergency Exit Session",
            bgHex = "#7F1D1D"
        ) {
            val snap = sessionController.activeSession.value ?: activeSessionSnapshot
            if (snap?.disableEmergencyExit == true) {
                return@createActionButton
            }
            if (!emergencyArmed) {
                emergencyArmed = true
                btnEmergency?.text = "Tap Again to Confirm Emergency Stop"
                btnEmergency?.background = roundedBg("#DC2626", "#FCA5A5")
            } else {
                removeOverlayInternal("User confirmed Emergency Exit on overlay")
                scope.launch {
                    sessionController.emergencyStop("Explicit emergency exit confirmed from blocking overlay")
                }
            }
        }
        btnEmergency = emergencyButton
        contentColumn.addView(
            emergencyButton,
            buttonLayoutParams(ctx).apply { topMargin = dp(ctx, 16) }
        )

        val disabledNote = TextView(ctx).apply {
            text = "Emergency Exit is disabled for this session • Back button locked"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(ctx, 14), 0, 0)
        }
        tvEmergencyDisabledNote = disabledNote
        contentColumn.addView(
            disabledNote,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        scrollView.addView(
            contentColumn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    private fun createOverlayLayoutParams(
        wm: WindowManager,
        windowType: Int,
        coverStatusBar: Boolean
    ): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = if (coverStatusBar) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        val (w, h) = if (coverStatusBar) {
            readPhysicalDisplaySize(wm)
        } else {
            WindowManager.LayoutParams.MATCH_PARENT to WindowManager.LayoutParams.MATCH_PARENT
        }

        return WindowManager.LayoutParams(
            w,
            h,
            windowType,
            flags,
            if (coverStatusBar) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
        ).apply {
            if (coverStatusBar) {
                x = 0
                y = 0
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } else {
                gravity = Gravity.CENTER
            }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "ArrestMeBlockingOverlay"
        }
    }

    private fun syncFullScreenBoundsIfNeeded() {
        if (!attachedCoverStatusBar) return
        val view = rootOverlayView ?: return
        if (!view.isAttachedToWindow) return
        val wm = attachedWindowManager ?: return
        val lp = attachedLayoutParams ?: return
        val (w, h) = readPhysicalDisplaySize(wm)
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    private fun readPhysicalDisplaySize(wm: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { wm.maximumWindowMetrics.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val outPoint = Point()
        @Suppress("DEPRECATION")
        runCatching { wm.defaultDisplay?.getRealSize(outPoint) }
        if (outPoint.x > 0 && outPoint.y > 0) {
            return outPoint.x to outPoint.y
        }
        val dm = appContext.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun applyImmersiveSystemUi(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        @Suppress("DEPRECATION")
        view.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
    }

    private fun ensureKeyguardReceiverRegistered() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                keyguardReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    private fun unregisterKeyguardReceiverIfNeeded() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { appContext.unregisterReceiver(keyguardReceiver) }
    }

    private fun syncKeyguardVisibility() {
        val view = rootOverlayView ?: return
        if (!attachedUsingAccessibilityLayer) return
        val isLocked = keyguardManager?.isKeyguardLocked == true
        if (isLocked) {
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        } else {
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                view.requestFocus()
                if (attachedCoverStatusBar) {
                    applyImmersiveSystemUi(view)
                }
            }
        }
    }

    private fun createActionButton(
        context: Context,
        label: String,
        bgHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedBg(bgHex)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            setOnClickListener { onClick() }
        }
    }

    private fun buttonLayoutParams(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 8)
        }
    }

    private fun launchDialer() {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        onLaunchingPermittedApp?.invoke(currentlyBlockedPackage, "*dialer*")
        runCatching { appContext.startActivity(intent) }
        removeOverlayInternal("Opening permitted Phone Dialer")
    }

    private fun launchPermittedPackage(packageName: String) {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) {
            onLaunchingPermittedApp?.invoke(currentlyBlockedPackage, packageName)
            runCatching { appContext.startActivity(launchIntent) }
            removeOverlayInternal("Opening explicitly permitted app: $packageName")
        }
    }

    private fun navigateHome(reason: String) {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        removeOverlayInternal(reason)
        runCatching { appContext.startActivity(homeIntent) }
    }

    private fun roundedBg(fillHex: String, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(2, Color.parseColor(strokeHex))
            }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun createOverlayWindowContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = base.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return runCatching {
                    base.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrDefault(base)
            }
        }
        return base
    }

    private class OverlayRootContainer(
        context: Context
    ) : FrameLayout(context) {
        var onOrientationPaddingUpdate: ((Configuration) -> Unit)? = null
        var onWindowFocusRestored: (() -> Unit)? = null
        private var backInvokedCallback: Any? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val callback = OnBackInvokedCallback {
                    // Swallow system back / predictive back gesture so Arrest UI cannot be backed out of
                }
                backInvokedCallback = callback
                runCatching {
                    findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        callback
                    )
                }
            }
            requestFocus()
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (backInvokedCallback as? OnBackInvokedCallback)?.let { cb ->
                    runCatching {
                        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb)
                    }
                }
                backInvokedCallback = null
            }
            super.onDetachedFromWindow()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) {
                requestFocus()
                onWindowFocusRestored?.invoke()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            if (newConfig != null) {
                onOrientationPaddingUpdate?.invoke(newConfig)
            }
        }

        override fun dispatchKeyEventPreIme(event: KeyEvent?): Boolean {
            if (event != null && isBlockedBackKey(event.keyCode)) {
                return true
            }
            return super.dispatchKeyEventPreIme(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            if (event != null && isBlockedBackKey(event.keyCode)) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        private fun isBlockedBackKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        }
    }
}
