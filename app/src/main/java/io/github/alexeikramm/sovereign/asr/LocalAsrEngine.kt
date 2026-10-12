package io.github.alexeikramm.sovereign.asr

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
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
import io.github.alexeikramm.sovereign.engine.MonotonicClock
import java.io.File

data class AsrChunkResult(
    val text: String,
    val activeMs: Long,
)

data class AsrTranscriptionResult(
    val text: String,
    val activeMs: Long,
    val chunksCount: Int,
)

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
) : AsrEngine {

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

    /**
     * The streaming transcript as of the last cut — the boundary between committed and live text
     * (D-140). The recogniser is **not** reset at a cut; the tail is computed against this snapshot,
     * so the model keeps its acoustic context across pauses. `@Volatile` because `applyModel` (a
     * lifecycle call from the session) can clear it off the ASR worker.
     */
    @Volatile
    private var committedText: String = ""

    /**
     * Revision counters for the utterance in progress (D-140). A revision is the committed snapshot
     * ceasing to be a prefix of the live transcript: detected and reported, never repaired. Should be
     * 0 for a well-behaved greedy transducer.
     */
    @Volatile
    var revisionEvents: Int = 0
        private set

    @Volatile
    var revisionDivergedChars: Int = 0
        private set

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

    override fun load(): Availability {
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

    override fun startSession(language: String, hotwords: String) {
        // A fresh utterance starts a fresh boundary and clean counters (D-140) for **every** model
        // type, so a previous streaming utterance's revisions cannot leak into an offline one (the
        // counters are read unconditionally at finish).
        committedText = ""
        revisionEvents = 0
        revisionDivergedChars = 0
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

    override fun accept(samples: FloatArray, count: Int): String? {
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

        return streamingTail(countRevision = false)
    }

    /**
     * The live tail of the streaming transcript relative to the committed snapshot (D-140). The
     * recogniser is never reset between cuts, so this is the text the pipeline treats as the current
     * unit — identical in shape to what a reset used to hand back, but with the model's context kept.
     *
     * [countRevision] is set only where a boundary is settled (a cut, or the final flush) so a
     * revision is counted once rather than on every frame.
     */
    private fun streamingTail(countRevision: Boolean): String {
        val active = stream ?: return ""
        val transcript = recognizer?.getResult(active)?.text.orEmpty()
        val split = TranscriptTail.split(transcript, committedText)
        if (split.revised && countRevision) {
            revisionEvents++
            revisionDivergedChars += split.divergedChars
            Log.w(
                TAG,
                "ASR revision at the segment boundary: ${split.divergedChars} char(s) of committed text changed",
            )
        }
        if (countRevision) Log.i(TAG, "ASR seam unit: ${seamShape(split.tail)}")
        return split.tail
    }

    /**
     * Diagnostic for the **shape** of a committed unit at the seam — metadata only, never the
     * transcript text. With one continuous stream the recogniser can carry a pause's punctuation into
     * the next unit and can re-emit the last committed word. Neither is a *revision* (the transcript
     * still appends to the snapshot), so [revisionEvents] does not see them; this records, per seam:
     * the leading character's class and code point, the unit length, and whether the first word
     * duplicates the last word of the committed snapshot.
     */
    private fun seamShape(tail: String): String {
        val lead = tail.firstOrNull { !it.isWhitespace() }
        val cls = when {
            lead == null -> "EMPTY"
            lead.isLetter() -> "LETTER"
            lead.isDigit() -> "DIGIT"
            else -> "PUNCT"
        }
        val strip = charArrayOf(',', '.', '!', '?', ';', ':', '"', '\'', '—', '–')
        val firstWord = tail.trimStart().takeWhile { !it.isWhitespace() }.trim(*strip)
        val lastWord = committedText.trimEnd().substringAfterLast(' ').trim(*strip)
        val dup = firstWord.isNotEmpty() && firstWord.equals(lastWord, ignoreCase = true)
        return String.format(Locale.US, "lead=%s U+%04X len=%d dup=%b", cls, lead?.code ?: 0, tail.length, dup)
    }

    override fun result(): String {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().result()
        }
        return streamingTail(countRevision = true)
    }

    override fun resetSegment() {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            getOrCreateOfflineEngine().resetSegment()
            return
        }
        val active = stream ?: return
        // Do NOT reset the recogniser (D-140): its acoustic context must survive the pause. Advance
        // the committed boundary instead, so the next tail begins a fresh unit with the model warm.
        committedText = recognizer?.getResult(active)?.text.orEmpty()
        pendingEndpoint = false
    }

    override fun isEndpoint(): Boolean {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().isEndpoint()
        }
        val endpoint = pendingEndpoint
        pendingEndpoint = false
        return endpoint
    }

    override fun isSpeechDetected(): Boolean {
        return if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            offlineEngine?.isSpeechDetected() == true
        } else {
            vad?.isSpeechDetected() == true
        }
    }

    override fun finish(): String {
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
        val text = streamingTail(countRevision = true)
        active.release()
        stream = null
        committedText = ""
        return text
    }

    /**
     * Decodes an isolated audio segment (<= 28s) using the active model.
     * Works identically for streaming (Nemotron) and offline (Whisper, Parakeet) models.
     */
    fun decodeSegment(samples: FloatArray, language: String = ""): String {
        if (samples.isEmpty()) return ""
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            return getOrCreateOfflineEngine().decodeSegment(samples)
        }
        if (recognizer == null) {
            val loaded = load()
            if (loaded !is Availability.Loaded) return ""
        }
        val rec = recognizer ?: return ""
        val activeStream = rec.createStream()
        return try {
            val lang = if (language.isNotEmpty() && language != "auto") language else modelSpec.defaultLanguage
            if (lang.isNotEmpty() && lang != "auto") {
                activeStream.setOption("language", lang)
            }
            activeStream.acceptWaveform(samples, AsrModels.SAMPLE_RATE)
            val padding = FloatArray((AsrModels.SAMPLE_RATE * STREAMING_TAIL_PADDING_SECONDS).toInt())
            activeStream.acceptWaveform(padding, AsrModels.SAMPLE_RATE)
            activeStream.inputFinished()
            while (rec.isReady(activeStream)) {
                rec.decode(activeStream)
            }
            rec.getResult(activeStream).text.trim()
        } finally {
            activeStream.release()
        }
    }

    /**
     * Decodes an isolated audio segment (<= 28s) using the active model and returns the transcript
     * along with the active duration (from receipt of audio input to transcript output).
     */
    fun decodeSegmentTimed(samples: FloatArray, language: String = ""): AsrChunkResult {
        if (samples.isEmpty()) return AsrChunkResult("", 0L)
        val start = MonotonicClock.nowMs()
        val text = decodeSegment(samples, language)
        val durationMs = MonotonicClock.nowMs() - start
        return AsrChunkResult(text, durationMs)
    }

    /**
     * Transcribes an in-memory audio sample buffer sequentially, chunking via [AudioSegmenter],
     * and returns the transcribed text along with the sum of active chunk ASR decode durations.
     */
    fun transcribeBufferTimed(
        samples: FloatArray,
        language: String,
    ): AsrTranscriptionResult {
        if (samples.isEmpty()) return AsrTranscriptionResult("", 0L, 0)
        val chunks = AudioSegmenter.segment(samples, assetManager)
        var totalActiveMs = 0L
        val sb = StringBuilder()
        for (chunk in chunks) {
            val result = decodeSegmentTimed(chunk, language)
            totalActiveMs += result.activeMs
            if (result.text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(result.text)
            }
        }
        return AsrTranscriptionResult(sb.toString().trim(), totalActiveMs, chunks.size)
    }

    /**
     * Transcribes an in-memory audio sample buffer sequentially.
     * Chunks audio using [AudioSegmenter] (<= 28s) to respect Whisper 30s limits and Parakeet low-RAM bounds.
     */
    fun transcribeBuffer(
        samples: FloatArray,
        language: String,
        chunkSize: Int = 1600,
    ): String = transcribeBufferTimed(samples, language).text

    override fun abort() {
        if (modelSpec.type != AsrModelType.STREAMING_TRANSDUCER) {
            offlineEngine?.abort()
            return
        }
        stream?.release()
        stream = null
        vad?.clear()
        pendingEndpoint = false
        committedText = ""
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
        private const val TAG = "LocalAsrEngine"
        const val DEFAULT_THREADS = 4
        private const val FEATURE_DIM = 80
        const val DEFAULT_PAUSE_SILENCE_SECONDS = 2.0f
        private const val MAX_FLUSH_DECODES = 512
        const val STREAMING_TAIL_PADDING_SECONDS = 0.8f
    }
}
