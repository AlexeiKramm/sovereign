package fi.ak.smartvoice.floating

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
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
import fi.ak.smartvoice.data.DictionaryStore
import fi.ak.smartvoice.data.ProfileStore
import fi.ak.smartvoice.ime.UtteranceFinalizer
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

    // Accumulators
    private val rawTextAccumulator = StringBuilder()
    private val committedText = StringBuilder()
    private var cleanupEngaged = false
    private var meteringJob: Job? = null
    private var inFlightJob: Job? = null
    private var silenceMonitoringJob: Job? = null

    // Keyboard & focus visibility tracker
    private var isKeyboardOpen = false
    private var hasEditableFocus = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        profileStore = ProfileStore(this)
        dictionaryStore = DictionaryStore(this)
        recorder = WavRecorder()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForegroundWithNotification()

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
        val initialX = (16 * density).toInt()
        val initialY = (90 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = initialX
            y = initialY
        }
        layoutParams = params

        val view = ComposeView(this).apply {
            overlayOwner.attach(this)
            setContent {
                FloatingDictationOverlay(
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    audioLevel = audioLevel,
                    isProcessing = isProcessing,
                    statusNote = statusNote,
                    onPillClicked = { startOrResumeRecording(fresh = true) },
                    onCancel = ::cancelSession,
                    onToggleRecording = ::toggleRecording,
                    onCommit = ::commitSession,
                    onReprocessFullText = ::reprocessFullText,
                    onDrag = ::handleDrag,
                )
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

    private fun handleDrag(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = composeView ?: return
        // Since gravity is BOTTOM | END, dragging left (dx < 0) increases x distance from END
        params.x = (params.x - dx.toInt()).coerceAtLeast(0)
        // Dragging up (dy < 0) increases y distance from BOTTOM
        params.y = (params.y - dy.toInt()).coerceAtLeast(0)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout", e)
        }
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
                engine.load()
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

        uiState = FloatingUiState.RECORDING
        audioLevel = 0f
        statusNote = null

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
        abandonCapture()
        resetToResting()
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
        resetToResting()
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
                if (loadRes.isFailure) {
                    withContext(Dispatchers.Main.immediate) {
                        isProcessing = false
                        statusNote = "Failed to load cleanup model"
                    }
                    return@launch
                }
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
        isProcessing = false
        rawTextAccumulator.clear()
        committedText.clear()
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
                    if (loadResult.isFailure) {
                        val reason = loadResult.exceptionOrNull()?.message ?: "unknown"
                        Log.e(TAG, "LLM cleanup engine failed to load: $reason")
                        statusNote = "⚠ LLM unavailable: $reason"
                        // Fall through to raw-append below
                    }
                }

                if (engine.isLoaded) {
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
