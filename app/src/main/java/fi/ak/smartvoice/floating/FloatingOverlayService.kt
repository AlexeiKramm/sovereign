package fi.ak.smartvoice.floating

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import fi.ak.smartvoice.R
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.asr.LocalAsrSession
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.cleanup.CleanupEngine
import fi.ak.smartvoice.cleanup.CleanupPass
import fi.ak.smartvoice.cleanup.CleanupPipeline
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.cleanup.LiteRtLmCleanupEngine
import fi.ak.smartvoice.cleanup.SeamHealer
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.data.DictionaryStore
import fi.ak.smartvoice.data.ProfileStore
import fi.ak.smartvoice.data.UsageStatsStore
import fi.ak.smartvoice.data.WordDiffCalculator
import fi.ak.smartvoice.engine.Engine
import fi.ak.smartvoice.engine.ModelResidency
import fi.ak.smartvoice.text.UtteranceFinalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Foreground service hosting the Wispr Flow-style floating dictation pill.
 * Renders dynamically via WindowManager over any active software keyboard (Gboard, Samsung, SwiftKey).
 */
class FloatingOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val asrDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smartvoice-floating-asr")
    }.asCoroutineDispatcher()

    private val cleanupDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smartvoice-floating-cleanup")
    }.asCoroutineDispatcher()

    private lateinit var windowManager: WindowManager
    private var composeView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private lateinit var settings: AppSettings
    private lateinit var profileStore: ProfileStore
    private lateinit var dictionaryStore: DictionaryStore
    private lateinit var usageStatsStore: UsageStatsStore
    private lateinit var recorder: WavRecorder
    private lateinit var localEngine: LocalAsrEngine
    private lateinit var localSession: LocalAsrSession
    private lateinit var cleanupPipeline: CleanupPipeline
    private var cleanupEngine: CleanupEngine? = null

    // Compose State
    private var uiState by mutableStateOf(FloatingUiState.RESTING_PILL)
    private var previewText by mutableStateOf("")
    private var rawText by mutableStateOf("")
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
    private var profiles by mutableStateOf(emptyList<CleaningProfile>())
    private var activeProfileId by mutableStateOf("")

    /** True between a quick capture's arm and its release; the release is the only normal stop. */
    private var quickCaptureActive = false
    /** The brief result shown on the minimized pill after a quick capture, or null. */
    private var quickStatus by mutableStateOf<String?>(null)
    /** True when [quickStatus] is a failure. */
    private var quickStatusIsError by mutableStateOf(false)

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

    // Residency: the engines are released by four triggers, all funnelled through [unloadEngines].
    /** When the last capture finished (recording and processing both done); 0 before the first. */
    private var lastCaptureEndedAtMs = 0L
    /** The armed idle-timeout unload, or null when none is pending. */
    private var idleUnloadJob: Job? = null
    /** The keyboard-close grace unload, or null once the keyboard is open or the grace has passed. */
    private var keyboardCloseUnloadJob: Job? = null
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

    // Arrival: the pill's entry fraction plus its velocity, stepped per frame by [entryTick] — the
    // same shape as the fling above. `entryValue` is the transition fraction where `1f` is the pill
    // at its rest dock and `0f` is fully off screen (`PillEnter`), so the spring's units are
    // fractions and the same curve serves any travel. While [entryRunning] the tick's writes are the
    // *only* position writes: every other writer clamps to the screen and would drag the pill back
    // on screen mid-slide. `entryStart`/`entryRest` stay non-null after the motion ends on purpose —
    // CHUNK-3 reuses that pair for a reversal, so a flip cannot recompute (and so cannot jump).
    private var entryValue = 1f
    private var entryVelocity = 0f
    private var entryTarget = 1f
    private var entryStart: PillOffset? = null
    private var entryRest: PillOffset? = null
    private var entryLastNanos = 0L
    private var entryStartedAtNanos = 0L
    private var entryRunning = false

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
                persistPillPosition()
                return
            }
            composeView?.postOnAnimation(this)
        }
    }

    /**
     * One frame of the arrival (CHUNK-3 adds the departure on top of the same state).
     *
     * It writes the window **only** through [writeWindowPosition] — never through
     * [applyWindowPosition], which clamps to the screen and would drag the pill back on screen
     * mid-slide. The clamps are suspended while [entryRunning] (the layout listener's guard) and
     * applied once, here, when the motion ends.
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
                entryRunning = false
                // The motion ended: land exactly on the dock, then let the suspended clamps run once.
                writeWindowPosition(PillPoint(rest.x.toInt(), rest.y.toInt()))
                applyWindowPosition()
                val elapsedMs = (System.nanoTime() - entryStartedAtNanos) / 1_000_000
                Log.i(TAG, "Entry complete in ${elapsedMs}ms")
                return
            }
            composeView?.postOnAnimation(this)
        }
    }

    // Accumulators
    private val rawTextAccumulator = StringBuilder()
    private val committedText = StringBuilder()
    private var cleanupEngaged = false
    private var inFlightJob: Job? = null
    private var silenceMonitoringJob: Job? = null

    /** Wall-clock accounting for the usage stats the dashboard shows. */
    private var recordStartedAtMs = 0L
    private var recordedMillis = 0L

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
            Log.i(TAG, "Screen off — releasing engines")
            unloadEngines("screen off")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        profileStore = ProfileStore(this)
        dictionaryStore = DictionaryStore(this)
        usageStatsStore = UsageStatsStore(this)
        recorder = WavRecorder()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForegroundWithNotification()

        darkTheme = resolveDarkTheme()
        // The pill is its own window, so the app changing its theme mode does not reach it by itself.
        settings.observeThemeMode { darkTheme = resolveDarkTheme() }
        cleanupEnabled = settings.cleanupEnabled
        refreshProfiles()
        setupEngines()
        registerScreenOffReceiver()
        setupOverlayView()
        updateOverlayVisibility()
        Log.i(TAG, "FloatingOverlayService initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateOverlayVisibility()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        darkTheme = resolveDarkTheme()
        // A display change cannot be animated into: abandon any arrival, put the window back at its
        // rest position, then re-clamp so the pill cannot be stranded off-screen.
        cancelEntry(restore = true)
        composeView?.post { applyWindowPosition() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        settings.removeThemeModeObserver()
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        stopIdleUnload()
        stopKeyboardCloseUnload()
        abandonCapture()
        removeOverlayView()
        scope.cancel()
        localEngine.unload()
        cleanupEngine?.unload()
        asrModelLoaded = false
        cleanupModelLoaded = false
        asrDispatcher.close()
        cleanupDispatcher.close()
        Log.i(TAG, "FloatingOverlayService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Dictation Pill",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Allows Smart Voice dictation over any keyboard"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Voice")
            .setContentText("Floating Dictation Pill active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMic) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupEngines() {
        localEngine = LocalAsrEngine(
            modelDirectory = settings.asrModel.directory(filesDir),
            modelSpec = settings.asrModel,
            assetManager = assets,
        )

        localSession = LocalAsrSession(
            engine = localEngine,
            dispatcher = asrDispatcher,
            scope = scope,
            onUnit = ::onAsrUnit,
            timings = { settings.listenerTimings() },
        )

        cleanupPipeline = CleanupPipeline(
            engine = CurrentCleanupEngine(),
            scope = scope,
            dispatcher = cleanupDispatcher,
            rules = {
                val active = profileStore.activeProfile()
                CleanupPrompt.Variant.fromId(active.id)
            },
            onCommitted = { commit ->
                scope.launch(Dispatchers.Main.immediate) {
                    if (commit.overlapToDelete.isNotEmpty() && committedText.endsWith(commit.overlapToDelete)) {
                        committedText.setLength(committedText.length - commit.overlapToDelete.length)
                    }
                    if (committedText.isNotEmpty() && !committedText.endsWith(" ") && !commit.text.startsWith(" ")) {
                        committedText.append(" ")
                    }
                    committedText.append(commit.text)
                    previewText = committedText.toString()
                }
            }
        )
    }

    private fun setupOverlayView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; cannot display floating pill")
            return
        }

        val overlayOwner = OverlayLifecycleOwner()
        overlayLifecycleOwner = overlayOwner
        overlayOwner.start()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val start = PillDock.restore(settings.floatingPillX, settings.floatingPillY, density)
        dragOffsetX = start.x.toFloat()
        dragOffsetY = start.y.toFloat()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            overlayFlags(focusable = false),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = start.x
            y = start.y
        }
        layoutParams = params

        val content = ComposeView(this).apply {
            setContent {
                FloatingDictationOverlay(
                    darkTheme = darkTheme,
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    audioLevel = { audioLevel },
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
                        if (uiState == FloatingUiState.RESTING_PILL) startOrResumeRecording(fresh = true)
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
                )
            }
        }

        // The ComposeView is wrapped so the window root can capture the pointer's screen position
        // before Compose consumes the touch (see PillRootView).
        val view = PillRootView(this).apply {
            overlayOwner.attach(this)
            onPointerScreen = { x, y ->
                pointerScreenX = x
                pointerScreenY = y
            }
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            // Never while a drag, a fling or an arrival owns the position: those writes are
            // unclamped on purpose, and this clamp would drag the pill back on screen mid-slide.
            if (!isDragging && flingLastNanos == 0L && !entryRunning) {
                applyWindowPosition(clampVertical = false)
            }
        }

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun removeOverlayView() {
        stopFling()
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
        return if (focusable) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
        val params = layoutParams ?: return
        val view = composeView ?: return
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
        val view = composeView
        val velocity = dragVelocity.velocity()
        if (view == null || velocity.speed < FLING_MIN_VELOCITY_PX_PER_SECOND) {
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
        applyWindowPosition()
        persistPillPosition()
    }

    private fun stopFling() {
        flingVelocityX = 0f
        flingVelocityY = 0f
        flingLastNanos = 0L
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

    /** Toggles AI cleanup from the pill's settings view; it reads the setting at use time. */
    private fun toggleCleanup() {
        settings.cleanupEnabled = !settings.cleanupEnabled
        cleanupEnabled = settings.cleanupEnabled
        Log.i(TAG, "Cleanup ${if (cleanupEnabled) "enabled" else "disabled"} from the pill")
    }

    fun onKeyboardOrFocusChanged(keyboardOpen: Boolean, editableFocus: Boolean) {
        val wasOpen = isKeyboardOpen
        isKeyboardOpen = keyboardOpen
        hasEditableFocus = editableFocus

        // If the keyboard just closed and the pill is set to only show when a keyboard is open,
        // auto-cancel any active session and return to resting so the pill hides.
        if (wasOpen && !keyboardOpen && settings.floatingPillOnlyWhenKeyboardOpen) {
            if (uiState != FloatingUiState.RESTING_PILL) {
                Log.i(TAG, "Keyboard closed — dismissing active pill session")
                cancelSession()
            }
        }

        // The keyboard-close unload is a grace, not an immediate release: typing is bursty, and the
        // keyboard often closes and reopens between two fields. Reopening (or any input focus)
        // cancels the pending unload.
        if (keyboardOpen) {
            stopKeyboardCloseUnload()
        } else if (wasOpen) {
            scheduleKeyboardCloseUnload()
        }

        // The keyboard boundary is the one transition a user watches, so it is the only caller that
        // asks for the arrival animation; everything else shows or hides instantly.
        updateOverlayVisibility(animated = true)
    }

    /**
     * Loads everything the current configuration needs, in the order the latency cares about:
     * **ASR first** (the first frames must be recognised as soon as possible), then the cleanup LLM
     * once ASR has reported loaded.
     *
     * Sequential rather than parallel on purpose: the engines run on separate single-thread
     * dispatchers, and racing two large allocations for CPU and memory bandwidth is exactly how a
     * low-end device spikes into OOM. The LLM is not needed until the first *unit* is emitted (after
     * a pause), so serialising costs almost nothing in perceived latency.
     *
     * Called from [beginCapture] only: nothing loads because a keyboard appeared or the service
     * started. The lazy load in [onAsrUnit] stays as the safety net if this warm-up loses a race.
     */
    private fun warmEngines() {
        scope.launch {
            Log.i(TAG, "Warming engines at capture start")
            val asrAvailable = withContext(asrDispatcher) { localEngine.load() }
            asrModelLoaded = asrAvailable is LocalAsrEngine.Availability.Loaded
            Log.i(TAG, "ASR warm: $asrAvailable in ${localEngine.lastLoadMillis}ms")
            // No ASR means no text, so there is nothing for cleanup to do; skip the allocation.
            if (!asrModelLoaded) return@launch
            if (!settings.cleanupEnabled) return@launch

            val engine = engineForUtterance()
            if (engine.isLoaded) {
                cleanupModelLoaded = true
                Log.i(TAG, "Cleanup model already warm")
                return@launch
            }
            val result = withContext(cleanupDispatcher) { engine.load() }
            cleanupModelLoaded = engine.isLoaded
            if (result.isFailure) {
                Log.w(TAG, "Cleanup model warm-up failed: ${result.exceptionOrNull()?.message}")
            } else {
                Log.i(TAG, "Cleanup model warm in ${engine.lastLoadMs}ms")
            }
        }
    }

    // --- Residency: the unload side (D-045, CHUNK-2) ---------------------------
    //
    // Four triggers — screen off, idle timeout, keyboard close, critical memory pressure — all end
    // here. The decision that matters ("may we release right now?") lives in `ModelResidency` so it
    // is unit-tested rather than argued about on a phone.

    /**
     * Releases both engines, unless a capture is in flight. Every trigger calls this and nothing
     * else unloads, so "never yank a model out from under a running utterance" has one home.
     *
     * Unload is idempotent: releasing an already-cold engine is a no-op, but the early return keeps
     * the log honest about what actually happened.
     */
    private fun unloadEngines(reason: String) {
        stopIdleUnload()
        stopKeyboardCloseUnload()

        if (!ModelResidency.canUnload(recording = recorder.isRecording, processing = isProcessing)) {
            Log.i(TAG, "Unload skipped ($reason): capture in flight")
            return
        }
        if (!asrModelLoaded && !cleanupModelLoaded) {
            Log.i(TAG, "Unload skipped ($reason): nothing is loaded")
            return
        }

        Log.i(TAG, "Unloading engines ($reason)")
        scope.launch {
            // Each engine is released on its own single-thread dispatcher, matching how it loads.
            withContext(asrDispatcher) { localEngine.unload() }
            asrModelLoaded = false
            withContext(cleanupDispatcher) { cleanupEngine?.unload() }
            cleanupModelLoaded = false
            Log.i(TAG, "Engines unloaded ($reason)")
        }
    }

    /** A capture's recording and processing are both done, so the engines start their idle window. */
    private fun onCaptureFinished() {
        lastCaptureEndedAtMs = SystemClock.elapsedRealtime()
        restartIdleUnload()
    }

    /** (Re)arms the idle-timeout unload from "now". `modelIdleTimeoutMinutes == 0` means never. */
    private fun restartIdleUnload() {
        stopIdleUnload()
        val minutes = settings.modelIdleTimeoutMinutes
        if (minutes <= 0) return
        idleUnloadJob = scope.launch {
            delay(minutes.toLong() * ModelResidency.MILLIS_PER_MINUTE)
            if (ModelResidency.idleTimeoutReached(
                    idleMillis = SystemClock.elapsedRealtime() - lastCaptureEndedAtMs,
                    timeoutMinutes = minutes,
                )
            ) {
                unloadEngines("idle ${minutes}m")
            }
        }
    }

    private fun stopIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
    }

    private fun scheduleKeyboardCloseUnload() {
        stopKeyboardCloseUnload()
        if (!settings.unloadOnKeyboardClose) return
        keyboardCloseUnloadJob = scope.launch {
            delay(KEYBOARD_CLOSE_GRACE_MS)
            if (settings.unloadOnKeyboardClose) unloadEngines("keyboard closed")
        }
    }

    private fun stopKeyboardCloseUnload() {
        keyboardCloseUnloadJob?.cancel()
        keyboardCloseUnloadJob = null
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
            unloadEngines("critical memory pressure")
        }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        Log.i(TAG, "Low memory — releasing engines if idle")
        unloadEngines("low memory")
    }

    /**
     * **The one place the overlay view's visibility is decided and written.** Every path that shows
     * or hides the pill comes through here, so no visibility write can happen without the decision
     * being made in one place — the transition (and whether it may be animated) is
     * [PillEnter.transitionFor]'s, including the four gates on `animated`.
     *
     * [animated] is the caller's *request* only: the keyboard signal is the one transition a user
     * watches, so [onKeyboardOrFocusChanged] is the only caller that passes `true`. A service start,
     * a settings change, a display change and a session cancel keep today's instant behaviour.
     */
    private fun updateOverlayVisibility(animated: Boolean = false) {
        val view = composeView ?: return
        val shouldShow = when {
            !settings.floatingPillEnabled -> false
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
            start = start,
            rest = rest,
            requestedAnimated = animated,
            keyboardDrivenVisibility = settings.floatingPillOnlyWhenKeyboardOpen,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        )
        when (transition) {
            // Nothing changed — but a running arrival still has to be abandoned rather than left
            // writing from a decision nobody made.
            PillTransition.None -> cancelEntry(restore = true)
            is PillTransition.Enter -> if (transition.animated) {
                startEntry(transition.start, transition.rest, view)
            } else {
                cancelEntry(restore = true)
                view.visibility = View.VISIBLE
            }
            is PillTransition.Leave -> {
                // CHUNK-3 replaces exactly this branch with the mirror of the arrival. Until then
                // the departure is instant, which is a coherent intermediate state.
                cancelEntry(restore = true)
                view.visibility = View.GONE
            }
        }
    }

    /**
     * Abandons a running arrival: the tick stops and the entry state is dropped.
     *
     * With [restore] the window is first put back at the rest position the arrival started from —
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
            // An arrival cut short is otherwise indistinguishable from one that never started — and
            // "a redundant keyboard signal killed it" is the failure this trace exists to name.
            val elapsedMs = (System.nanoTime() - entryStartedAtNanos) / 1_000_000
            Log.i(TAG, "Entry abandoned after ${elapsedMs}ms (restore=$restore)")
        }
        entryRunning = false
        if (restore && rest != null) {
            writeWindowPosition(PillPoint(rest.x.toInt(), rest.y.toInt()))
        }
        entryStart = null
        entryRest = null
        entryValue = 1f
        entryVelocity = 0f
        entryTarget = 1f
    }

    /**
     * Starts the arrival, in the one order that does not flash: the start position is written to the
     * window **while the view is still `GONE`**, and only then is the view shown. Showing first would
     * draw the pill at its dock for one frame before the slide moved it.
     */
    private fun startEntry(start: PillOffset, rest: PillOffset, view: View) {
        entryValue = 0f
        entryVelocity = 0f
        entryTarget = 1f
        entryStart = start
        entryRest = rest
        val startPoint = PillPoint(start.x.toInt(), start.y.toInt())
        writeWindowPosition(startPoint)

        // That start is *outside* the display (PillEnter.startOffset), so read it back: if this build
        // rewrote it — a window manager is not obliged to keep an off-screen position — the arrival
        // uses the platform's value for this transition. Degraded, not broken: the pill then starts
        // at the edge instead of outside it. Only a device can say which branch runs.
        val params = layoutParams
        if (params != null && params.x != startPoint.x) {
            Log.i(
                TAG,
                "Entry start clamped: platform rewrote x from ${startPoint.x} to ${params.x} px",
            )
            entryStart = start.copy(x = params.x.toFloat())
        }

        view.visibility = View.VISIBLE
        entryLastNanos = System.nanoTime()
        entryStartedAtNanos = entryLastNanos
        entryRunning = true
        view.postOnAnimation(entryTick)
    }

    // --- Dictation Capture & ASR ---

    private fun toggleRecording() {
        if (uiState == FloatingUiState.RECORDING || recorder.isRecording) {
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
     * recorder, the frame sink, the ASR session and the model load. Keeping this in one place is
     * what makes "quick capture uses the same code as the regular pill" true rather than aspirational.
     */
    private fun beginCapture(targetState: FloatingUiState, fresh: Boolean, quick: Boolean) {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            Toast.makeText(this, "Microphone permission required for Smart Voice", Toast.LENGTH_SHORT).show()
            return
        }

        if (recorder.isRecording) return

        // A capture is about to need the engines: a pending idle/keyboard-close unload must not fire
        // under it (the guard in `unloadEngines` is the backstop, this just keeps them from racing).
        stopIdleUnload()
        stopKeyboardCloseUnload()

        quickCaptureActive = quick
        recordStartedAtMs = SystemClock.elapsedRealtime()
        uiState = targetState
        audioLevel = 0f
        statusNote = null
        errorMessage = null
        settingsOpen = false
        dismissWordSelection()

        if (fresh) {
            previewText = ""
            rawText = ""
            rawTextAccumulator.clear()
            committedText.clear()
        }
        if (quick) {
            quickStatus = null
            quickStatusIsError = false
        }
        cleanupEngaged = false

        localEngine.applyModel(settings.asrModel, filesDir)
        localEngine.applyPauseSilence(settings.pauseSilenceSeconds)

        // Warm at capture start and nowhere else. After the two apply calls, because both can unload
        // a resident model that no longer matches the settings.
        warmEngines()

        localSession.begin()
        recorder.frameSink = { buffer, read ->
            localSession.onFrames(buffer, read)
            // Raw RMS only: the visual level is derived on a perceptual (dB) scale by the overlay,
            // so the mic gain is not baked in here.
            audioLevel = calculatePcmRms(buffer, read).coerceIn(0f, 1f)
        }

        try {
            recorder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cancelSession()
            return
        }

        if (quick) performHaptic()
        startAudioMetering()
        startSilenceMonitoring()

        scope.launch {
            val availability = localSession.open(settings.language)
            asrModelLoaded = availability is LocalAsrEngine.Availability.Loaded
            if (availability !is LocalAsrEngine.Availability.Loaded) {
                val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason ?: "model not ready"
                statusNote = "ASR unavailable: $reason"
            }
        }
    }

    private fun stopRecording() {
        if (!recorder.isRecording) return
        finishRecordingAccounting()
        stopAudioMetering()
        stopSilenceMonitoring()

        uiState = FloatingUiState.REVIEW
        isProcessing = true
        statusNote = "Polishing with AI..."

        inFlightJob = scope.launch {
            finalizeUtterance()
            isProcessing = false
            statusNote = null
            onCaptureFinished()

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
        if (!recorder.isRecording) return

        finishRecordingAccounting()
        stopAudioMetering()
        stopSilenceMonitoring()

        isProcessing = true
        inFlightJob = scope.launch {
            finalizeUtterance()
            isProcessing = false
            onCaptureFinished()
            deliverQuickCapture()
        }
    }

    /**
     * Stops the recorder and runs the utterance to settled text. Shared by the card and quick
     * paths so a change to the pipeline cannot apply to only one of them.
     */
    private suspend fun finalizeUtterance() {
        withContext(Dispatchers.IO) {
            recorder.stop()
        }
        localSession.finish()

        if (settings.cleanupEnabled && cleanupEngaged) {
            cleanupPipeline.drain()
        }

        finalizePunctuation()
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
        val textToInject = previewText.ifBlank { rawTextAccumulator.toString() }.trim()
        if (textToInject.isBlank()) {
            showQuickResult("Nothing heard")
        } else {
            val toType = UtteranceFinalizer.forInsertion(textToInject)
            val injected = VoiceAccessibilityService.instance?.injectText(toType) ?: false
            recordUsageStats(deliveredText = textToInject)
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

        // Let the result read, then rest. A new session resets the state first, and the guard means
        // a late timer cannot clobber it.
        delay(QUICK_STATUS_MS)
        if (uiState == FloatingUiState.QUICK_CAPTURE && !isProcessing) {
            resetToResting()
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

    private fun cancelSession() {
        // ✕ — the explicit discard. Always throws the session away.
        abandonCapture()
        resetToResting()
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
        if (recorder.isRecording) {
            abandonCapture()
        }
        val textToInject = previewText.ifBlank { rawTextAccumulator.toString() }.trim()
        if (textToInject.isNotBlank()) {
            // A trailing space separates this session from the next: the pill has no memory of where
            // the cursor sits between sessions, and re-dictating must not glue the new segment onto
            // this one. It comes after terminal punctuation too ("Done. ").
            val toType = UtteranceFinalizer.forInsertion(textToInject)
            val injected = VoiceAccessibilityService.instance?.injectText(toType) ?: false
            if (!injected) {
                // Never fail silently: the words are on the clipboard, so tell the user how to use them.
                Log.w(TAG, "Text injected via clipboard fallback")
                Toast.makeText(this, "Copied — long-press the text field to paste", Toast.LENGTH_LONG).show()
            }
        }
        recordUsageStats(deliveredText = textToInject)
        resetToResting()
    }

    /**
     * The pill is the primary surface, so its dictation has to reach the same
     * stats store the IME writes to — otherwise the dashboard silently misses
     * most of what the user actually dictated.
     */
    private fun recordUsageStats(deliveredText: String) {
        val raw = rawTextAccumulator.toString().trim()
        if (raw.isEmpty() && deliveredText.isEmpty()) return

        val diff = if (cleanupEngaged && raw.isNotEmpty()) {
            WordDiffCalculator.calculate(rawText = raw, cleanedText = deliveredText)
        } else {
            WordDiffCalculator.verbatim(raw.ifEmpty { deliveredText })
        }
        usageStatsStore.record(
            durationSeconds = currentSessionSeconds(),
            rawWords = diff.rawWords,
            cleanedWords = diff.wordsCleaned,
            engine = Engine.LOCAL.label,
            deliveredWords = diff.deliveredWords,
            wordsRemoved = diff.wordsRemoved,
            wordsChanged = diff.wordsChanged,
            wordsAdded = diff.wordsAdded,
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
        val textToRewrite = rawTextAccumulator.toString().trim()
        if (textToRewrite.isBlank() || isProcessing) return
        // The transcript is about to be rewritten wholesale, so any selected word's range is stale.
        dismissWordSelection()

        isProcessing = true
        statusNote = "Reprocessing entire text with LLM..."

        scope.launch(cleanupDispatcher) {
            val engine = engineForUtterance()
            if (!engine.isLoaded) {
                val loadRes = engine.load()
                cleanupModelLoaded = engine.isLoaded
                if (loadRes.isFailure) {
                    // Reviews are non-destructive: the text on screen is already safe, so this is
                    // an advisory note rather than the raw-text fallback treatment.
                    withContext(Dispatchers.Main.immediate) {
                        isProcessing = false
                        statusNote = "Couldn't reload the cleanup model"
                    }
                    return@launch
                }
            } else {
                cleanupModelLoaded = true
            }

            val activeProfile = profileStore.activeProfile()
            val rules = if (!activeProfile.isBuiltin) activeProfile.rules else null
            val variant = CleanupPrompt.Variant.fromId(activeProfile.id)

            val pass = engine.clean(
                unit = textToRewrite,
                context = null, // No context prefix; rewrites the whole text cleanly
                variant = variant,
                customRules = rules,
            )

            withContext(Dispatchers.Main.immediate) {
                isProcessing = false
                if (pass.cleaned) {
                    previewText = pass.text
                    committedText.clear()
                    committedText.append(pass.text)
                    val costMs = (pass.promptTimeMs + pass.decodeTimeMs).toInt()
                    statusNote = "Reprocessed full text (${costMs}ms)"
                } else {
                    statusNote = "Reprocess failed: ${pass.note.ifBlank { "unknown" }}"
                }
            }
        }
    }

    private fun resetToResting() {
        uiState = FloatingUiState.RESTING_PILL
        quickCaptureActive = false
        quickStatus = null
        quickStatusIsError = false
        previewText = ""
        rawText = ""
        audioLevel = 0f
        statusNote = null
        errorMessage = null
        settingsOpen = false
        dismissWordSelection()
        isProcessing = false
        rawTextAccumulator.clear()
        committedText.clear()
        recordStartedAtMs = 0L
        recordedMillis = 0L
        // A session that was cancelled (rather than finalised) still ends the capture; the engines
        // are idle from here. Harmless when a finalised capture already armed the same timer.
        onCaptureFinished()
        updateOverlayVisibility()
    }

    private fun abandonCapture() {
        stopAudioMetering()
        stopSilenceMonitoring()
        inFlightJob?.cancel()
        inFlightJob = null
        if (recorder.isRecording) {
            runCatching { recorder.stop() }
        }
        localSession.abort()
        if (cleanupEngaged) {
            cleanupPipeline.abort()
            cleanupEngaged = false
        }
    }

    private fun onAsrUnit(unit: String) {
        if (unit.isBlank()) return
        scope.launch(Dispatchers.Main.immediate) {
            if (rawTextAccumulator.isNotEmpty() && !rawTextAccumulator.endsWith(" ")) {
                rawTextAccumulator.append(" ")
            }
            rawTextAccumulator.append(unit)
            rawText = rawTextAccumulator.toString()

            if (settings.cleanupEnabled) {
                val engine = engineForUtterance()

                // If the engine isn't loaded yet, wait for it (suspends this coroutine only;
                // recording and the ASR pipeline keep running normally on their own threads).
                if (!engine.isLoaded) {
                    val loadResult = withContext(cleanupDispatcher) { engine.load() }
                    cleanupModelLoaded = engine.isLoaded
                    if (loadResult.isFailure) {
                        // Never surface the exception text: it is noise to a user mid-sentence.
                        // The raw ASR still lands below, so no words are lost.
                        Log.e(TAG, "LLM cleanup engine failed to load: ${loadResult.exceptionOrNull()?.message}")
                        errorMessage = CLEANUP_UNAVAILABLE
                        // Fall through to raw-append below
                    }
                }

                if (engine.isLoaded) {
                    cleanupModelLoaded = true
                    errorMessage = null
                    if (!cleanupEngaged) {
                        cleanupPipeline.begin(committedText.toString())
                        cleanupEngaged = true
                    }
                    val seam = if (settings.healingSentencesCount > 0 && committedText.isNotEmpty()) {
                        SeamHealer.split(committedText.toString(), settings.healingSentencesCount)
                    } else null
                    cleanupPipeline.submit(unit, seam)
                    return@launch
                }
            }

            // Cleanup disabled or engine failed to load — append raw text as fallback
            if (committedText.isNotEmpty() && !committedText.endsWith(" ") && !unit.startsWith(" ")) {
                committedText.append(" ")
            }
            committedText.append(unit)
            previewText = committedText.toString()
        }
    }

    private fun finalizePunctuation() {
        val action = UtteranceFinalizer.resolve(committedText)
        if (action == UtteranceFinalizer.Action.REPLACE_COMMA) {
            val trimLen = committedText.trimEnd().length
            if (trimLen > 0) {
                committedText.setLength(trimLen - 1)
                committedText.append('.')
                previewText = committedText.toString()
            }
        } else if (action == UtteranceFinalizer.Action.APPEND_PERIOD) {
            committedText.append('.')
            previewText = committedText.toString()
        }
    }

    private fun startAudioMetering() {
        // Metering is updated directly in recorder.frameSink
    }

    private fun stopAudioMetering() {
        audioLevel = 0f
    }

    private fun startSilenceMonitoring() {
        silenceMonitoringJob?.cancel()
        silenceMonitoringJob = scope.launch {
            while (isActive && recorder.isRecording) {
                delay(100L)
                val state = localSession.boundaryState
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
                if (!quickCaptureActive && recorder.capturedSeconds >= 10.0 && !state.heardAudio) {
                    Log.i(TAG, "Initial silence timeout (10s) reached without speech")
                    stopCapture()
                    break
                }
                // 3. Max recording duration ceiling (300s / 5min): a runaway guard on both paths.
                if (recorder.capturedSeconds >= 300.0) {
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

    private fun calculatePcmRms(bytes: ByteArray, length: Int): Float {
        val samplesCount = length / 2
        if (samplesCount == 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until samplesCount) {
            val offset = i * 2
            if (offset + 1 >= length) break
            val low = bytes[offset].toInt() and 0xFF
            val high = bytes[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val norm = sample / 32768.0
            sumSquares += norm * norm
        }
        return sqrt(sumSquares / samplesCount).toFloat()
    }

    private fun engineForUtterance(): CleanupEngine {
        cleanupEngine?.let { existing ->
            if (existing.isServing(settings)) return existing
            existing.unload()
            cleanupModelLoaded = false
        }
        return LiteRtLmCleanupEngine(
            context = this,
            modelSpec = settings.litertModel,
            processor = settings.litertProcessor,
            speculativeDecoding = settings.litertSpeculativeDecoding,
            vocabulary = dictionaryStore::getEntries,
        ).also { cleanupEngine = it }
    }

    private inner class CurrentCleanupEngine : CleanupEngine {
        override val isLoaded: Boolean get() = cleanupEngine?.isLoaded == true
        override val lastLoadMs: Long get() = cleanupEngine?.lastLoadMs ?: 0L
        override suspend fun load(): Result<Unit> = engineForUtterance().load()

        override suspend fun clean(
            unit: String,
            context: String?,
            variant: CleanupPrompt.Variant,
            customRules: String?,
        ): CleanupPass {
            val engine = cleanupEngine ?: return CleanupPass.failed(unit, "no cleanup model loaded")
            val active = profileStore.activeProfile()
            val effectiveRules = customRules ?: if (!active.isBuiltin) active.rules else null
            return engine.clean(unit, context, variant, effectiveRules)
        }

        override fun unload() {
            cleanupEngine?.unload()
            cleanupModelLoaded = false
        }

        override fun startSession(variant: CleanupPrompt.Variant, customRules: String?) {
            val engine = cleanupEngine ?: return
            val active = profileStore.activeProfile()
            val effectiveRules = customRules ?: if (!active.isBuiltin) active.rules else null
            engine.startSession(variant, effectiveRules)
        }

        override fun closeSession() {
            cleanupEngine?.closeSession()
        }
    }

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val CHANNEL_ID = "smartvoice_floating_pill"
        private const val NOTIFICATION_ID = 4001

        /** Below this release speed a fling is not worth starting; the pill just settles. */
        private const val FLING_MIN_VELOCITY_PX_PER_SECOND = 120f

        /** How long the minimized pill shows a quick-capture result before returning to rest. */
        private const val QUICK_STATUS_MS = 1600L

        /**
         * Grace after the keyboard closes before the engines are released. A tuned constant, not a
         * setting: long enough to survive moving between two fields, short enough to matter.
         */
        private const val KEYBOARD_CLOSE_GRACE_MS = 8_000L

        /** The error state's user-facing line. Deliberately says nothing about exceptions. */
        const val CLEANUP_UNAVAILABLE = "Cleanup is unavailable — showing your words as spoken."

        @Volatile
        var instance: FloatingOverlayService? = null
            private set

        fun start(context: Context) {
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
                if (settings.floatingPillEnabled) {
                    start(context)
                }
            }
        }
    }
}
