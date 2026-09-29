package fi.ak.smartvoice.asr

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Offline ASR engine using sherpa-onnx OfflineRecognizer coupled with Silero VAD.
 *
 * Audio frames are buffered by Silero VAD until a natural pause (bounded by [pauseSilenceSeconds])
 * is detected. The completed speech segment is then decoded in one pass by the offline recognizer
 * (supporting Whisper models and Parakeet Conformer CTC).
 *
 * For Whisper models, previous decoded units and hotwords can be fed into the decoder stream via
 * prompt/context options, mirroring faster-whisper's initial_prompt context capability.
 */
class OfflineVadAsrEngine(
    private val modelDirectory: File,
    private val spec: AsrModelSpec,
    private val assetManager: AssetManager? = null,
    private val numThreads: Int = LocalAsrEngine.DEFAULT_THREADS,
    pauseSilenceSeconds: Float = LocalAsrEngine.DEFAULT_PAUSE_SILENCE_SECONDS,
) {

    @Volatile
    var pauseSilenceSeconds: Float = pauseSilenceSeconds
        private set

    fun applyPauseSilence(seconds: Float) {
        if (seconds == pauseSilenceSeconds) return
        pauseSilenceSeconds = seconds
        unload()
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var failure: String? = null

    @Volatile
    var lastLoadMillis: Long = 0L
        private set

    private var activeLanguage: String = "auto"
    private var activeHotwords: String = ""
    private var pendingUnit: String? = null
    private var previousContextText: String = ""
    private var sessionActive: Boolean = false

    val isSessionActive: Boolean get() = sessionActive

    fun availability(): LocalAsrEngine.Availability = when {
        failure != null -> LocalAsrEngine.Availability.Unavailable(failure!!)
        recognizer != null -> LocalAsrEngine.Availability.Loaded
        !spec.isInstalled(modelDirectory) -> LocalAsrEngine.Availability.NotInstalled
        else -> LocalAsrEngine.Availability.NotLoaded
    }

    fun load(): LocalAsrEngine.Availability {
        recognizer?.let { return LocalAsrEngine.Availability.Loaded }
        failure?.let { return LocalAsrEngine.Availability.Unavailable(it) }
        if (!spec.isInstalled(modelDirectory)) return LocalAsrEngine.Availability.NotInstalled

        return try {
            val startedAt = SystemClock.elapsedRealtime()

            val modelConfig = when (spec.type) {
                AsrModelType.OFFLINE_WHISPER -> {
                    val whisper = OfflineWhisperModelConfig(
                        encoder = File(modelDirectory, spec.encoderFileName).absolutePath,
                        decoder = File(modelDirectory, spec.decoderFileName).absolutePath,
                        language = if (spec.defaultLanguage == "auto") "" else spec.defaultLanguage,
                        task = "transcribe",
                        tailPaddings = -1,
                    )
                    OfflineModelConfig(
                        whisper = whisper,
                        tokens = File(modelDirectory, spec.tokensFileName).absolutePath,
                        numThreads = numThreads,
                        provider = "cpu",
                        modelType = "whisper",
                    )
                }
                AsrModelType.OFFLINE_TRANSDUCER -> {
                    val transducer = OfflineTransducerModelConfig(
                        encoder = File(modelDirectory, spec.encoderFileName).absolutePath,
                        decoder = File(modelDirectory, spec.decoderFileName).absolutePath,
                        joiner = File(modelDirectory, spec.joinerFileName).absolutePath,
                    )
                    OfflineModelConfig(
                        transducer = transducer,
                        tokens = File(modelDirectory, spec.tokensFileName).absolutePath,
                        numThreads = numThreads,
                        provider = "cpu",
                        modelType = "nemo_transducer",
                    )
                }
                AsrModelType.OFFLINE_CTC -> {
                    val nemoCtc = OfflineNemoEncDecCtcModelConfig(
                        model = File(modelDirectory, spec.modelFileName).absolutePath,
                    )
                    OfflineModelConfig(
                        nemo = nemoCtc,
                        tokens = File(modelDirectory, spec.tokensFileName).absolutePath,
                        numThreads = numThreads,
                        provider = "cpu",
                        modelType = "nemo_ctc",
                    )
                }
                AsrModelType.STREAMING_TRANSDUCER -> {
                    error("Streaming transducer model ${spec.id} should use LocalAsrEngine's streaming driver")
                }
            }

            val featConfig = FeatureConfig(sampleRate = AsrModels.SAMPLE_RATE, featureDim = 80)
            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
            )
            recognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)

            // Initialise Silero VAD from bundled asset or filesystem
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

            lastLoadMillis = SystemClock.elapsedRealtime() - startedAt
            LocalAsrEngine.Availability.Loaded
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.java.simpleName
            failure = reason
            LocalAsrEngine.Availability.Unavailable(reason)
        }
    }

    private var speechSamplesSinceCut: Long = 0L

    fun startSession(language: String, hotwords: String = "") {
        val active = load()
        check(active is LocalAsrEngine.Availability.Loaded) { "offline engine not loaded: $active" }
        abort()

        activeLanguage = language
        activeHotwords = hotwords
        pendingUnit = null
        previousContextText = ""
        speechSamplesSinceCut = 0L
        vad?.reset()
        sessionActive = true
    }

    fun accept(samples: FloatArray, count: Int = samples.size): String? {
        if (!sessionActive) return null
        val v = vad ?: return null
        val effectiveSamples = if (count == samples.size) samples else samples.copyOf(count)

        v.acceptWaveform(effectiveSamples)

        // Track accumulated speech samples in the current active segment
        if (v.isSpeechDetected()) {
            speechSamplesSinceCut += effectiveSamples.size
        } else if (v.empty()) {
            // In quiet/pause state with no pending segments, reset speech counter
            speechSamplesSinceCut = 0L
        }

        // 1. Continuous speech chunking: If speech reaches ~29s without a pause, forcibly
        // cut the segment so Whisper's 30s mel spectrogram window is never exceeded and audio is never lost.
        if (speechSamplesSinceCut >= MAX_CHUNK_SAMPLES) {
            v.flush()
            if (!v.empty()) {
                val segment = v.front()
                v.pop()
                speechSamplesSinceCut = 0L
                val text = decodeSegment(segment.samples)
                if (text.isNotBlank()) {
                    pendingUnit = text
                    return text
                }
            }
            speechSamplesSinceCut = 0L
        }

        // 2. Check if Silero VAD has detected a natural pause / endpoint boundary
        if (!v.empty()) {
            val segment = v.front()
            v.pop()
            speechSamplesSinceCut = 0L
            val text = decodeSegment(segment.samples)
            if (text.isNotBlank()) {
                pendingUnit = text
                return text
            }
        }
        return null
    }

    fun decodeSegment(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        if (recognizer == null) {
            val loaded = load()
            if (loaded !is LocalAsrEngine.Availability.Loaded) return ""
        }
        val rec = recognizer ?: return ""

        // Strictly clamp to maximum 30.0s for Whisper safety
        val safeSamples = if (spec.type == AsrModelType.OFFLINE_WHISPER && samples.size > MAX_WHISPER_SAMPLES) {
            samples.copyOf(MAX_WHISPER_SAMPLES)
        } else {
            samples
        }

        val stream = if (activeHotwords.isNotEmpty()) {
            try { rec.createStream(activeHotwords) } catch (_: Throwable) { rec.createStream() }
        } else {
            rec.createStream()
        }

        try {
            if (spec.type == AsrModelType.OFFLINE_WHISPER) {
                val lang = when (activeLanguage.lowercase()) {
                    "auto", "" -> ""
                    "fi-fi" -> "fi"
                    "en-us", "en-gb" -> "en"
                    else -> activeLanguage
                }
                if (lang.isNotEmpty()) {
                    stream.setOption("language", lang)
                }
                stream.setOption("task", "transcribe")

                // Inject preceding context prompt to guide Whisper decoder across segment boundaries
                val contextPrompt = buildWhisperPrompt()
                if (contextPrompt.isNotEmpty()) {
                    stream.setOption("prompt", contextPrompt)
                }
            }

            stream.acceptWaveform(safeSamples, AsrModels.SAMPLE_RATE)
            rec.decode(stream)
            val resultText = rec.getResult(stream).text.trim()
            if (resultText.isNotEmpty()) {
                previousContextText = if (previousContextText.isEmpty()) {
                    resultText
                } else {
                    "$previousContextText $resultText"
                }
            }
            return resultText
        } finally {
            stream.release()
        }
    }

    private fun buildWhisperPrompt(): String {
        val parts = mutableListOf<String>()
        if (activeHotwords.isNotEmpty()) {
            parts.add(activeHotwords)
        }
        if (previousContextText.isNotEmpty()) {
            parts.add(previousContextText.takeLast(250))
        }
        return parts.joinToString(". ")
    }

    fun isEndpoint(): Boolean = pendingUnit != null

    fun isSpeechDetected(): Boolean = vad?.isSpeechDetected() ?: false

    fun result(): String = pendingUnit.orEmpty()

    fun resetSegment() {
        pendingUnit = null
    }

    fun finish(): String {
        val v = vad
        val rec = recognizer
        if (v != null && rec != null) {
            v.flush()
            if (!v.empty()) {
                val segment = v.front()
                v.pop()
                val tailText = decodeSegment(segment.samples)
                if (tailText.isNotBlank()) {
                    pendingUnit = if (pendingUnit != null) "${pendingUnit} $tailText" else tailText
                }
            }
        }
        speechSamplesSinceCut = 0L
        sessionActive = false
        val finalUnit = pendingUnit.orEmpty()
        pendingUnit = null
        return finalUnit
    }

    fun abort() {
        pendingUnit = null
        speechSamplesSinceCut = 0L
        vad?.clear()
        sessionActive = false
    }

    fun unload() {
        abort()
        vad?.release()
        vad = null
        recognizer?.release()
        recognizer = null
        failure = null
    }

    companion object {
        /** 29.0s safe cut threshold for continuous speech (464,000 samples @ 16 kHz) */
        const val MAX_CHUNK_SAMPLES = 29 * AsrModels.SAMPLE_RATE

        /** Hard 30.0s window cap for Whisper log-mel spectrogram safety */
        const val MAX_WHISPER_SAMPLES = 30 * AsrModels.SAMPLE_RATE
    }
}
