package io.github.alexeikramm.sovereign.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Segments an audio sample buffer into bounded chunks (<= 28s) to satisfy:
 * 1. Whisper Base fixed 30-second mel spectrogram window limit.
 * 2. Parakeet TDT conformer attention memory bounds (low RAM).
 * 3. Gemma 4 E2B context protection in post-ASR cleanup.
 *
 * Slices are made on natural speech pauses (silence >= 400ms) detected by Silero VAD,
 * preventing words or phonemes from being severed at chunk boundaries.
 * If continuous speech exceeds 28s without a pause, a hard boundary is cut at 28s,
 * allowing [io.github.alexeikramm.sovereign.cleanup.SeamHealer] to repair cross-chunk grammar/words.
 */
object AudioSegmenter {

    const val DEFAULT_SAMPLE_RATE = 16_000
    const val MIN_CHUNK_SECONDS = 12.0f
    const val MAX_CHUNK_SECONDS = 28.0f
    const val MIN_SILENCE_SECONDS = 0.4f
    private const val FRAME_SIZE_SAMPLES = 1600 // 100ms at 16kHz
    private const val INTER_SEGMENT_GAP_SECONDS = 0.15f

    /**
     * Slices [samples] into a list of [FloatArray] chunks, each guaranteed to be
     * <= [maxChunkSeconds] in duration.
     */
    fun segment(
        samples: FloatArray,
        assetManager: AssetManager?,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        minChunkSeconds: Float = MIN_CHUNK_SECONDS,
        maxChunkSeconds: Float = MAX_CHUNK_SECONDS,
        minSilenceSeconds: Float = MIN_SILENCE_SECONDS,
    ): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()

        val maxChunkSamples = (maxChunkSeconds * sampleRate).toInt()
        val minChunkSamples = (minChunkSeconds * sampleRate).toInt()

        // Fast path: if entire audio file is already under the max chunk limit, no slicing needed
        if (samples.size <= maxChunkSamples) {
            return listOf(samples)
        }

        val rawSpeechSegments = if (assetManager != null) {
            extractVadSpeechSegments(
                samples = samples,
                assetManager = assetManager,
                sampleRate = sampleRate,
                minSilenceSeconds = minSilenceSeconds,
                maxSpeechSeconds = maxChunkSeconds,
            )
        } else null

        // Fallback: If VAD is unavailable or detected no speech, slice uniformly into maxChunkSamples blocks
        if (rawSpeechSegments.isNullOrEmpty()) {
            return sliceUniform(samples, maxChunkSamples)
        }

        return mergeIntoBoundedChunks(
            segments = rawSpeechSegments,
            minSamples = minChunkSamples,
            maxSamples = maxChunkSamples,
            gapSamples = (sampleRate * INTER_SEGMENT_GAP_SECONDS).toInt(),
        )
    }

    private fun extractVadSpeechSegments(
        samples: FloatArray,
        assetManager: AssetManager,
        sampleRate: Int,
        minSilenceSeconds: Float,
        maxSpeechSeconds: Float,
    ): List<FloatArray>? = runCatching {
        val sileroConfig = SileroVadModelConfig(
            model = "silero_vad.onnx",
            threshold = 0.5f,
            minSilenceDuration = minSilenceSeconds,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = maxSpeechSeconds,
        )
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
        )
        val vad = Vad(assetManager = assetManager, config = vadConfig)
        val extracted = mutableListOf<FloatArray>()
        try {
            for (offset in samples.indices step FRAME_SIZE_SAMPLES) {
                val end = minOf(offset + FRAME_SIZE_SAMPLES, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))
                while (!vad.empty()) {
                    val seg = vad.front().samples
                    vad.pop()
                    if (seg.isNotEmpty()) {
                        extracted.add(seg)
                    }
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front().samples
                vad.pop()
                if (seg.isNotEmpty()) {
                    extracted.add(seg)
                }
            }
        } finally {
            vad.release()
        }
        extracted
    }.getOrNull()

    /**
     * Merges small speech segments into cohesive chunks between [minSamples] and [maxSamples],
     * separated by a short natural silence gap.
     */
    fun mergeIntoBoundedChunks(
        segments: List<FloatArray>,
        minSamples: Int,
        maxSamples: Int,
        gapSamples: Int,
    ): List<FloatArray> {
        val chunks = mutableListOf<FloatArray>()
        var current = FloatArray(0)

        for (seg in segments) {
            // Guard against oversized segments from VAD
            val safeSeg = if (seg.size > maxSamples) seg.copyOf(maxSamples) else seg

            val gapSize = if (current.isEmpty()) 0 else gapSamples
            val wouldExceed = current.isNotEmpty() && (current.size + gapSize + safeSeg.size > maxSamples)

            if (wouldExceed) {
                chunks.add(current)
                current = safeSeg
            } else {
                current = if (current.isEmpty()) {
                    safeSeg
                } else {
                    val combined = FloatArray(current.size + gapSize + safeSeg.size)
                    System.arraycopy(current, 0, combined, 0, current.size)
                    // gap remains zeroed (silence)
                    System.arraycopy(safeSeg, 0, combined, current.size + gapSize, safeSeg.size)
                    combined
                }
            }

            if (current.size >= minSamples) {
                chunks.add(current)
                current = FloatArray(0)
            }
        }

        if (current.isNotEmpty()) {
            chunks.add(current)
        }

        return chunks
    }

    /**
     * Uniformly slices an audio buffer into chunks of at most [chunkSize] samples.
     */
    fun sliceUniform(samples: FloatArray, chunkSize: Int): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val chunks = ArrayList<FloatArray>((samples.size + chunkSize - 1) / chunkSize)
        for (i in samples.indices step chunkSize) {
            val end = minOf(i + chunkSize, samples.size)
            chunks.add(samples.copyOfRange(i, end))
        }
        return chunks
    }
}
