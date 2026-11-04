package com.arrestme.benchmark.core.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arrestme.benchmark.MainActivity
import com.arrestme.benchmark.core.monitoring.AntiCheatingMonitor
import com.arrestme.benchmark.core.monitoring.MonitorEventType
import com.arrestme.benchmark.core.permissions.PermissionChecker
import com.arrestme.benchmark.core.session.ActiveSessionSnapshot
import com.arrestme.benchmark.core.session.AllowedCategory
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

    private val overlayWindowContext: Context by lazy {
        createOverlayWindowContext(appContext)
    }

    private val windowManager: WindowManager by lazy {
        overlayWindowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var rootOverlayView: OverlayRootContainer? = null
    private var currentlyBlockedPackage: String? = null
    private var activeSessionSnapshot: ActiveSessionSnapshot? = null
    private var emergencyArmed = false

    private var tvTimer: TextView? = null
    private var tvGoal: TextView? = null
    private var tvSessionMeta: TextView? = null
    private var tvBlockReason: TextView? = null
    private var allowedAppsContainer: LinearLayout? = null
    private var btnEmergency: Button? = null

    private val _isOverlayShowing = MutableStateFlow(false)
    val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (rootOverlayView == null) return

            if (!permissionChecker.hasOverlayPermission()) {
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
            if (!permissionChecker.hasOverlayPermission()) {
                onResult(false, null, "SYSTEM_ALERT_WINDOW permission is missing")
                return@post
            }

            if (request.session.state != SessionState.ACTIVE || request.session.hasExpired()) {
                onResult(false, null, "Session is not ACTIVE or already expired")
                return@post
            }

            activeSessionSnapshot = request.session
            emergencyArmed = false

            val existingView = rootOverlayView
            if (existingView != null && existingView.isAttachedToWindow) {
                currentlyBlockedPackage = request.blockedPackageName
                bindOverlayData(request)
                val latencyMs = (SystemClock.elapsedRealtimeNanos() - request.detectionElapsedNanos) / 1_000_000.0
                onResult(true, latencyMs, null)
                return@post
            }

            // Ensure any detached stale view reference is cleaned first
            if (existingView != null) {
                runCatching { windowManager.removeViewImmediate(existingView) }
                clearViewRefs()
            }

            val newView = buildOverlayHierarchy(overlayWindowContext)
            rootOverlayView = newView
            currentlyBlockedPackage = request.blockedPackageName
            bindOverlayData(request)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                title = "ArrestMeBlockingOverlay"
            }

            val reported = AtomicBoolean(false)
            try {
                windowManager.addView(newView, layoutParams)
                _isOverlayShowing.value = true
                mainHandler.removeCallbacks(countdownRunnable)
                mainHandler.post(countdownRunnable)

                // Measure frame-commit latency via view.post with safe fallback
                newView.post {
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
        val view = rootOverlayView ?: return
        val prevPkg = currentlyBlockedPackage
        runCatching {
            windowManager.removeViewImmediate(view)
        }.onFailure {
            runCatching { windowManager.removeView(view) }
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
        currentlyBlockedPackage = null
        emergencyArmed = false
        tvTimer = null
        tvGoal = null
        tvSessionMeta = null
        tvBlockReason = null
        allowedAppsContainer = null
        btnEmergency = null
    }

    private fun bindOverlayData(request: OverlayRequest) {
        tvTimer?.text = formatDuration(request.session.computeRemainingMs())
        tvGoal?.text = "Goal: ${request.session.goal}"
        tvSessionMeta?.text = "${request.session.sessionType.label} • ID: ${request.session.sessionId} • Allowed: ${
            request.session.allowedCategories.joinToString(", ") { it.label }
        }"
        tvBlockReason?.text = "Blocked Reason: ${request.blockReason}"
        btnEmergency?.text = "Emergency Exit Session"
        btnEmergency?.background = roundedBg("#7F1D1D", strokeHex = "#EF4444")

        val container = allowedAppsContainer ?: return
        container.removeAllViews()

        // Always allow opening Phone Dialer when Calls category is permitted (and for emergency safety)
        val dialerBtn = createActionButton(
            context = container.context,
            label = "Open Phone Dialer (Calls Allowed)",
            bgHex = "#1E3A8A"
        ) {
            launchDialer()
        }
        container.addView(dialerBtn, buttonLayoutParams(container.context))

        // Explicitly permitted Study Apps
        if (AllowedCategory.STUDY_APPS in request.session.allowedCategories) {
            request.allowedStudyApps.take(6).forEach { (pkg, label) ->
                val studyBtn = createActionButton(
                    context = container.context,
                    label = "Open Study App: $label",
                    bgHex = "#065F46"
                ) {
                    launchPermittedPackage(pkg)
                }
                container.addView(studyBtn, buttonLayoutParams(container.context))
            }
        }
    }

    private fun buildOverlayHierarchy(ctx: Context): OverlayRootContainer {
        val root = OverlayRootContainer(ctx) {
            navigateHome("Back key pressed on blocking overlay")
        }.apply {
            setBackgroundColor(Color.parseColor("#F50B1120"))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
        }

        val contentColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = dp(ctx, if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 48 else 24)
            val vPad = dp(ctx, 32)
            setPadding(hPad, vPad, hPad, vPad)
        }
        root.onOrientationPaddingUpdate = { config ->
            val hPad = dp(ctx, if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 48 else 24)
            val vPad = dp(ctx, 28)
            contentColumn.setPadding(hPad, vPad, hPad, vPad)
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

        val homeBtn = createActionButton(
            context = ctx,
            label = "Go to Phone Home Screen",
            bgHex = "#334155"
        ) {
            navigateHome("User tapped Go to Home Screen on overlay")
        }
        contentColumn.addView(homeBtn, buttonLayoutParams(ctx))

        val openArrestMeBtn = createActionButton(
            context = ctx,
            label = "Return to Arrest Me Session Screen",
            bgHex = "#0F766E"
        ) {
            val intent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            removeOverlayInternal("Navigating back to Arrest Me app")
            runCatching { appContext.startActivity(intent) }
        }
        contentColumn.addView(openArrestMeBtn, buttonLayoutParams(ctx))

        val emergencyButton = createActionButton(
            context = ctx,
            label = "Emergency Exit Session",
            bgHex = "#7F1D1D"
        ) {
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
        removeOverlayInternal("Opening permitted Phone Dialer")
        runCatching { appContext.startActivity(intent) }
    }

    private fun launchPermittedPackage(packageName: String) {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) {
            removeOverlayInternal("Opening explicitly permitted app: $packageName")
            runCatching { appContext.startActivity(launchIntent) }
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
        context: Context,
        private val onBackAction: () -> Unit
    ) : FrameLayout(context) {
        var onOrientationPaddingUpdate: ((Configuration) -> Unit)? = null

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            if (newConfig != null) {
                onOrientationPaddingUpdate?.invoke(newConfig)
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBackAction()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
