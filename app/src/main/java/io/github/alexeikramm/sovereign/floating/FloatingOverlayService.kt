package io.github.alexeikramm.sovereign.floating

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import io.github.alexeikramm.sovereign.R
import io.github.alexeikramm.sovereign.theme.DynamicThemeEngine
import io.github.alexeikramm.sovereign.theme.rawTextColor
import android.os.Handler
import android.os.Looper
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.cleanup.SeamHealer
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.AppThemeMode
import io.github.alexeikramm.sovereign.data.CleaningProfile
import io.github.alexeikramm.sovereign.data.DictationHistoryStore
import io.github.alexeikramm.sovereign.data.DictationRecord
import io.github.alexeikramm.sovereign.data.DictionaryStore
import io.github.alexeikramm.sovereign.data.ProfileStore
import io.github.alexeikramm.sovereign.data.UsageStatsStore
import io.github.alexeikramm.sovereign.data.WordDiffCalculator
import io.github.alexeikramm.sovereign.dictation.DictationSession
import io.github.alexeikramm.sovereign.dictation.SessionArbiter
import io.github.alexeikramm.sovereign.engine.Engine
import io.github.alexeikramm.sovereign.engine.ModelResidency
import io.github.alexeikramm.sovereign.text.UtteranceFinalizer
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service hosting the Wispr Flow-style floating dictation pill.
 * Renders dynamically via WindowManager over any active software keyboard (Gboard, Samsung, SwiftKey).
 */
class FloatingOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var composeView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private lateinit var settings: AppSettings
    private lateinit var profileStore: ProfileStore
    private lateinit var dictionaryStore: DictionaryStore
    private lateinit var usageStatsStore: UsageStatsStore
    private lateinit var historyStore: DictationHistoryStore
    /** The one engine/audio/residency owner, shared with the desktop server (D-106). */
    private lateinit var session: DictationSession
    /** The pill's capture lease, held from capture start until the session returns to rest. */
    private var pillLease: SessionArbiter.Lease? = null

    // Compose State
    private var uiState by mutableStateOf(FloatingUiState.RESTING_PILL)
    private var previewText by mutableStateOf("")
    private var rawText by mutableStateOf("")
    /** The recogniser's live provisional text (raw-preview); mirrored from the pipeline each partial. */
    private var provisionalText by mutableStateOf("")
    private var audioLevel by mutableFloatStateOf(0f)
    private var isProcessing by mutableStateOf(false)
    private var statusNote by mutableStateOf<String?>(null)
    /** Non-null when cleanup failed: the card shows raw ASR and a friendly warning. */
    private var errorMessage by mutableStateOf<String?>(null)
    /** True once the ASR recogniser is resident; the other half of the pill's warm indicator. */
    private var asrModelLoaded by mutableStateOf(false)
    /** True once the cleanup LLM is resident. Only relevant while `cleanupEnabled` is true. */
    private var cleanupModelLoaded by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    /** Mirrors `AppSettings.cleanupEnabled` so the settings view reflects the toggle immediately. */
    private var cleanupEnabled by mutableStateOf(true)
    private var darkTheme by mutableStateOf(false)
    private var dynamicColorScheme by mutableStateOf<ColorScheme?>(null)
    private var profiles by mutableStateOf(emptyList<CleaningProfile>())
    private var activeProfileId by mutableStateOf("")

    /** True between a quick capture's arm and its release; the release is the only normal stop. */
    private var quickCaptureActive = false
    /** The brief result shown on the minimized pill after a quick capture, or null. */
    private var quickStatus by mutableStateOf<String?>(null)
    /** True when [quickStatus] is a failure. */
    private var quickStatusIsError by mutableStateOf(false)
    /** Debug override for testing/inspecting the minimized pill's six states. */
    private var debugPillPhase by mutableStateOf<MinimizedPillPhase?>(null)

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("phase")?.let { phaseName ->
                debugPillPhase = when (phaseName.uppercase()) {
                    "COLD" -> MinimizedPillPhase.COLD
                    "READY" -> MinimizedPillPhase.READY
                    "RECORDING" -> MinimizedPillPhase.RECORDING
                    "CLEANING" -> MinimizedPillPhase.CLEANING
                    "RESULT" -> MinimizedPillPhase.RESULT
                    "ERROR" -> MinimizedPillPhase.ERROR
                    "RESET", "CLEAR", "NONE" -> null
                    else -> null
                }
                Log.i(TAG, "debug receiver set phase: $debugPillPhase")
            }
            intent?.getStringExtra("seed")?.let { seedStr ->
                val clean = seedStr.removePrefix("#")
                val fullHex = if (clean.length == 6) "FF$clean" else clean
                val argb = fullHex.toLongOrNull(16)?.toInt()
                if (argb != null) {
                    settings.themeSeedColor = argb or -0x1000000
                    Log.i(TAG, "debug receiver set seedColor: $seedStr -> $argb")
                }
            }
            intent?.getStringExtra("style")?.let { styleName ->
                settings.themePaletteStyle = styleName.uppercase()
                Log.i(TAG, "debug receiver set paletteStyle: $styleName")
            }
            intent?.getStringExtra("card")?.let { cardAction ->
                when (cardAction.uppercase()) {
                    "OPEN", "EXPAND", "REVIEW" -> {
                        val text = intent.getStringExtra("transcript")
                            ?: "So the plan for this week is to finish the fixture set, then move on to the benchmark harness, and after that we can prune the model list down to the three winners before any feature work starts on the cleanup pass."
                        previewText = text
                        rawText = text
                        uiState = if (intent.getStringExtra("state")?.uppercase() == "RECORDING") {
                            FloatingUiState.RECORDING
                        } else {
                            FloatingUiState.REVIEW
                        }
                        openCard()
                    }
                    "CLOSE", "COLLAPSE" -> {
                        startCardCollapse(animated = false)
                    }
                }
                Log.i(TAG, "debug receiver card action: $cardAction, uiState: $uiState")
            }
        }
    }

    /** The word the user long-pressed in the review transcript, or null when none is selected. */
    private var wordSelection by mutableStateOf<WordSelection?>(null)
    /** Non-null while a word action's input form is open. */
    private var wordForm by mutableStateOf<WordFormState?>(null)
    /**
     * Whether the overlay window is currently allowed to take input focus. It is non-focusable at
     * rest (so it never steals the keyboard from the app); the word-fix form flips it on for its
     * lifetime so the keyboard can reach the correction field.
     */
    private var windowFocusable = false

    // Drag / fling state. The pill's position is kept as floats so a slow drag is not truncated
    // into never moving; it is flushed to the window at most once per frame.
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging = false
    private var dragFlushScheduled = false
    private val dragVelocity = DragVelocityTracker()

    /**
     * The pointer's latest **screen** position. Recorded in the overlay view's
     * `dispatchTouchEvent`, because screen coordinates do not collapse when the window moves under
     * the finger. See [WindowDrag].
     */
    private var pointerScreenX = 0f
    private var pointerScreenY = 0f

    /** The drag session that maps the pointer's screen position to the window offset. */
    private val windowDrag = WindowDrag()

    /** Unregisters the screen-off receiver; set once registration succeeds. */
    private var screenOffReceiverRegistered = false

    // Fling: one decaying velocity vector for both axes (see PillFling), stepped per frame.
    private var fling = PillFling()
    private var flingVelocityX = 0f
    private var flingVelocityY = 0f
    private var flingLastNanos = 0L
    private var flingStartedAtNanos = 0L
    private var flingStartX = 0f
    private var flingStartY = 0f

    // Edge snap: smoothly springs the resting pill into the nearer screen edge with optional Y momentum follow-through.
    private var snapRunning = false
    private var snapStartX = 0f
    private var snapTargetX = 0f
    private var snapProgressX = 0f
    private var snapVelocityX = 0f
    private var snapStartY = 0f
    private var snapTargetY = 0f
    private var snapProgressY = 0f
    private var snapVelocityY = 0f
    private var snapLastNanos = 0L
    private var snapStiffness = PillSnap.SNAP_STIFFNESS
    private var snapDampingRatio = PillSnap.SNAP_DAMPING_RATIO

    // Entering and leaving: the pill's fraction plus its velocity, stepped per frame by [entryTick]
    // — the same shape as the fling above. `entryValue` is the transition fraction where `1f` is the
    // pill at its rest dock and `0f` is fully off screen (`PillEnter`), so the spring's units are
    // fractions and the same curve serves any travel: the arrival drives it `0f -> 1f` and the
    // departure drives the *same* pair back. While [entryRunning] the tick's writes are the *only*
    // position writes: every other writer clamps to the screen and would drag the pill back on
    // screen mid-slide. `entryStart`/`entryRest` stay non-null after the motion ends on purpose —
    // a reversal reuses that pair, so a flip cannot recompute (and so cannot jump).
    private var entryValue = 1f
    private var entryVelocity = 0f
    private var entryTarget = 1f
    private var entryStart: PillOffset? = null
    private var entryRest: PillOffset? = null
    private var entryLastNanos = 0L
    private var entryStartedAtNanos = 0L
    private var entryRunning = false

    /**
     * The second overlay window: the expanded card. Both windows are **fixed size** — the pill
     * window is always pill-sized, this one always card-sized — so the transition never resizes a
     * window, which is what produced the old swap-frame glitch. The card is shown/hidden and its
     * content scaled inside the window; see `docs/work/archive/card-expand-animation/`.
     */
    private var cardView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null

    // Card expansion: the pill-to-card container transform. `expandProgress` is `0f` at the pill's
    // size and `1f` at the card's; `cardGrowth` carries the start scale and transform origin that
    // `PillExpand` computed at the seam.
    private var expandProgress by mutableFloatStateOf(1f)
    private var expandVelocity = 0f
    private var expandTarget = 1f
    private var expandLastNanos = 0L
    private var expandStartedAtNanos = 0L
    private var expandRunning = false
    /** True while the card is animating out; drives the container fade in the card window. */
    private var cardClosing by mutableStateOf(false)
    /** Runs once when a collapse morph lands; the pending state reset behind it. */
    private var collapseAfter: (() -> Unit)? = null
    private var cardGrowth by mutableStateOf(PillGrowth.NONE)
    /**
     * True while the card window is visible. The pill window stays put; the card is a separate
     * fixed-size window, so the pill's geometry is never touched by the expansion.
     */
    private var cardWindowShown = false
    /**
     * True while the card occupies the pill's screen real estate (open through the collapse morph),
     * which is what hides the pill window underneath. Cleared at the start of the collapse so the
     * real pill is already on screen while the card shrinks and fades onto it.
     */
    private var cardHoldsScreen = false
    /** Where the card window was placed for the current session. */
    private var cardWindowOffset: PillPoint? = null

    private val dragFlush = Runnable {
        dragFlushScheduled = false
        applyWindowPosition()
    }

    private val flingTick = object : Runnable {
        override fun run() {
            val startedAt = flingLastNanos
            if (startedAt == 0L) return
            val now = System.nanoTime()
            // A dropped frame should not teleport the pill; cap the step at ~3 frames.
            val dtSeconds = ((now - startedAt) / 1_000_000_000f).coerceIn(0f, 0.05f)
            flingLastNanos = now

            val step = fling.advance(dragOffsetX, dragOffsetY, flingVelocityX, flingVelocityY, dtSeconds)
            flingVelocityX = step.velocityX
            flingVelocityY = step.velocityY

            // Bounce nothing: hitting an edge drops only the outward component, so the pill slides
            // along the edge while the rest of the momentum bleeds off.
            val (maxX, maxY) = maxOffsets()
            var nextX = step.x
            var nextY = step.y
            if (nextX < 0f || nextX > maxX) {
                nextX = nextX.coerceIn(0f, maxX.toFloat())
                flingVelocityX = 0f
            }
            if (nextY < 0f || nextY > maxY) {
                nextY = nextY.coerceIn(0f, maxY.toFloat())
                flingVelocityY = 0f
            }
            dragOffsetX = nextX
            dragOffsetY = nextY
            pushWindowPosition()

            if (fling.isFinished(flingVelocityX, flingVelocityY)) {
                val travelled = kotlin.math.hypot(dragOffsetX - flingStartX, dragOffsetY - flingStartY)
                val elapsedMs = (System.nanoTime() - flingStartedAtNanos) / 1_000_000
                Log.i(TAG, "Fling settled: travelled %.0f px in %d ms".format(travelled, elapsedMs))
                stopFling()
                if (settings.floatingPillEdgeAnchoring) {
                    startSnap()
                } else {
                    persistPillPosition()
                }
                return
            }
            composeView?.postOnAnimation(this)
        }
    }

    /**
     * One frame of the edge-snap motion: smoothly glides the pill into the nearer screen edge
     * using [PillSpring] while carrying attenuated vertical flick momentum (Chat Heads style).
     */
    private val snapTick = object : Runnable {
        override fun run() {
            if (!snapRunning) return
            val startedAt = snapLastNanos
            if (startedAt == 0L) return
            val now = System.nanoTime()
            val dtSeconds = ((now - startedAt) / 1_000_000_000f).coerceIn(0f, PillSpring.MAX_STEP_SECONDS)
            snapLastNanos = now

            val stepX = PillSnap.step(
                value = snapProgressX,
                velocity = snapVelocityX,
                target = 1f,
                dtSeconds = dtSeconds,
                stiffness = snapStiffness,
                dampingRatio = snapDampingRatio,
            )
            snapProgressX = stepX.value
            snapVelocityX = stepX.velocity

            val currentX = snapStartX + (snapTargetX - snapStartX) * snapProgressX.coerceIn(0f, 1f)
            dragOffsetX = currentX

            val currentY = if (snapTargetY != snapStartY) {
                val stepY = PillSnap.step(
                    value = snapProgressY,
                    velocity = snapVelocityY,
                    target = 1f,
                    dtSeconds = dtSeconds,
                    stiffness = snapStiffness,
                    dampingRatio = snapDampingRatio,
                )
                snapProgressY = stepY.value
                snapVelocityY = stepY.velocity
                snapStartY + (snapTargetY - snapStartY) * snapProgressY.coerceIn(0f, 1f)
            } else {
                snapStartY
            }
            dragOffsetY = currentY
            pushWindowPosition()

            val atRestX = PillSnap.isAtRest(snapProgressX, snapVelocityX, 1f)
            val atRestY = snapTargetY == snapStartY || PillSnap.isAtRest(snapProgressY, snapVelocityY, 1f)

            if (atRestX && atRestY) {
                dragOffsetX = snapTargetX
                dragOffsetY = snapTargetY
                applyWindowPosition()
                persistPillPosition()
                stopSnap()
                Log.i(TAG, "Pill snap settled at ($snapTargetX, $snapTargetY)")
                return
            }
            composeView?.postOnAnimation(this)
        }
    }

    /**
     * One frame of the entry motion — the arrival or the departure, whichever [entryTarget] names.
     *
     * It writes the window **only** through [writeWindowPosition] — never through
     * [applyWindowPosition], which clamps to the screen and would drag the pill back on screen
     * mid-slide. The clamps are suspended while [entryRunning] (the layout listener's guard) and
     * applied once, here, when the arrival ends.
     */
    private val entryTick = object : Runnable {
        override fun run() {
            if (!entryRunning) return
            val now = System.nanoTime()
            // The fling's own cap and reason: a dropped frame should not teleport the pill.
            val dtSeconds = ((now - entryLastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            entryLastNanos = now

            val step = PillSpring.step(entryValue, entryVelocity, entryTarget, dtSeconds)
            entryValue = step.value
            entryVelocity = step.velocity

            val start = entryStart
            val rest = entryRest
            if (start == null || rest == null) {
                // The pair was dropped (a drag took over, a cancel, a removal): stop rather than
                // write from a state nobody owns any more.
                cancelEntry(restore = false)
                return
            }
            val at = PillEnter.positionAt(start, rest, entryValue)
            writeWindowPosition(PillPoint(at.x.toInt(), at.y.toInt()))

            if (PillSpring.isAtRest(entryValue, entryVelocity, entryTarget)) {
                // The two directions end differently — the arrival lands on the dock, the departure
                // hides — so each has its own end-state routine with its own pinned order.
                if (entryTarget == 0f) finishExit(rest) else finishEntry(rest)
                return
            }
            composeView?.postOnAnimation(this)
        }
    }

    /**
     * One frame of the card's expansion: the card's `graphicsLayer` scale is a pure function of
     * [expandProgress], so this tick only advances the fraction. The same `PillSpring` and the same
     * dt cap as the arrival and the fling; the window is not touched.
     */
    private val expandTick = object : Runnable {
        override fun run() {
            if (!expandRunning) return
            val now = System.nanoTime()
            val dtSeconds = ((now - expandLastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            expandLastNanos = now
            val step = PillSpring.step(expandProgress, expandVelocity, expandTarget, dtSeconds)
            expandProgress = step.value
            expandVelocity = step.velocity
            if (PillSpring.isAtRest(expandProgress, expandVelocity, expandTarget)) {
                expandProgress = expandTarget
                expandRunning = false
                Log.i(TAG, "Card ${if (expandTarget == 0f) "collapsed" else "expanded"} in ${(now - expandStartedAtNanos) / 1_000_000}ms")
                val after = if (expandTarget == 0f) collapseAfter else null
                collapseAfter = null
                after?.invoke()
                return
            }
            cardView?.postOnAnimation(this)
        }
    }

    private var inFlightJob: Job? = null
    private var silenceMonitoringJob: Job? = null

    /** Wall-clock accounting for the usage stats the dashboard shows. */
    private var recordStartedAtMs = 0L
    private var recordedMillis = 0L
    private var captureStoppedAtMs = 0L
    private var turnaroundLatencyMs = 0L
    private var reprocessActiveLlmMs: Long? = null

    // Keyboard & focus visibility tracker
    private var isKeyboardOpen = false
    private var hasEditableFocus = false

    /**
     * Screen-off is the one unload trigger with no callback in the service, so it needs a receiver.
     * Registered only for the system broadcast, and gated on the setting at receive time so a toggle
     * takes effect without re-registering.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (!settings.unloadOnScreenOff) return
            session.onScreenOff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        profileStore = ProfileStore(this)
        dictionaryStore = DictionaryStore(this)
        usageStatsStore = UsageStatsStore(this)
        historyStore = DictationHistoryStore(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Log.w(TAG, "RECORD_AUDIO not granted; stopping FloatingOverlayService")
            stopSelf()
            return
        }

        createNotificationChannel()
        startForegroundWithNotification()

        darkTheme = resolveDarkTheme()
        dynamicColorScheme = resolveColorScheme()
        // The pill is its own window, so the app changing theme does not reach it by itself.
        settings.observeTheme {
            Handler(Looper.getMainLooper()).post {
                darkTheme = resolveDarkTheme()
                dynamicColorScheme = resolveColorScheme()
            }
        }
        // Cleanup can be toggled from the pill's menu, the Home status card, or Settings. The pill is
        // its own window, so it reads the single source of truth through this observer rather than
        // keeping a separately-written copy that could drift.
        settings.observeCleanupEnabled {
            Handler(Looper.getMainLooper()).post {
                cleanupEnabled = settings.cleanupEnabled
                Log.i(TAG, "Cleanup preference changed; pill now shows ${if (cleanupEnabled) "on" else "off"}")
            }
        }
        cleanupEnabled = settings.cleanupEnabled
        refreshProfiles()
        setupSession()
        registerScreenOffReceiver()
        val debugFilter = IntentFilter().apply {
            addAction("io.github.alexeikramm.sovereign.DEBUG_PILL_PHASE")
            addAction("io.github.alexeikramm.sovereign.DEBUG_THEME")
        }
        ContextCompat.registerReceiver(
            this,
            debugReceiver,
            debugFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        setupOverlayView()
        updateOverlayVisibility()
        Log.i(TAG, "FloatingOverlayService initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("debug_phase")?.let { phaseName ->
            debugPillPhase = when (phaseName.uppercase()) {
                "COLD" -> MinimizedPillPhase.COLD
                "READY" -> MinimizedPillPhase.READY
                "RECORDING" -> MinimizedPillPhase.RECORDING
                "CLEANING" -> MinimizedPillPhase.CLEANING
                "RESULT" -> MinimizedPillPhase.RESULT
                "ERROR" -> MinimizedPillPhase.ERROR
                "RESET", "CLEAR", "NONE" -> null
                else -> null
            }
            Log.i(TAG, "debug_phase set to: $debugPillPhase")
        }
        updateOverlayVisibility()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        darkTheme = resolveDarkTheme()
        dynamicColorScheme = resolveColorScheme()
        // A display change cannot be animated into: abandon any arrival, put the window back at its
        // rest position, then re-clamp so the pill cannot be stranded off-screen.
        cancelEntry(restore = true)
        composeView?.post { applyWindowPosition() }
        // The card window's size is derived from the density/display metrics, so a config change
        // (density, rotation) must re-derive it. It is only written when no card session is running:
        // mid-session the geometry is owned by the open/close motion.
        val card = cardView
        val cardParamsRef = cardParams
        if (card != null && cardParamsRef != null && !cardWindowShown) {
            val (width, height) = cardWindowSizePx(resources.displayMetrics.density)
            cardParamsRef.width = width
            cardParamsRef.height = height
            runCatching { windowManager.updateViewLayout(card, cardParamsRef) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        settings.removeThemeModeObserver()
        settings.removeEdgeAnchoringObserver()
        settings.removeCleanupEnabledObserver()
        stopSnap()
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        runCatching { unregisterReceiver(debugReceiver) }
        abandonCapture()
        removeOverlayView()
        scope.cancel()
        session.shutdown()
        releasePillLease()
        Log.i(TAG, "FloatingOverlayService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sovereign Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the floating dictation pill active"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sovereign")
            .setContentText("Floating Dictation Pill active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMic) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to startForeground: missing permission", e)
            stopSelf()
        }
    }

    /**
     * Builds the one headless [DictationSession] — the engines, recorder, dispatchers and residency
     * that used to live here (D-106). The pill is now an adapter: it mirrors the session's text into
     * Compose state and drives capture through it. UI state that has no place in a headless engine
     * (audio level, error text, warmth indicators) is mirrored back via the callbacks.
     */
    private fun setupSession() {
        session = DictationSession(
            context = this,
            settings = settings,
            profileStore = profileStore,
            dictionaryStore = dictionaryStore,
            scope = scope,
            // All three strings arrive together, so a commit clears the provisional tail in the same
            // update that shows the committed text — no separate partial channel to fall out of sync.
            onText = { committed, raw, provisional ->
                previewText = committed
                rawText = raw
                provisionalText = if (settings.streamingPreviewEnabled) provisional else ""
            },
            onCleanupUnavailable = { errorMessage = CLEANUP_UNAVAILABLE },
            onAudioLevel = { level -> audioLevel = level },
            onModelResidencyChanged = { asrLoaded, cleanupLoaded ->
                asrModelLoaded = asrLoaded
                cleanupModelLoaded = cleanupLoaded
            },
        )
    }

    /** Releases the pill's capture lease, if it holds one. Safe to call repeatedly. */
    private fun releasePillLease() {
        pillLease?.let { session.arbiter.release(it) }
        pillLease = null
    }

    /**
     * Resolves the provisional text's colour from the user's chosen M3 role + opacity, against the
     * overlay's live scheme. Resolved at call time rather than stored, so a theme change or a settings
     * edit takes effect without restarting the pill.
     */
    private fun provisionalTailColor(): Color {
        val scheme = dynamicColorScheme ?: return Color.Unspecified
        return rawTextColor(
            role = settings.streamingPreviewColorRole,
            opacity = settings.streamingPreviewOpacity,
            scheme = scheme,
        )
    }

    /**
     * The Compose content for one of the two overlay windows. Both windows host the same overlay
     * composable and differ only in what they are allowed to render ([hostsCard]/[hostsPill]), so
     * every state and callback is threaded from this one place; the pill window never builds the
     * card and the card window never builds the pill. See `docs/work/archive/card-expand-animation/`.
     */
    private fun buildOverlayComposeView(hostsCard: Boolean, hostsPill: Boolean): ComposeView =
        ComposeView(this).apply {
            setContent {
                FloatingDictationOverlay(
                    darkTheme = darkTheme,
                    colorScheme = dynamicColorScheme,
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    provisionalText = provisionalText,
                    provisionalColor = provisionalTailColor(),
                    audioLevel = { audioLevel },
                    cardGrowth = cardGrowth,
                    cardExpandProgress = { expandProgress },
                    isProcessing = isProcessing,
                    statusNote = statusNote,
                    errorMessage = errorMessage,
                    // Breathing means "everything this configuration needs is resident", which is
                    // ASR plus the LLM unless the user turned cleanup off.
                    modelReady = ModelResidency.isFullyWarm(
                        asrLoaded = asrModelLoaded,
                        cleanupEnabled = cleanupEnabled,
                        cleanupLoaded = cleanupModelLoaded,
                    ),
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    cleanupEnabled = cleanupEnabled,
                    settingsOpen = settingsOpen,
                    wordSelection = wordSelection,
                    wordForm = wordForm,
                    // Quick capture swallows new presses while it flushes and injects; the tap path
                    // must not start a card session under it.
                    onPillClicked = {
                        if (uiState == FloatingUiState.RESTING_PILL) {
                            startOrResumeRecording(fresh = true)
                        } else if (uiState == FloatingUiState.QUICK_CAPTURE && quickStatusIsError) {
                            resetToResting()
                        }
                    },
                    onCancel = ::cancelSession,
                    onToggleRecording = ::toggleRecording,
                    onCommit = ::commitSession,
                    onReprocessFullText = ::reprocessFullText,
                    onToggleSettings = {
                        dismissWordSelection()
                        settingsOpen = !settingsOpen
                        // The active profile can change from Home or the Profiles tab while the pill
                        // lives on; re-read on open so the list never shows a stale choice.
                        if (settingsOpen) refreshProfiles()
                    },
                    onSelectProfile = ::selectProfile,
                    onToggleCleanup = ::toggleCleanup,
                    onWordSelected = ::selectWord,
                    onWordAction = ::openWordAction,
                    onWordFormSubmit = ::submitWordForm,
                    onWordDismiss = ::dismissWordSelection,
                    quickCaptureEnabled = { settings.quickDictateOnHold },
                    quickStatus = quickStatus,
                    quickStatusIsError = quickStatusIsError,
                    onQuickCaptureStart = ::startQuickCapture,
                    onQuickCaptureEnd = ::releaseQuickCapture,
                    onDragStart = ::beginDrag,
                    onDragBy = ::dragBy,
                    onDragEnd = ::endDrag,
                    onDragCancel = ::endDragWithoutFling,
                    hostsCard = hostsCard,
                    hostsPill = hostsPill,
                    debugPillPhase = debugPillPhase,
                    cardClosing = cardClosing,
                    pillBackdrop = cardWindowShown || cardClosing,
                )
            }
        }

    /** The card window's pixel size (card + the same transparent inset the pill window carries). */
    private fun cardWindowSizePx(density: Float): Pair<Int, Int> {
        val inset = OVERLAY_INSET.value * density
        return (CARD_WIDTH.value * density + 2f * inset).roundToInt() to
            (CARD_HEIGHT.value * density + 2f * inset).roundToInt()
    }

    private fun setupOverlayView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; cannot display floating pill")
            return
        }

        val overlayOwner = OverlayLifecycleOwner()
        overlayLifecycleOwner = overlayOwner
        overlayOwner.start()

        val density = resources.displayMetrics.density
        val start = PillDock.restore(settings.floatingPillX, settings.floatingPillY, density)
        dragOffsetX = start.x.toFloat()
        dragOffsetY = start.y.toFloat()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            overlayFlags(focusable = false),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = start.x
            y = start.y
        }
        layoutParams = params

        val content = buildOverlayComposeView(hostsCard = false, hostsPill = true)

        // The ComposeView is wrapped so the window root can capture the pointer's screen position
        // before Compose consumes the touch (see PillRootView).
        val view = PillRootView(this).apply {
            overlayOwner.attach(this)
            onPointerScreen = { x, y ->
                pointerScreenX = x
                pointerScreenY = y
            }
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ),
            )
        }

        // `FLAG_WATCH_OUTSIDE_TOUCH` delivers a single ACTION_OUTSIDE for a touch that lands outside
        // the pill, *without consuming it* — so the app underneath still receives its own tap. We get
        // it here, before Compose sees anything, and only for that one action; everything else falls
        // through to Compose untouched. If a future platform stops delivering it, deleting this block
        // and the flag is the whole removal.
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                true
            } else {
                false
            }
        }

        // The expanded card is wider than the resting pill, so a size change can leave the window
        // hanging off the edge: clamp **horizontally** on every layout, but never vertically here.
        // The window is anchored bottom/end, so a taller layout (the `···` panel animating its
        // height) simply grows upward and the card stays exactly where it is. Re-clamping vertically
        // on those frames — or shortly after them — is what made the pill jump; the vertical clamp
        // is left to the explicit drag / fling / display-change paths instead.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Never while a drag, a fling, an arrival or the card window owns the position: those
            // writes are unclamped on purpose, and this clamp would drag the pill back on screen
            // mid-slide or fight the expansion's own geometry.
            if (!isDragging && flingLastNanos == 0L && !entryRunning && !cardWindowShown && !snapRunning) {
                applyWindowPosition(clampVertical = false)
            }
        }

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }

        setupCardWindow()
        settings.observeEdgeAnchoring { onEdgeAnchoringChanged() }
    }

    /**
     * Adds the card window once, at its fixed card size and hidden. Pre-adding it (rather than
     * adding it on the first expand) means showing the card is a visibility flip on an
     * already-measured window: the surface is the right size before the first frame is drawn, so no
     * resize ever races the content. Its position is set per session by [openCard].
     */
    private fun setupCardWindow() {
        val density = resources.displayMetrics.density
        val (width, height) = cardWindowSizePx(density)
        val start = PillDock.restore(settings.floatingPillX, settings.floatingPillY, density)
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            overlayFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = start.x
            y = start.y
        }
        val view = PillRootView(this).apply {
            overlayLifecycleOwner?.attach(this)
            addView(
                buildOverlayComposeView(hostsCard = true, hostsPill = false),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ),
            )
            // The same outside-tap contract as the pill: an ACTION_OUTSIDE (delivered because the
            // window sets FLAG_WATCH_OUTSIDE_TOUCH) minimises the card without consuming the app's
            // touch.
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    onOutsideTouch()
                    true
                } else {
                    false
                }
            }
            visibility = View.GONE
        }
        cardView = view
        cardParams = params
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add card view", e)
            cardView = null
            cardParams = null
        }
    }

    /** The overlay window type, shared by the pill and card windows. */
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun removeOverlayView() {
        stopFling()
        stopSnap()
        stopCardExpand()
        cardWindowShown = false
        cardHoldsScreen = false
        cardClosing = false
        // Nothing may write to a window that is going away.
        cancelEntry(restore = false)
        composeView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
        composeView = null
        cardView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing card view", e)
            }
        }
        cardView = null
        cardParams = null
        overlayLifecycleOwner?.destroy()
        overlayLifecycleOwner = null
    }

    // --- Long-press a word: fix it --------------------------------------------
    //
    // The mechanism is deliberately data-driven: the view reports the pressed word, the menu is
    // built from `WordAction.entries`, and every action's behaviour lives in one `when` in
    // [submitWordForm]. Adding an action (say, "Replace word") is a case in the catalogue plus a
    // branch there; the selection, the highlight, the bar and the form are all shared.

    /**
     * The window flags for the pill. [focusable] is false at rest, which is what keeps the pill from
     * stealing the keyboard from the app underneath. The word-fix form flips it on for its lifetime:
     * without focus the keyboard cannot reach the correction field, because a window added with
     * `FLAG_NOT_FOCUSABLE` never receives input. `FLAG_NOT_TOUCH_MODAL` keeps touches outside the
     * pill going to the app even while the window is focusable.
     */
    private fun overlayFlags(focusable: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Observe touches that land outside the pill (without stealing them from the app
            // underneath) so an outside tap can dismiss the ··· panel and minimise the card.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val focus = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        // Composed here, not OR-ed onto `params.flags` at the capture sites: `setOverlayFocusable`
        // rebuilds the whole int from this function whenever the word-fix form opens or closes, so
        // a bit added anywhere else would be silently dropped by the next focus toggle.
        val keepScreen = if (ScreenAwake.shouldHold(session.isRecording, settings.keepScreenOnWhileRecording)) {
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            0
        }
        return base or focus or keepScreen
    }

    /**
     * The window that currently owns the user's attention: the card while it is shown, the pill
     * otherwise. Focus and keep-screen are properties of the *visible* window — the hidden one
     * cannot hold the screen or receive the IME.
     */
    private fun activeInputView(): View? = if (cardWindowShown) cardView ?: composeView else composeView

    private fun activeInputParams(): WindowManager.LayoutParams? =
        if (cardWindowShown) cardParams ?: layoutParams else layoutParams

    /**
     * Applies or releases the pill window's `FLAG_KEEP_SCREEN_ON` hold for the current capture
     * state (D-050). The desired state comes from the **live recorder and setting**, not from
     * [uiState]: `stopRecording` enters `REVIEW` while the mic is still finishing, so a UI-state
     * trigger would drop the hold before the microphone actually stopped. A no-op when the bit is
     * already correct, so the cheap calls at the capture boundaries cost nothing.
     */
    private fun refreshKeepScreenOn() {
        val params = activeInputParams() ?: return
        val view = activeInputView() ?: return
        val desired = ScreenAwake.shouldHold(session.isRecording, settings.keepScreenOnWhileRecording)
        val current = (params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (desired == current) return
        params.flags = overlayFlags(windowFocusable)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating keep-screen-on flag", e)
        }
    }

    /**
     * Flips the overlay between "observe only" and "can take the keyboard". Re-applies the window
     * layout, and hides the IME again on the way back so a keyboard aimed at a form that no longer
     * exists does not linger.
     */
    private fun setOverlayFocusable(focusable: Boolean) {
        if (windowFocusable == focusable) return
        windowFocusable = focusable
        val params = activeInputParams() ?: return
        val view = activeInputView() ?: return
        params.flags = overlayFlags(focusable)
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        } else {
            0
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay focusability", e)
        }
        if (!focusable) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /** A word was long-pressed in the review transcript: highlight it and offer the actions. */
    private fun selectWord(selection: WordSelection) {
        wordSelection = selection
        wordForm = null
        // The menu itself needs no typing, so the window stays non-focusable until a form opens.
        setOverlayFocusable(false)
        Log.i(TAG, "Word selected: '${selection.word}' (${selection.start}..${selection.end})")
    }

    /** A word action was chosen: open its input form and let the keyboard reach it. */
    private fun openWordAction(action: WordAction) {
        val selection = wordSelection ?: return
        setOverlayFocusable(true)
        wordForm = WordFormState(action = action, word = selection.word)
        Log.i(TAG, "Word action '${action.id}' opened for '${selection.word}'")
    }

    /**
     * The form was submitted. This is where each catalogue action does its work; today they all save
     * the pressed word to the shared dictionary, merging into an existing entry for the typed word.
     */
    private fun submitWordForm(value: String) {
        val selection = wordSelection
        val action = wordForm?.action
        if (selection != null && action != null) {
            when (action) {
                WordAction.ADD_TO_DICTIONARY -> {
                    val fix = WordFix.correction(heard = selection.word, replacement = value)
                    if (fix != null) {
                        dictionaryStore.addCorrection(fix.correct, listOf(fix.incorrect))
                        statusNote = "Added \u201C${fix.correct}\u201D to the dictionary"
                        Log.i(TAG, "Dictionary correction saved: '${fix.incorrect}' -> '${fix.correct}'")
                    }
                }
            }
        }
        dismissWordSelection()
    }

    /** Clears the long-press selection and form, and gives the keyboard back to the app. */
    private fun dismissWordSelection() {
        if (wordSelection == null && wordForm == null && !windowFocusable) return
        wordSelection = null
        wordForm = null
        setOverlayFocusable(false)
    }

    // --- Dragging ---
    //
    // `updateViewLayout` is a real window relayout, so the old code's one-call-per-pointer-event
    // could miss frames at 120 Hz. Instead the pointer deltas accumulate in floats and a single
    // frame-coalesced flush writes them to the window. The release velocity comes from the window
    // offset's own trajectory (`DragVelocityTracker`) and the glide from `PillFling` — one decaying
    // vector for both axes, so a diagonal flick stays diagonal.

    private fun beginDrag() {
        stopFling()
        stopSnap()
        // The drag takes over: stop any arrival and drop the entry state **without** writing a
        // position — the drag's own base is the current `params.x/y`, which already includes the
        // entry offset, and its release clamps and persists as it does today.
        cancelEntry(restore = false)
        isDragging = true
        layoutParams?.let {
            dragOffsetX = it.x.toFloat()
            dragOffsetY = it.y.toFloat()
            windowDrag.begin(
                screen = PillOffset(pointerScreenX, pointerScreenY),
                offset = PillOffset(dragOffsetX, dragOffsetY),
            )
        }
        dragVelocity.reset(System.nanoTime(), dragOffsetX, dragOffsetY)
    }

    private fun dragBy(dx: Float, dy: Float) {
        // `dx`/`dy` are window-local pointer deltas, which shrink as the window moves under the
        // finger; the pointer's screen position does not (WindowDrag). `dx`/`dy` stay in the
        // signature because the card's grip shares this callback, and are the fallback if there is
        // no window to move.
        val next = if (layoutParams != null) {
            windowDrag.offsetFor(pointerScreenX, pointerScreenY)
        } else {
            null
        }
        if (next != null) {
            dragOffsetX = next.x
            dragOffsetY = next.y
        } else {
            dragOffsetX -= dx
            dragOffsetY -= dy
        }
        dragVelocity.sample(System.nanoTime(), dragOffsetX, dragOffsetY)
        if (!dragFlushScheduled) {
            dragFlushScheduled = true
            composeView?.postOnAnimation(dragFlush)
        }
    }

    private fun endDrag() {
        isDragging = false
        val view = composeView ?: return
        val velocity = dragVelocity.velocity()

        if (settings.floatingPillEdgeAnchoring) {
            startSnap(velocity)
            return
        }

        if (velocity.speed < FLING_MIN_VELOCITY_PX_PER_SECOND) {
            applyWindowPosition()
            persistPillPosition()
            return
        }

        // Read the friction per flick, so tuning it in Settings takes effect immediately.
        fling = PillFling(settings.pillFlickFriction)
        flingVelocityX = velocity.x
        flingVelocityY = velocity.y
        flingStartedAtNanos = System.nanoTime()
        flingStartX = dragOffsetX
        flingStartY = dragOffsetY
        flingLastNanos = flingStartedAtNanos
        view.postOnAnimation(flingTick)
        Log.i(
            TAG,
            "Fling %.0f px/s (friction %.1f/s -> predicted ~%.0f px)".format(
                velocity.speed,
                settings.pillFlickFriction,
                fling.travelFor(velocity.speed),
            ),
        )
    }

    private fun endDragWithoutFling() {
        isDragging = false
        stopFling()
        if (settings.floatingPillEdgeAnchoring) {
            startSnap()
        } else {
            applyWindowPosition()
            persistPillPosition()
        }
    }

    private fun stopFling() {
        flingVelocityX = 0f
        flingVelocityY = 0f
        flingLastNanos = 0L
    }

    private fun startSnap(velocity: PillVelocity = PillVelocity(0f, 0f)) {
        val view = composeView ?: return
        val margin = (PillDock.DRAG_MARGIN_DP * resources.displayMetrics.density).toInt()
        val screen = windowManager.currentWindowMetrics.bounds
        snapStiffness = settings.pillSnapStiffness
        snapDampingRatio = settings.pillSnapDamping
        val targetPoint = PillSnap.snapPosition(
            current = desiredPoint(),
            screenWidthPx = screen.width(),
            screenHeightPx = screen.height(),
            viewWidthPx = view.width,
            viewHeightPx = view.height,
            marginPx = margin,
            isRtl = false,
            velocity = velocity,
            attenuation = settings.pillSnapAttenuation,
            stiffness = snapStiffness,
        )

        val targetX = targetPoint.x.toFloat()
        val targetY = targetPoint.y.toFloat()
        // If already within 1px on both axes or animators are disabled, settle immediately
        val isAtTarget = kotlin.math.abs(dragOffsetX - targetX) < 1f &&
            kotlin.math.abs(dragOffsetY - targetY) < 1f
        if (isAtTarget || !ValueAnimator.areAnimatorsEnabled()) {
            dragOffsetX = targetX
            dragOffsetY = targetY
            applyWindowPosition()
            persistPillPosition()
            stopSnap()
            return
        }

        snapStartX = dragOffsetX
        snapStartY = dragOffsetY
        snapTargetX = targetX
        snapTargetY = targetY
        snapProgressX = 0f
        snapVelocityX = 0f
        if (snapTargetY != snapStartY) {
            snapProgressY = 0f
            snapVelocityY = 0f
        } else {
            snapProgressY = 1f
            snapVelocityY = 0f
        }
        snapRunning = true
        snapLastNanos = System.nanoTime()
        view.postOnAnimation(snapTick)
        Log.i(
            TAG,
            "Pill snap started: (%.0f, %.0f) -> (%.0f, %.0f) v=(%.0f, %.0f)".format(
                snapStartX, snapStartY, snapTargetX, snapTargetY, velocity.x, velocity.y
            ),
        )
    }

    private fun stopSnap() {
        snapRunning = false
        snapLastNanos = 0L
        snapProgressX = 0f
        snapVelocityX = 0f
        snapProgressY = 0f
        snapVelocityY = 0f
    }

    private fun onEdgeAnchoringChanged() {
        composeView?.post {
            if (settings.floatingPillEdgeAnchoring && !isDragging && flingLastNanos == 0L && !entryRunning && !cardWindowShown) {
                startSnap()
            }
        }
    }

    /**
     * Clamps the pending position to the screen and writes it to the window if it changed.
     *
     * This is deliberately **non-destructive**: the drag offsets stay the user's *intended* position
     * and the clamp only decides where the window actually sits for the current view size. Writing
     * the clamped value back used to ratchet the pill downward every time a taller layout (the `···`
     * panel) appeared — it never came back. Keeping the intent means the pill returns to its spot
     * when the taller layout goes away.
     *
     * [clampVertical] is false for layout-driven re-clamps, so a panel animating its height cannot
     * move the pill; the horizontal clamp still runs, because that is what the width change needs.
     */
    private fun applyWindowPosition(clampVertical: Boolean = true) {
        val params = layoutParams ?: return
        val view = composeView ?: return
        val clamped = clampToScreen(view)
        val point = if (clampVertical) clamped else PillPoint(clamped.x, params.y)
        writeWindowPosition(point)
    }

    /** Writes the window pixels without touching the fractional offsets — used by the fling. */
    private fun pushWindowPosition() {
        val view = composeView ?: return
        writeWindowPosition(clampToScreen(view))
    }

    private fun writeWindowPosition(point: PillPoint) {
        val params = layoutParams ?: return
        val view = composeView ?: return
        if (params.x == point.x && params.y == point.y) return
        params.x = point.x
        params.y = point.y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout", e)
        }
    }

    private fun desiredPoint(): PillPoint = PillPoint(dragOffsetX.toInt(), dragOffsetY.toInt())

    private fun clampToScreen(view: View): PillPoint {
        val margin = (PillDock.DRAG_MARGIN_DP * resources.displayMetrics.density).toInt()
        val screen = windowManager.currentWindowMetrics.bounds
        return PillDock.clamp(
            point = desiredPoint(),
            screenWidthPx = screen.width(),
            screenHeightPx = screen.height(),
            viewWidthPx = view.width,
            viewHeightPx = view.height,
            marginPx = margin,
        )
    }

    /** The largest offsets that still keep the whole view on screen. */
    private fun maxOffsets(): Pair<Int, Int> {
        val view = composeView ?: return 0 to 0
        val margin = (PillDock.DRAG_MARGIN_DP * resources.displayMetrics.density).toInt()
        val screen = windowManager.currentWindowMetrics.bounds
        return (screen.width() - view.width - margin).coerceAtLeast(0) to
            (screen.height() - view.height - margin).coerceAtLeast(0)
    }

    private fun persistPillPosition() {
        val params = layoutParams ?: return
        settings.floatingPillX = params.x
        settings.floatingPillY = params.y
    }

    private fun resolveDarkTheme(): Boolean = when (settings.themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun resolveColorScheme(): ColorScheme =
        DynamicThemeEngine.resolveColorScheme(this, settings, resolveDarkTheme())

    private fun refreshProfiles() {
        profiles = profileStore.allProfiles()
        activeProfileId = profileStore.activeProfile().id
    }

    /** Switches the active profile from the pill's settings view, without opening the app. */
    private fun selectProfile(id: String) {
        profileStore.setActiveProfile(id)
        refreshProfiles()
        Log.i(TAG, "Active profile switched to $id")
    }

    /** Toggles AI cleanup from the pill's settings view; the observer refreshes the pill's state. */
    private fun toggleCleanup() {
        settings.cleanupEnabled = !settings.cleanupEnabled
        Log.i(TAG, "Cleanup toggled from the pill to ${settings.cleanupEnabled}")
    }

    fun onKeyboardOrFocusChanged(keyboardOpen: Boolean, editableFocus: Boolean) {
        val wasOpen = isKeyboardOpen
        isKeyboardOpen = keyboardOpen
        hasEditableFocus = editableFocus

        // If the keyboard just closed and the pill is set to only show when a keyboard is open,
        // auto-cancel any active session and return to resting so the pill hides. This cancel is
        // itself keyboard-driven, so it asks for the departure animation: without the flag the pill
        // would be hidden here, *before* the `updateOverlayVisibility` below, and the commonest
        // transition in the app (keyboard closed with a card open) would never animate.
        if (wasOpen && !keyboardOpen && settings.floatingPillOnlyWhenKeyboardOpen) {
            if (uiState != FloatingUiState.RESTING_PILL) {
                Log.i(TAG, "Keyboard closed — dismissing active pill session")
                cancelSession(animated = true)
            }
        }

        // The keyboard-close unload is a grace, not an immediate release: typing is bursty, and the
        // keyboard often closes and reopens between two fields. Reopening (or any input focus)
        // cancels the pending unload.
        if (keyboardOpen) {
            session.stopKeyboardCloseUnload()
        } else if (wasOpen) {
            session.scheduleKeyboardCloseUnload()
        }

        // The keyboard boundary is the one transition a user watches, so it is the only caller that
        // asks for the arrival animation; everything else shows or hides instantly.
        updateOverlayVisibility(animated = true)
    }

    private fun registerScreenOffReceiver() {
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffReceiverRegistered = true
    }

    // The critical-running level (and `onLowMemory`) still arrive on minSdk 30–33, but the platform
    // stopped delivering them to apps in API 34. They are kept because the repo supports API 30+
    // and because a trim sent from a shell (`am send-trim-memory`) exercises the same path.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the critical level: reacting to the early "running low" signals would make dictation
        // latency depend on whatever else is running (D-045).
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.i(TAG, "Critical memory pressure — releasing engines if idle")
            session.unloadEngines("critical memory pressure")
        }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        Log.i(TAG, "Low memory — releasing engines if idle")
        session.unloadEngines("low memory")
    }

    /**
     * **The one place the overlay view's visibility is decided and written.** Every path that shows
     * or hides the pill comes through here, so no visibility write can happen without the decision
     * being made in one place — the transition (and whether it may be animated) is
     * [PillEnter.transitionFor]'s, including the four gates on `animated`.
     *
     * [animated] is the caller's *request* only: the keyboard signal is the one transition a user
     * watches, so [onKeyboardOrFocusChanged] is the only caller that passes `true` directly — and a
     * keyboard-driven session cancel threads the same `true` in through [cancelSession] so a card
     * that is open when the keyboard closes still animates out. A service start, a settings change,
     * a display change and a session cancel that is *not* keyboard-driven keep today's instant
     * behaviour.
     */
    private fun updateOverlayVisibility(animated: Boolean = false) {
        val view = composeView ?: return
        val shouldShow = when {
            !settings.floatingPillEnabled -> false
            // The card is its own window and owns the screen while it is up; the pill window is
            // hidden underneath it (and revealed at the start of the collapse, before the card
            // shrinks, so the handoff never shows an empty container).
            cardHoldsScreen -> false
            uiState != FloatingUiState.RESTING_PILL -> true // keep visible while recording or reviewing
            settings.floatingPillOnlyWhenKeyboardOpen -> isKeyboardOpen
            else -> true
        }
        val params = layoutParams
        val rest = PillOffset(params?.x?.toFloat() ?: 0f, params?.y?.toFloat() ?: 0f)
        // The arrival's start comes from the pill's own horizontal centre against the display's
        // midpoint; `view.width == 0` means the view has not been measured yet, and an animation with
        // no start point is not an animation (PillEnter.transitionFor forces the instant path).
        val displayWidthPx = windowManager.currentWindowMetrics.bounds.width()
        val start = if (params != null && view.width > 0 && view.height > 0) {
            PillEnter.startOffset(
                edge = PillEnter.edgeFor(
                    pillCentreX = PillEnter.pillCentreX(
                        displayWidthPx = displayWidthPx,
                        offsetXPx = params.x,
                        viewWidthPx = view.width,
                        isRtl = false,
                    ),
                    displayWidthPx = displayWidthPx,
                    isRtl = false,
                ),
                viewWidthPx = view.width,
                displayWidthPx = displayWidthPx,
                rest = rest,
            )
        } else {
            null
        }
        val transition = PillEnter.transitionFor(
            // `isVisible`, not `visibility == View.VISIBLE`: the KTX property is the same test and
            // lint's own suggestion (`UseKtx`), so the file stays at its warning count.
            visibleNow = view.isVisible,
            shouldShow = shouldShow,
            // A motion in flight is part of the decision, not a detail after it: the view is VISIBLE
            // from the arrival's first frame, so without this a redundant event mid-motion is
            // indistinguishable from an idle call and the None branch below would abort the motion.
            motionTarget = if (entryRunning) entryTarget else null,
            start = start,
            rest = rest,
            requestedAnimated = animated,
            keyboardDrivenVisibility = settings.floatingPillOnlyWhenKeyboardOpen,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        )
        when (transition) {
            // Nothing to do — and with the motion in flight part of the decision, that is the whole
            // of it: either none is running, or the one that is is already heading for the state this
            // decision asks for (an arrival told to stay visible). Aborting that motion here is what
            // used to cut an arrival short on a redundant accessibility event and snap a reversing
            // departure back to its dock, so the abort stays where it belongs — on the two instant
            // paths below, each of which has decided to end the motion. With nothing in flight this is
            // the no-op it has always been: the pair is null, so there is no write.
            PillTransition.None -> if (!entryRunning) cancelEntry(restore = true)
            is PillTransition.Enter -> if (transition.animated) {
                startMotion(target = 1f, start = transition.start, rest = transition.rest, view = view)
            } else {
                cancelEntry(restore = true)
                view.visibility = View.VISIBLE
            }
            is PillTransition.Leave -> if (transition.animated) {
                // The mirror of the arrival: the same pair, the same tick, the other target. No
                // second geometry and no velocity reset, so a keyboard that closes and reopens
                // between two fields reverses mid-flight instead of restarting.
                startMotion(target = 0f, start = transition.start, rest = transition.rest, view = view)
            } else {
                // The instant departure, in the same pinned order as the animated one's end state:
                // hide first, *then* put the window back at rest. A params write while the pill is
                // still visible would draw it back at its dock for a frame.
                view.visibility = View.GONE
                cancelEntry(restore = true)
            }
        }
    }

    /**
     * Abandons a running motion: the tick stops and the entry state is dropped.
     *
     * With [restore] the window is first put back at the rest position the motion started from —
     * which is what every *instant* path needs, because the slide leaves `params` parked mid-flight
     * and the invariant everywhere else in the service is "hidden or resting ⇒ `params` is at the
     * dock". The drag passes `false`: it wants no write at all, since its own base is the current
     * `params.x/y` (entry offset included).
     *
     * `entryStart`/`entryRest` are cleared here and not when a motion merely *ends*, so a reversal
     * mid-flight keeps the pair it was launched with.
     */
    private fun cancelEntry(restore: Boolean) {
        val rest = entryRest
        if (entryRunning) {
            // A motion cut short is otherwise indistinguishable from one that never started — and
            // "a redundant keyboard signal killed it" is the failure this trace exists to name.
            val direction = if (entryTarget == 0f) "Exit" else "Entry"
            Log.i(TAG, "$direction abandoned after ${entryElapsedMillis()}ms (restore=$restore)")
        }
        // The guard is released before the restore write, so the relayout that write causes goes
        // through the normal clamp path instead of being suppressed as mid-flight.
        entryRunning = false
        if (restore && rest != null) {
            writeWindowPosition(PillPoint(rest.x.toInt(), rest.y.toInt()))
        }
        dropEntryState()
    }

    /**
     * The arrival's end state: land exactly on the dock, then let the suspended clamps run once. The
     * `entryStart`/`entryRest` pair is *kept* here — unlike the departure's — because a reversal
     * drives it back.
     */
    private fun finishEntry(rest: PillOffset) {
        entryRunning = false
        writeWindowPosition(PillPoint(rest.x.toInt(), rest.y.toInt()))
        applyWindowPosition()
        Log.i(TAG, "Entry complete in ${entryElapsedMillis()}ms")
    }

    /**
     * The departure's end state, in one pinned order: **hide**, then put the window back at its rest
     * position, then drop the entry state. A params write before `GONE` draws the pill back at its
     * dock for a frame, and the state drop *after* the write is what leaves "hidden ⇒ `params` at
     * rest" — the invariant the next arrival's `rest` target is read from.
     */
    private fun finishExit(rest: PillOffset) {
        composeView?.visibility = View.GONE
        writeWindowPosition(PillPoint(rest.x.toInt(), rest.y.toInt()))
        dropEntryState()
        Log.i(TAG, "Exit complete in ${entryElapsedMillis()}ms")
    }

    /** Stops the tick and drops the entry state, leaving the fraction at rest. */
    private fun dropEntryState() {
        entryRunning = false
        entryStart = null
        entryRest = null
        entryValue = 1f
        entryVelocity = 0f
        entryTarget = 1f
    }

    /** Milliseconds since the current motion started, for the completion and abandon traces. */
    private fun entryElapsedMillis(): Long = (System.nanoTime() - entryStartedAtNanos) / 1_000_000

    /**
     * Starts a motion, or reverses a running one — in the one order that neither flashes nor stutters.
     *
     * **Fresh** (nothing running): the pair is adopted and the fraction starts at the end the pill is
     * at — `0f` for an arrival (written while the view is still `GONE`, so showing it at the dock for
     * a frame is impossible) and `1f` for a departure (where the pill already sits, so no position is
     * written at all). An arrival additionally reads its off-screen start back, because a window
     * manager is not obliged to keep a position beyond the display; if it was rewritten the arrival
     * uses the platform's value for this transition.
     *
     * **Reversal** (a motion is running and [target] flips — the keyboard closing and reopening
     * between two fields): the fraction and the velocity carry over and only [entryTarget] changes,
     * so the pill continues from where it is and how fast it is going instead of restarting from the
     * other end. The `entryStart`/`entryRest` pair captured at the first arrival is reused, never
     * recomputed: a reversal that recomputed the geometry would jump.
     *
     * A motion already heading for [target] is left alone. The keyboard signal fires more than once
     * per transition (a keyboard-close session cancel reaches visibility through [cancelSession] and
     * then the `onKeyboardOrFocusChanged` tail calls it again), and restarting on the second event
     * would reset the velocity and double the duration.
     */
    private fun startMotion(target: Float, start: PillOffset, rest: PillOffset, view: View) {
        if (entryRunning && entryTarget == target) return
        if (!entryRunning) {
            entryStart = start
            entryRest = rest
            if (target == 1f) {
                entryValue = 0f
                entryVelocity = 0f
                val startPoint = PillPoint(start.x.toInt(), start.y.toInt())
                writeWindowPosition(startPoint)

                // That start is *outside* the display (PillEnter.startOffset), so read it back: if
                // this build rewrote it — a window manager is not obliged to keep an off-screen
                // position — the arrival uses the platform's value for this transition. Degraded,
                // not broken: the pill then starts at the edge instead of outside it. Only a device
                // can say which branch runs.
                val params = layoutParams
                if (params != null && params.x != startPoint.x) {
                    Log.i(
                        TAG,
                        "Entry start clamped: platform rewrote x from ${startPoint.x} to ${params.x} px",
                    )
                    entryStart = start.copy(x = params.x.toFloat())
                }

                view.visibility = View.VISIBLE
            } else {
                // A departure begins where the pill is: at its rest offset, which is the pair's `1f`.
                entryValue = 1f
                entryVelocity = 0f
            }
        }
        entryTarget = target
        entryLastNanos = System.nanoTime()
        entryStartedAtNanos = entryLastNanos
        entryRunning = true
        view.postOnAnimation(entryTick)
    }

    // --- Dictation Capture & ASR ---

    private fun toggleRecording() {
        if (uiState == FloatingUiState.RECORDING || session.isRecording) {
            stopRecording()
        } else {
            startOrResumeRecording(fresh = false)
        }
    }

    /** The card path: tap the pill (or "Speak again") and review before inserting. */
    private fun startOrResumeRecording(fresh: Boolean) {
        beginCapture(targetState = FloatingUiState.RECORDING, fresh = fresh, quick = false)
    }

    /**
     * The hold-to-talk path: the finger was held still past the threshold, so capture starts now
     * and the release ends it. Deliberately the **same** [beginCapture] as the card — only the
     * target state and the quick flag differ.
     */
    private fun startQuickCapture() {
        if (uiState != FloatingUiState.RESTING_PILL) return
        if (!settings.quickDictateOnHold) return
        beginCapture(targetState = FloatingUiState.QUICK_CAPTURE, fresh = true, quick = true)
    }

    /**
     * Everything the card and quick paths share about *starting* an utterance: permission, the
     * capture arbiter, the recorder, the ASR session and the model load. Keeping this in one place is
     * what makes "quick capture uses the same code as the regular pill" true rather than aspirational.
     */
    private fun beginCapture(targetState: FloatingUiState, fresh: Boolean, quick: Boolean) {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            Toast.makeText(this, "Microphone permission required for Sovereign", Toast.LENGTH_SHORT).show()
            return
        }

        if (session.isRecording) return

        // One capture at a time, process-wide (D-106): the desktop server claims the same lease. A
        // refusal is surfaced, never silent. A resume from REVIEW already holds the pill's lease, so
        // it must not re-acquire (that would refuse itself).
        if (pillLease == null) {
            when (val result = session.arbiter.acquire(SessionArbiter.Owner.PILL)) {
                is SessionArbiter.Result.Refused -> {
                    val note = if (result.holder == SessionArbiter.Owner.SERVER) {
                        "Desktop session active"
                    } else {
                        "Dictation already in progress"
                    }
                    Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
                    return
                }
                is SessionArbiter.Result.Granted -> pillLease = result.lease
            }
        }

        quickCaptureActive = quick
        recordStartedAtMs = SystemClock.elapsedRealtime()
        // The card path is the one transition that expands the window; capture the minimized state
        // first so the growth is started only on the pill -> card edge, never on a resume from REVIEW.
        val wasMinimized = uiState == FloatingUiState.RESTING_PILL ||
            uiState == FloatingUiState.QUICK_CAPTURE
        uiState = targetState
        if (targetState == FloatingUiState.RECORDING && wasMinimized) {
            // The card is its own window; it grows inside it while this window's pill is hidden.
            openCard()
        } else {
            uiState = targetState
        }
        audioLevel = 0f
        statusNote = null
        errorMessage = null
        settingsOpen = false
        dismissWordSelection()

        if (fresh) {
            previewText = ""
            rawText = ""
            provisionalText = ""
        }
        if (quick) {
            quickStatus = null
            quickStatusIsError = false
        }
        reprocessActiveLlmMs = null
        session.begin(fresh = fresh, cleanupEnabled = settings.cleanupEnabled)
        session.beginSession()

        try {
            session.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cancelSession()
            return
        }

        refreshKeepScreenOn()
        if (quick) performHaptic()
        startAudioMetering()
        startSilenceMonitoring()

        scope.launch {
            val availability = session.open(settings.language)
            if (availability !is LocalAsrEngine.Availability.Loaded) {
                val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason ?: "model not ready"
                statusNote = "ASR unavailable: $reason"
            }
        }
    }

    /**
     * Shows the card window and grows the card out of the pill (see
     * `docs/work/archive/card-expand-animation/`).
     *
     * The card lives in its **own, always-card-sized window**, so nothing resizes during the
     * motion: the window is placed at the card's clamped dock and made visible, the pill window is
     * hidden underneath, and the card's `graphicsLayer` scale is driven from the pill's rect to
     * `1f`. The pill's own geometry is never touched, which is what makes the close trivially
     * symmetric.
     */
    private fun openCard() {
        stopSnap()
        val card = cardView
        val params = cardParams
        val pillView = composeView
        val pillParams = layoutParams
        if (card == null || params == null || pillView == null || pillParams == null) {
            // No second window: fall back to showing the card state on the pill window's state.
            uiState = FloatingUiState.RECORDING
            return
        }
        val density = resources.displayMetrics.density
        val screen = windowManager.currentWindowMetrics.bounds
        val margin = (PillDock.DRAG_MARGIN_DP * density).toInt()
        val inset = OVERLAY_INSET.value * density
        val (cardWindowWidth, cardWindowHeight) = cardWindowSizePx(density)

        val cardPoint = PillDock.clamp(
            point = desiredPoint(),
            screenWidthPx = screen.width(),
            screenHeightPx = screen.height(),
            viewWidthPx = cardWindowWidth,
            viewHeightPx = cardWindowHeight,
            marginPx = margin,
        )
        val cardSurface = windowRect(cardPoint, cardWindowWidth, cardWindowHeight, screen).inset(inset)
        val measured = pillView.width > 0 && pillView.height > 0
        val pillOffset = PillPoint(pillParams.x, pillParams.y)
        val pillSurface = if (measured) {
            windowRect(pillOffset, pillView.width, pillView.height, screen).inset(inset)
        } else {
            // Unmeasured: a zero-size pill at the card's end-bottom corner -> scale 0, origin (1,1).
            PillRect(cardSurface.right, cardSurface.bottom, cardSurface.right, cardSurface.bottom)
        }
        val edge = PillEnter.edgeFor(
            pillCentreX = ((pillSurface.left + pillSurface.right) / 2f).roundToInt(),
            displayWidthPx = screen.width(),
            isRtl = false,
        )
        cardGrowth = PillExpand.growthFor(cardSurface, pillSurface, edge)
        cardWindowOffset = cardPoint
        Log.i(
            TAG,
            "Card open: card=${cardPoint.x},${cardPoint.y} pill=${pillOffset.x},${pillOffset.y} " +
                "scale=${cardGrowth.startScaleX},${cardGrowth.startScaleY} origin=${cardGrowth.originX},${cardGrowth.originY}",
        )

        // The card window is already the card's size; only its position and visibility change.
        params.x = cardPoint.x
        params.y = cardPoint.y
        try {
            windowManager.updateViewLayout(card, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error positioning the card window", e)
        }
        // At the pill's size before the first card frame, so it cannot flash at full size. The pill
        // window is hidden on the *next* frame (once the card is composed), so the pill can never
        // blink out before the card is drawn.
        expandProgress = 0f
        cardClosing = false
        card.visibility = View.VISIBLE
        cardWindowShown = true

        // Start on the next frame, once the card's content is measured inside its window.
        card.post { startCardOpen() }
    }

    /** Starts the growth, once the card window is visible and measured. */
    private fun startCardOpen() {
        if (!cardWindowShown) return
        Log.i(TAG, "Card grow start")
        // The card is on screen now: hide the pill window that sat underneath it.
        cardHoldsScreen = true
        updateOverlayVisibility()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            stopCardExpand()
            return
        }
        expandVelocity = 0f
        expandTarget = 1f
        expandLastNanos = System.nanoTime()
        expandStartedAtNanos = expandLastNanos
        expandRunning = true
        cardView?.postOnAnimation(expandTick)
    }

    /** Ends the card-expand motion and leaves the card at full scale. */
    private fun stopCardExpand() {
        expandRunning = false
        expandProgress = 1f
        expandVelocity = 0f
        collapseAfter = null
    }

    /**
     * Starts the card -> pill morph: the same transform run backwards inside the still-visible card
     * window. The pill window is revealed first (it sits under the card), so the card shrinks and
     * fades *onto the real pill* rather than onto an empty container. [collapseAfter] runs when it
     * lands; there is no window write at any point.
     */
    private fun startCardCollapse(animated: Boolean) {
        cardHoldsScreen = false
        cardClosing = true
        // Reveal the pill underneath the card before the shrink, so the handoff is already in place.
        updateOverlayVisibility()
        collapseAfter = { finishResting(animated) }
        expandTarget = 0f
        expandVelocity = 0f
        expandLastNanos = System.nanoTime()
        expandStartedAtNanos = expandLastNanos
        expandRunning = true
        cardView?.postOnAnimation(expandTick)
    }

    /** A window's screen rect for an offset and size in the bottom-end gravity's coordinates. */
    private fun windowRect(offset: PillPoint, width: Int, height: Int, screen: Rect): PillRect {
        val right = (screen.width() - offset.x).toFloat()
        val bottom = (screen.height() - offset.y).toFloat()
        return PillRect(left = right - width, top = bottom - height, right = right, bottom = bottom)
    }

    /**
     * The status shown while a capture is finalised (recorder stop + ASR tail flush + cleanup drain).
     * "Polishing" only when an LLM pass will actually run; with cleanup off the same work happens but
     * there is no AI in it, and claiming otherwise makes an ordinary stop look like a stray step.
     */
    private fun finishingStatus(): String =
        if (settings.cleanupEnabled) "Polishing with AI..." else "Finishing..."

    private fun stopRecording() {
        if (!session.isRecording) return
        captureStoppedAtMs = SystemClock.elapsedRealtime()
        finishRecordingAccounting()
        stopAudioMetering()
        stopSilenceMonitoring()

        uiState = FloatingUiState.REVIEW
        isProcessing = true
        statusNote = finishingStatus()

        inFlightJob = scope.launch {
            finalizeUtterance()
            turnaroundLatencyMs = if (captureStoppedAtMs > 0L) SystemClock.elapsedRealtime() - captureStoppedAtMs else 0L
            isProcessing = false
            statusNote = null
            session.onCaptureFinished()

            if (settings.floatingPillAutoCommit) {
                commitSession()
            }
        }
    }

    /**
     * The release that ends a quick capture. Mirrors [stopRecording]'s finalize, but skips REVIEW:
     * the text is injected and the pill shows a brief status before returning to rest.
     */
    private fun releaseQuickCapture() {
        if (!quickCaptureActive) return
        quickCaptureActive = false
        if (!session.isRecording) return
        captureStoppedAtMs = SystemClock.elapsedRealtime()
        finishRecordingAccounting()
        stopAudioMetering()
        stopSilenceMonitoring()

        isProcessing = true
        inFlightJob = scope.launch {
            finalizeUtterance()
            turnaroundLatencyMs = if (captureStoppedAtMs > 0L) SystemClock.elapsedRealtime() - captureStoppedAtMs else 0L
            isProcessing = false
            session.onCaptureFinished()
            deliverQuickCapture()
        }
    }

    /**
     * Stops the recorder and runs the utterance to settled text. Shared by the card and quick
     * paths so a change to the session cannot apply to only one of them.
     */
    private suspend fun finalizeUtterance() {
        // The session brackets recorder stop + drain as "processing" so no unload trigger can release
        // the engines inside that window; the keep-screen hold is released as the mic shuts.
        session.finish(onRecorderStopped = ::refreshKeepScreenOn)
    }

    /** Folds the running capture into [recordedMillis]; the counterpart to `recordStartedAtMs`. */
    private fun finishRecordingAccounting() {
        recordStartedAtMs.let { started ->
            if (started > 0L) {
                recordedMillis += SystemClock.elapsedRealtime() - started
                recordStartedAtMs = 0L
            }
        }
    }

    /** Injects the finalized utterance and shows the outcome on the minimized pill. */
    private suspend fun deliverQuickCapture() {
        val latency = if (turnaroundLatencyMs > 0L) turnaroundLatencyMs else (if (captureStoppedAtMs > 0L) SystemClock.elapsedRealtime() - captureStoppedAtMs else 0L)
        captureStoppedAtMs = 0L
        turnaroundLatencyMs = 0L
        val textToInject = session.deliveryText.trim()
        if (textToInject.isBlank()) {
            showQuickResult("Nothing heard")
        } else {
            val toType = UtteranceFinalizer.forInsertion(textToInject)
            val injected = VoiceAccessibilityService.instance?.injectText(toType) ?: false
            recordUsageStats(deliveredText = textToInject, latencyMs = latency)
            recordHistory(deliveredText = textToInject, insertedSuccessfully = injected)
            if (injected) {
                // A cleanup failure still injects the raw words; say so rather than claim a polish.
                showQuickResult(if (errorMessage != null) "Inserted as spoken" else "Inserted")
            } else {
                // Never fail silently: the words are on the clipboard, so tell the user how to use them.
                Log.w(TAG, "Text injected via clipboard fallback (quick capture)")
                Toast.makeText(this, "Copied — long-press the text field to paste", Toast.LENGTH_LONG).show()
                showQuickResult("Copied")
            }
        }
        performHaptic()

        // Result holds ~1.7s then returns to rest; Error holds until the next attempt.
        if (!quickStatusIsError) {
            delay(QUICK_STATUS_MS)
            if (uiState == FloatingUiState.QUICK_CAPTURE && !isProcessing) {
                resetToResting()
            }
        }
    }

    private fun showQuickResult(text: String, isError: Boolean = false) {
        quickStatus = text
        quickStatusIsError = isError
    }

    /** A short haptic so the arm and the release land without the user watching the pill. */
    private fun performHaptic() {
        composeView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** The auto-stops fire the right ending for whichever capture is running. */
    private fun stopCapture() {
        if (quickCaptureActive) releaseQuickCapture() else stopRecording()
    }

    private fun cancelSession(animated: Boolean = false) {
        // ✕ — the explicit discard. Always throws the session away. The keyboard-close path passes
        // `true` so a card that is open when the keyboard closes slides out with the pill; the ✕ and
        // the outside tap keep the default and stay instant.
        abandonCapture()
        resetToResting(animated)
    }

    /**
     * The pill's "minimise": an outside tap collapses the expanded card back to resting.
     *
     * Currently this behaves exactly like [cancelSession] — the user accepted that an outside tap
     * loses the text for now — but it is deliberately its own call so the parked session-preservation
     * work has **one** seam to change: keep the text here instead of going through
     * [resetToResting]'s discard path.
     */
    private fun collapseSession() {
        abandonCapture()
        resetToResting()
    }

    /**
     * A touch landed outside the pill's window. Close the `···` panel and minimise the card if it is
     * open; do nothing when the pill is already resting. The app underneath has already received the
     * same touch — `ACTION_OUTSIDE` is a copy, not a capture.
     */
    private fun onOutsideTouch() {
        scope.launch(Dispatchers.Main.immediate) {
            settingsOpen = false
            // A tap outside while a word is being fixed only closes the fix (and the keyboard it
            // raised); it must not throw the whole session away.
            if (wordSelection != null || wordForm != null) {
                dismissWordSelection()
                return@launch
            }
            if (uiState != FloatingUiState.RESTING_PILL) {
                Log.i(TAG, "Outside tap — minimising the pill")
                collapseSession()
            }
        }
    }

    private fun commitSession() {
        scope.launch(Dispatchers.Main.immediate) {
            if (session.isRecording) {
                captureStoppedAtMs = SystemClock.elapsedRealtime()
                finishRecordingAccounting()
                stopAudioMetering()
                stopSilenceMonitoring()
                isProcessing = true
                statusNote = finishingStatus()
                finalizeUtterance()
                turnaroundLatencyMs = if (captureStoppedAtMs > 0L) SystemClock.elapsedRealtime() - captureStoppedAtMs else 0L
                isProcessing = false
                statusNote = null
                session.onCaptureFinished()
            } else if (isProcessing) {
                inFlightJob?.join()
            }

            val latency = if (turnaroundLatencyMs > 0L) turnaroundLatencyMs else (if (captureStoppedAtMs > 0L) SystemClock.elapsedRealtime() - captureStoppedAtMs else 0L)
            captureStoppedAtMs = 0L
            turnaroundLatencyMs = 0L
            val textToInject = session.deliveryText.trim()
            var injected = false
            if (textToInject.isNotBlank()) {
                // A trailing space separates this session from the next: the pill has no memory of where
                // the cursor sits between sessions, and re-dictating must not glue the new segment onto
                // this one. It comes after terminal punctuation too ("Done. ").
                val toType = UtteranceFinalizer.forInsertion(textToInject)
                injected = VoiceAccessibilityService.instance?.injectText(toType) ?: false
                if (!injected) {
                    // Never fail silently: the words are on the clipboard, so tell the user how to use them.
                    Log.w(TAG, "Text injected via clipboard fallback")
                    Toast.makeText(this@FloatingOverlayService, "Copied — long-press the text field to paste", Toast.LENGTH_LONG).show()
                }
            }
            recordUsageStats(deliveredText = textToInject, latencyMs = latency)
            recordHistory(deliveredText = textToInject, insertedSuccessfully = injected)
            resetToResting()
        }
    }

    /**
     * The pill is the primary surface, so its dictation has to reach the same
     * stats store the IME writes to — otherwise the dashboard silently misses
     * most of what the user actually dictated.
     */
    private fun recordUsageStats(deliveredText: String, latencyMs: Long = 0L) {
        val raw = session.rawText.trim()
        if (raw.isEmpty() && deliveredText.isEmpty()) return

        val asrMs = session.totalActiveAsrMs
        val llmMs = reprocessActiveLlmMs ?: if (session.isCleanupEngaged || settings.cleanupEnabled) session.totalActiveLlmMs else 0L

        val diff = if (raw.isNotEmpty() && (llmMs > 0L || deliveredText != raw)) {
            WordDiffCalculator.calculate(rawText = raw, cleanedText = deliveredText)
        } else {
            WordDiffCalculator.verbatim(raw.ifEmpty { deliveredText })
        }
        val activeComputeMs = asrMs + llmMs

        usageStatsStore.record(
            durationSeconds = currentSessionSeconds(),
            rawWords = diff.rawWords,
            cleanedWords = diff.wordsCleaned,
            engine = Engine.LOCAL.label,
            deliveredWords = diff.deliveredWords,
            wordsRemoved = diff.wordsRemoved,
            wordsChanged = diff.wordsChanged,
            wordsAdded = diff.wordsAdded,
            asrDurationMs = asrMs,
            llmDurationMs = llmMs,
            wallClockMs = activeComputeMs,
            latencyMs = latencyMs,
        )
    }

    private fun recordHistory(deliveredText: String, insertedSuccessfully: Boolean) {
        if (!settings.historyEnabled) return
        val raw = session.rawText.trim()
        val cleaned = deliveredText.trim()
        if (raw.isEmpty() && cleaned.isEmpty()) return

        val llmMs = reprocessActiveLlmMs ?: if (session.isCleanupEngaged || settings.cleanupEnabled) session.totalActiveLlmMs else 0L

        val diff = if (raw.isNotEmpty() && (llmMs > 0L || cleaned != raw)) {
            WordDiffCalculator.calculate(rawText = raw, cleanedText = cleaned)
        } else {
            WordDiffCalculator.verbatim(raw.ifEmpty { cleaned })
        }

        historyStore.record(
            record = DictationRecord(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                durationSeconds = currentSessionSeconds(),
                rawText = raw.ifEmpty { cleaned },
                cleanedText = cleaned.ifEmpty { raw },
                asrModel = settings.asrModel.label,
                llmModel = if (session.isCleanupEngaged || llmMs > 0L) settings.litertModel.label else null,
                wordsCleaned = diff.wordsCleaned,
                insertedSuccessfully = insertedSuccessfully,
            ),
            maxCapacity = settings.historyCapacity,
        )
    }

    /** Recorded time so far, including a still-running capture. */
    private fun currentSessionSeconds(): Double {
        val live = if (recordStartedAtMs > 0L) {
            SystemClock.elapsedRealtime() - recordStartedAtMs
        } else {
            0L
        }
        return (recordedMillis + live) / 1000.0
    }

    /**
     * User-requested feature: Reprocesses the ENTIRE transcript from scratch with the LLM.
     */
    private fun reprocessFullText() {
        val textToRewrite = session.rawText.trim()
        if (textToRewrite.isBlank() || isProcessing) return
        // The transcript is about to be rewritten wholesale, so any selected word's range is stale.
        dismissWordSelection()

        isProcessing = true
        statusNote = "Reprocessing entire text with LLM..."

        inFlightJob = scope.launch {
            val pass = session.reprocess(textToRewrite, profileStore.activeProfile().rules)
            isProcessing = false
            if (pass == null) {
                // Reviews are non-destructive: the text on screen is already safe, so this is an
                // advisory note rather than the raw-text fallback treatment.
                statusNote = "Couldn't reload the cleanup model"
                return@launch
            }
            if (pass.cleaned) {
                val costMs = (pass.promptTimeMs + pass.decodeTimeMs).toInt()
                reprocessActiveLlmMs = if (pass.activeMs > 0L) pass.activeMs else costMs.toLong()
                session.replaceCommitted(pass.text)
                statusNote = "Reprocessed full text (${costMs}ms)"
            } else {
                statusNote = "Reprocess failed: ${pass.note.ifBlank { "unknown" }}"
            }
        }
    }

    private fun resetToResting(animated: Boolean = false) {
        // The card's close is its own morph: keep the card window, shrink and fade the card onto the
        // pill (already revealed), and only then hide the card window. Resetting the state first is
        // what made the card vanish and the pill then appear at the card's corner and fly.
        if (cardWindowShown && ValueAnimator.areAnimatorsEnabled() &&
            (uiState == FloatingUiState.RECORDING || uiState == FloatingUiState.REVIEW)
        ) {
            if (collapseAfter == null) startCardCollapse(animated)
            return
        }
        finishResting(animated)
    }

    /**
     * The actual return to rest. The card window is a separate fixed-size window, so this is just
     * its visibility flip plus the state reset — no window is resized, and the pill window never
     * moved, so there is no frame in which either surface is the wrong size.
     */
    private fun finishResting(animated: Boolean) {
        expandRunning = false
        collapseAfter = null
        cardClosing = false
        Log.i(TAG, "finishResting: cardShown=$cardWindowShown card=$cardWindowOffset")
        uiState = FloatingUiState.RESTING_PILL
        quickCaptureActive = false
        quickStatus = null
        quickStatusIsError = false
        previewText = ""
        rawText = ""
        provisionalText = ""
        audioLevel = 0f
        statusNote = null
        errorMessage = null
        settingsOpen = false
        dismissWordSelection()
        isProcessing = false
        session.reset()
        recordStartedAtMs = 0L
        recordedMillis = 0L
        captureStoppedAtMs = 0L
        turnaroundLatencyMs = 0L
        // A session that was cancelled (rather than finalised) still ends the capture; the engines
        // are idle from here. Harmless when a finalised capture already armed the same timer.
        session.onCaptureFinished()
        // Resting is always released: this funnel is the backstop, so no path can leave the window
        // holding the screen on for a later, idle appearance (D-050).
        refreshKeepScreenOn()
        // The capture is over, so the process-wide lease goes back and a desktop session may start.
        releasePillLease()

        // Hide the card window and show the pill. The pill was already revealed at the start of the
        // collapse and its window never moved or resized, so this is the whole swap.
        cardHoldsScreen = false
        cardWindowShown = false
        cardView?.visibility = View.GONE
        cardWindowOffset = null
        updateOverlayVisibility(animated)
    }

    private fun abandonCapture() {
        stopAudioMetering()
        stopSilenceMonitoring()
        inFlightJob?.cancel()
        inFlightJob = null
        session.abort()
        refreshKeepScreenOn()
        captureStoppedAtMs = 0L
        turnaroundLatencyMs = 0L
    }

    private fun startAudioMetering() {
        // Metering is updated directly in the session's recorder frame sink.
    }

    private fun stopAudioMetering() {
        audioLevel = 0f
    }

    private fun startSilenceMonitoring() {
        silenceMonitoringJob?.cancel()
        silenceMonitoringJob = scope.launch {
            while (isActive && session.isRecording) {
                delay(100L)
                val state = session.boundaryState
                val stopAfter = settings.stopAfterSilenceSeconds
                // 1. Normal speech pause auto-stop. Skipped during quick capture: the finger is
                //    still down, and lifting it is what ends the utterance (D-quick-dictate).
                if (!quickCaptureActive && stopAfter > 0f && state.heardAudio && state.quietFor >= stopAfter) {
                    Log.i(TAG, "Silence threshold reached (%.1fs >= %.1fs); auto-stopping".format(state.quietFor, stopAfter))
                    stopCapture()
                    break
                }
                // 2. Initial silence timeout (10s without speech after starting). Card path only —
                //    a held finger with no speech is the user's choice, ended by the release.
                if (!quickCaptureActive && session.capturedSeconds >= 10.0 && !state.heardAudio) {
                    Log.i(TAG, "Initial silence timeout (10s) reached without speech")
                    stopCapture()
                    break
                }
                // 3. Max recording duration ceiling (600s / 10min): a runaway guard on both paths.
                if (session.capturedSeconds >= 600.0) {
                    Log.i(TAG, "Max recording duration ceiling reached")
                    stopCapture()
                    break
                }
            }
        }
    }

    private fun stopSilenceMonitoring() {
        silenceMonitoringJob?.cancel()
        silenceMonitoringJob = null
    }

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val CHANNEL_ID = "sovereign_floating_pill"
        private const val NOTIFICATION_ID = 4001

        /** Below this release speed a fling is not worth starting; the pill just settles. */
        private const val FLING_MIN_VELOCITY_PX_PER_SECOND = 120f

        /** How long the minimized pill shows a quick-capture result before returning to rest (~1.7s). */
        private const val QUICK_STATUS_MS = 1700L

        /** The error state's user-facing line. Deliberately says nothing about exceptions. */
        const val CLEANUP_UNAVAILABLE = "Cleanup is unavailable — showing your words as spoken."

        @Volatile
        var instance: FloatingOverlayService? = null
            private set

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot start FloatingOverlayService: RECORD_AUDIO permission not granted")
                return
            }
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }

        fun updateVisibility(context: Context, isKeyboardOpen: Boolean, hasEditableFocus: Boolean) {
            instance?.onKeyboardOrFocusChanged(isKeyboardOpen, hasEditableFocus) ?: run {
                val settings = AppSettings(context)
                if (settings.floatingPillEnabled &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ) {
                    start(context)
                }
            }
        }
    }
}
