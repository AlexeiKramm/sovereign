package fi.ak.smartvoice.floating

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import fi.ak.smartvoice.R
import fi.ak.smartvoice.asr.AudioLevel
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
    private var composeView: ComposeView? = null
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
    /** Drives the resting pill's breathing: it animates only while the cleanup model is ready. */
    private var cleanupModelLoaded by mutableStateOf(false)
    private var quickSettingsOpen by mutableStateOf(false)
    private var darkTheme by mutableStateOf(false)
    private var profiles by mutableStateOf(emptyList<CleaningProfile>())
    private var activeProfileId by mutableStateOf("")

    // Drag / fling state. The pill's position is kept as floats so a slow drag is not truncated
    // into never moving; it is flushed to the window at most once per frame.
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging = false
    private var dragFlushScheduled = false
    private val dragVelocity = DragVelocityTracker()

    // Fling: one decaying velocity vector for both axes (see PillFling), stepped per frame.
    private var fling = PillFling()
    private var flingVelocityX = 0f
    private var flingVelocityY = 0f
    private var flingLastNanos = 0L
    private var flingStartedAtNanos = 0L
    private var flingStartX = 0f
    private var flingStartY = 0f

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

    // Accumulators
    private val rawTextAccumulator = StringBuilder()
    private val committedText = StringBuilder()
    private var cleanupEngaged = false
    private var meteringJob: Job? = null
    private var inFlightJob: Job? = null
    private var silenceMonitoringJob: Job? = null

    /** Wall-clock accounting for the usage stats the dashboard shows. */
    private var recordStartedAtMs = 0L
    private var recordedMillis = 0L

    // Keyboard & focus visibility tracker
    private var isKeyboardOpen = false
    private var hasEditableFocus = false

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
        refreshProfiles()
        setupEngines()
        setupOverlayView()
        updateOverlayVisibility()
        prefetchCleanupEngine()
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
        // The display may have rotated or resized: re-clamp so the pill cannot be stranded off-screen.
        composeView?.post { applyWindowPosition() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        abandonCapture()
        removeOverlayView()
        scope.cancel()
        localEngine.unload()
        cleanupEngine?.unload()
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Observe touches that land outside the pill (without stealing them from the app
                // underneath) so an outside tap can dismiss the ··· panel and minimise the card.
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = start.x
            y = start.y
        }
        layoutParams = params

        val view = ComposeView(this).apply {
            overlayOwner.attach(this)
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
                    modelReady = cleanupModelLoaded,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    quickSettingsOpen = quickSettingsOpen,
                    onPillClicked = { startOrResumeRecording(fresh = true) },
                    onCancel = ::cancelSession,
                    onToggleRecording = ::toggleRecording,
                    onCommit = ::commitSession,
                    onReprocessFullText = ::reprocessFullText,
                    onToggleQuickSettings = {
                        quickSettingsOpen = !quickSettingsOpen
                        // The active profile can change from Home or the Profiles tab while the pill
                        // lives on; re-read on open so the sheet's radio never shows a stale choice.
                        // (Cleanup itself always reads the store at use time, so this is display only.)
                        if (quickSettingsOpen) refreshProfiles()
                    },
                    onDismissQuickSettings = { quickSettingsOpen = false },
                    onSelectProfile = ::selectProfile,
                    onDragStart = ::beginDrag,
                    onDragBy = ::dragBy,
                    onDragEnd = ::endDrag,
                    onDragCancel = ::endDragWithoutFling,                )
            }
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
        // hanging off the edge: re-clamp after every layout, but never while the user is moving it.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isDragging && flingLastNanos == 0L) applyWindowPosition()
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

    // --- Dragging ---
    //
    // `updateViewLayout` is a real window relayout, so the old code's one-call-per-pointer-event
    // could miss frames at 120 Hz. Instead the pointer deltas accumulate in floats and a single
    // frame-coalesced flush writes them to the window. The release velocity comes from the window
    // offset's own trajectory (`DragVelocityTracker`) and the glide from `PillFling` — one decaying
    // vector for both axes, so a diagonal flick stays diagonal.

    private fun beginDrag() {
        stopFling()
        isDragging = true
        layoutParams?.let {
            dragOffsetX = it.x.toFloat()
            dragOffsetY = it.y.toFloat()
        }
        dragVelocity.reset(System.nanoTime(), dragOffsetX, dragOffsetY)
    }

    private fun dragBy(dx: Float, dy: Float) {
        // Gravity is BOTTOM | END: moving the finger right/up decreases x/y (the distance from
        // the anchored corner). Both axes are treated identically.
        dragOffsetX -= dx
        dragOffsetY -= dy
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

    /** Clamps the pending position to the screen and writes it to the window if it changed. */
    private fun applyWindowPosition() {
        val params = layoutParams ?: return
        val view = composeView ?: return
        val clamped = clampToScreen(view)
        // Only the clamped case collapses the fractional offset: staying in bounds must keep the
        // fraction, or a slow drag would lose sub-pixel movement and the fling's per-frame edge
        // check would misread every frame as "clamped".
        if (clamped != desiredPoint()) {
            dragOffsetX = clamped.x.toFloat()
            dragOffsetY = clamped.y.toFloat()
        }
        writeWindowPosition(clamped)
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

    /** Switches the active profile from the pill's quick settings, without opening the app. */
    private fun selectProfile(id: String) {
        profileStore.setActiveProfile(id)
        refreshProfiles()
        quickSettingsOpen = false
        Log.i(TAG, "Active profile switched to $id")
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

        updateOverlayVisibility()
        if (keyboardOpen) {
            prefetchCleanupEngine()
        }
    }

    private fun prefetchCleanupEngine() {
        if (!settings.cleanupEnabled) return
        scope.launch(cleanupDispatcher) {
            val engine = engineForUtterance()
            if (!engine.isLoaded) {
                val result = engine.load()
                cleanupModelLoaded = engine.isLoaded
                if (result.isFailure) {
                    Log.w(TAG, "Cleanup model prefetch failed: ${result.exceptionOrNull()?.message}")
                }
            } else {
                cleanupModelLoaded = true
            }
        }
    }

    private fun updateOverlayVisibility() {
        val view = composeView ?: return
        val shouldShow = when {
            !settings.floatingPillEnabled -> false
            uiState != FloatingUiState.RESTING_PILL -> true // keep visible while recording or reviewing
            settings.floatingPillOnlyWhenKeyboardOpen -> isKeyboardOpen
            else -> true
        }
        view.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    // --- Dictation Capture & ASR ---

    private fun toggleRecording() {
        if (uiState == FloatingUiState.RECORDING || recorder.isRecording) {
            stopRecording()
        } else {
            startOrResumeRecording(fresh = false)
        }
    }

    private fun startOrResumeRecording(fresh: Boolean) {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            Toast.makeText(this, "Microphone permission required for Smart Voice", Toast.LENGTH_SHORT).show()
            return
        }

        if (recorder.isRecording) return

        recordStartedAtMs = SystemClock.elapsedRealtime()
        uiState = FloatingUiState.RECORDING
        audioLevel = 0f
        statusNote = null
        errorMessage = null
        quickSettingsOpen = false

        if (fresh) {
            previewText = ""
            rawText = ""
            rawTextAccumulator.clear()
            committedText.clear()
        }
        cleanupEngaged = false

        if (settings.cleanupEnabled) {
            prefetchCleanupEngine()
        }

        localEngine.applyModel(settings.asrModel, filesDir)
        localEngine.applyPauseSilence(settings.pauseSilenceSeconds)

        localSession.begin()
        recorder.frameSink = { buffer, read ->
            localSession.onFrames(buffer, read)
            val rms = calculatePcmRms(buffer, read)
            audioLevel = (rms * 5f).coerceIn(0.05f, 1f)
        }

        try {
            recorder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cancelSession()
            return
        }

        startAudioMetering()
        startSilenceMonitoring()

        scope.launch {
            val availability = localSession.open(settings.language)
            if (availability !is LocalAsrEngine.Availability.Loaded) {
                val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason ?: "model not ready"
                statusNote = "ASR unavailable: $reason"
            }
        }
    }

    private fun stopRecording() {
        if (!recorder.isRecording) return
        recordStartedAtMs.let { started ->
            if (started > 0L) {
                recordedMillis += SystemClock.elapsedRealtime() - started
                recordStartedAtMs = 0L
            }
        }
        stopAudioMetering()
        stopSilenceMonitoring()

        uiState = FloatingUiState.REVIEW
        isProcessing = true
        statusNote = "Polishing with AI..."

        inFlightJob = scope.launch {
            withContext(Dispatchers.IO) {
                recorder.stop()
            }
            localSession.finish()

            if (settings.cleanupEnabled && cleanupEngaged) {
                cleanupPipeline.drain()
            }

            finalizePunctuation()
            isProcessing = false
            statusNote = null

            if (settings.floatingPillAutoCommit) {
                commitSession()
            }
        }
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
            quickSettingsOpen = false
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
            val injected = VoiceAccessibilityService.instance?.injectText(textToInject) ?: false
            if (!injected) {
                Log.w(TAG, "Text injected via clipboard fallback")
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
        previewText = ""
        rawText = ""
        audioLevel = 0f
        statusNote = null
        errorMessage = null
        quickSettingsOpen = false
        isProcessing = false
        rawTextAccumulator.clear()
        committedText.clear()
        recordStartedAtMs = 0L
        recordedMillis = 0L
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
                // 1. Normal speech pause auto-stop (when speech has been heard and silence duration exceeds stopAfterSilenceSeconds)
                if (stopAfter > 0f && state.heardAudio && state.quietFor >= stopAfter) {
                    Log.i(TAG, "Silence threshold reached (%.1fs >= %.1fs); auto-stopping".format(state.quietFor, stopAfter))
                    stopRecording()
                    break
                }
                // 2. Initial silence timeout (10s without speech after starting)
                if (recorder.capturedSeconds >= 10.0 && !state.heardAudio) {
                    Log.i(TAG, "Initial silence timeout (10s) reached without speech")
                    stopRecording()
                    break
                }
                // 3. Max recording duration ceiling (300s / 5min)
                if (recorder.capturedSeconds >= 300.0) {
                    Log.i(TAG, "Max recording duration ceiling reached")
                    stopRecording()
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
            dictionary = dictionaryStore.getWords(),
            dictionaryEntries = dictionaryStore.getEntries(),
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
