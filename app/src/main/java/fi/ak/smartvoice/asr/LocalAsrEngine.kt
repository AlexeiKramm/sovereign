package fi.ak.smartvoice.asr

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * The on-device ASR engine router: supports both streaming transducer models (Nemotron 3.5)
 * and offline speech recognition models (Whisper Tiny/Base/Small/Turbo and Parakeet CTC)
 * with Silero VAD segmenting.
 *
 * Threading contract: **one thread at a time, always.**
 * Memory contract: lazy loaded on first use, sticky resident across utterances until unload().
 */
class LocalAsrEngine(
    modelDirectory: File,
    private val numThreads: Int = DEFAULT_THREADS,
    pauseSilenceSeconds: Float = DEFAULT_PAUSE_SILENCE_SECONDS,
    modelSpec: AsrModelSpec = AsrModels.NEMOTRON,
    private val assetManager: AssetManager? = null,
) {

    @Volatile
    var modelDirectory: File = modelDirectory
        private set

    @Volatile
    var modelSpec: AsrModelSpec = modelSpec
        private set

    @Volatile
    var pauseSilenceSeconds: Float = pauseSilenceSeconds
        private set

    private var offlineEngine: OfflineVadAsrEngine? = null

    private fun getOrCreateOfflineEngine(): OfflineVadAsrEngine {
        val existing = offlineEngine
        if (existing != null) return existing
        val created = OfflineVadAsrEngine(
            modelDirectory = modelDirectory,
            spec = modelSpec,
            assetManager = assetManager,
            numThreads = numThreads,
            pauseSilenceSeconds = pauseSilenceSeconds,
        )
        offlineEngine = created
        return created
    }

    /** Applies a new model specification. Unloads the recogniser if it changed. */
    fun applyModel(newSpec: AsrModelSpec, filesDir: File) {
        if (newSpec.id == modelSpec.id) return
        unload()
        modelSpec = newSpec
        modelDirectory = newSpec.directory(filesDir)
    }

    /** Applies a new pause threshold. Unloads the recogniser if it actually changed. */
    fun applyPauseSilence(seconds: Float) {
        if (seconds == pauseSilenceSeconds) return
        pauseSilenceSeconds = seconds
        offlineEngine?.applyPauseSilence(seconds)
        unload()
    }

    sealed interface Availability {
        /** The model files are not on the device yet. Nothing to load. */
        data object NotInstalled : Availability

        /** Installed but not loaded — the state after process start (lazy load, D-010). */
        data object NotLoaded : Availability

        /** Loaded and usable. Sticky until [unload]. */
        data object Loaded : Availability

        /** Installed but the runtime refused it. The reason is shown to the user, never swallowed. */
        data class Unavailable(val reason: String) : Availability
    }

    // Streaming transducer state
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var vad: Vad? = null
    private var pendingEndpoint: Boolean = false
    private var failure: String? = null

    @Volatile
    private var streamingLoadMillis: Long = 0L

    val lastLoadMillis: Long
        get() = if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            offlineEngine?.lastLoadMillis ?: 0L
        } else {
            streamingLoadMillis
        }

    val isSessionActive: Boolean
        get() = if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            offlineEngine?.isSessionActive == true
        } else {
            stream != null
        }

    fun availability(): Availability {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().availability()
        }
        return when {
            failure != null -> Availability.Unavailable(failure!!)
            recognizer != null -> Availability.Loaded
            !modelSpec.isInstalled(modelDirectory) -> Availability.NotInstalled
            else -> Availability.NotLoaded
        }
    }

    fun load(): Availability {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().load()
        }
        recognizer?.let { return Availability.Loaded }
        failure?.let { return Availability.Unavailable(it) }
        if (!modelSpec.isInstalled(modelDirectory)) return Availability.NotInstalled

        return try {
            val startedAt = SystemClock.elapsedRealtime()
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AsrModels.SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDirectory, modelSpec.encoderFileName.ifEmpty { "encoder.int8.onnx" }).absolutePath,
                        decoder = File(modelDirectory, modelSpec.decoderFileName.ifEmpty { "decoder.int8.onnx" }).absolutePath,
                        joiner = File(modelDirectory, modelSpec.joinerFileName.ifEmpty { "joiner.int8.onnx" }).absolutePath,
                    ),
                    tokens = File(modelDirectory, modelSpec.tokensFileName).absolutePath,
                    numThreads = numThreads,
                    provider = "cpu",
                    modelType = "",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, EndpointRules.NEVER_SECONDS, EndpointRules.NEVER_SECONDS),
                    rule2 = EndpointRule(false, EndpointRules.NEVER_SECONDS, EndpointRules.NEVER_SECONDS),
                    rule3 = EndpointRule(false, EndpointRules.NEVER_SECONDS, EndpointRules.NEVER_SECONDS),
                ),
                enableEndpoint = false,
            )
            val created = OnlineRecognizer(assetManager = null, config = config)
            recognizer = created

            if (assetManager != null) {
                val sileroConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = pauseSilenceSeconds,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 30.0f,
                )
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = sileroConfig,
                    sampleRate = AsrModels.SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                )
                vad = Vad(assetManager = assetManager, config = vadConfig)
            }

            streamingLoadMillis = SystemClock.elapsedRealtime() - startedAt
            Availability.Loaded
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.java.simpleName
            failure = reason
            Availability.Unavailable(reason)
        }
    }

    fun startSession(language: String, hotwords: String = "") {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            getOrCreateOfflineEngine().startSession(language, hotwords)
            return
        }
        val active = load()
        check(active is Availability.Loaded) { "local engine not loaded: $active" }
        abort()

        val created = recognizer!!.createStream(hotwords)
        created.setOption("language", language)
        stream = created
        vad?.reset()
        pendingEndpoint = false
    }

    fun accept(samples: FloatArray, count: Int = samples.size): String? {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().accept(samples, count)
        }
        val active = stream ?: return null
        val effectiveSamples = if (count == samples.size) samples else samples.copyOf(count)
        active.acceptWaveform(effectiveSamples, AsrModels.SAMPLE_RATE)
        drain()

        val v = vad
        if (v != null) {
            v.acceptWaveform(effectiveSamples)
            if (!v.empty()) {
                v.pop()
                pendingEndpoint = true
            }
        }

        return recognizer?.getResult(active)?.text
    }

    fun result(): String {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().result()
        }
        val active = stream ?: return ""
        return recognizer?.getResult(active)?.text.orEmpty()
    }

    fun resetSegment() {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            getOrCreateOfflineEngine().resetSegment()
            return
        }
        val active = stream ?: return
        recognizer?.reset(active)
        pendingEndpoint = false
    }

    fun isEndpoint(): Boolean {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().isEndpoint()
        }
        val endpoint = pendingEndpoint
        pendingEndpoint = false
        return endpoint
    }

    fun isSpeechDetected(): Boolean {
        return if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            offlineEngine?.isSpeechDetected() == true
        } else {
            vad?.isSpeechDetected() == true
        }
    }

    fun finish(): String {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().finish()
        }
        vad?.flush()
        vad?.reset()
        pendingEndpoint = false

        val active = stream ?: return ""
        val engine = recognizer ?: return ""

        // Tail padding with silence guarantees any partial chunk (< 560ms or < 320ms)
        // accumulates enough frames for engine.isReady(active) to evaluate to true,
        // preventing the final words of the utterance from being dropped on stream release.
        val paddingSamples = (AsrModels.SAMPLE_RATE * STREAMING_TAIL_PADDING_SECONDS).toInt()
        val padding = FloatArray(paddingSamples)
        active.acceptWaveform(padding, AsrModels.SAMPLE_RATE)
        active.inputFinished()

        var decodes = 0
        while (engine.isReady(active) && decodes < MAX_FLUSH_DECODES) {
            engine.decode(active)
            decodes++
        }
        val text = engine.getResult(active).text
        active.release()
        stream = null
        return text
    }

    /**
     * Transcribes an in-memory audio sample buffer sequentially.
     * Useful for file-based transcription and standard benchmarking fixtures.
     */
    fun transcribeBuffer(
        samples: FloatArray,
        language: String,
        chunkSize: Int = 1600,
    ): String {
        startSession(language = language)
        for (i in samples.indices step chunkSize) {
            val end = minOf(i + chunkSize, samples.size)
            accept(samples.copyOfRange(i, end))
        }
        return finish().trim()
    }

    fun abort() {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            offlineEngine?.abort()
            return
        }
        stream?.release()
        stream = null
        vad?.clear()
        pendingEndpoint = false
    }

    fun unload() {
        offlineEngine?.unload()
        offlineEngine = null
        abort()
        recognizer?.release()
        recognizer = null
        vad?.release()
        vad = null
        pendingEndpoint = false
        failure = null
    }

    private fun drain() {
        val active = stream ?: return
        val engine = recognizer ?: return
        while (engine.isReady(active)) {
            engine.decode(active)
        }
    }

    companion object {
        const val DEFAULT_THREADS = 4
        private const val FEATURE_DIM = 80
        const val DEFAULT_PAUSE_SILENCE_SECONDS = 2.0f
        private const val MAX_FLUSH_DECODES = 512
        const val STREAMING_TAIL_PADDING_SECONDS = 0.8f
    }
}
