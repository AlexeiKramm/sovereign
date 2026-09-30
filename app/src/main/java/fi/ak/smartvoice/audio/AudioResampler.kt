package fi.ak.smartvoice.audio

fun interface FloatConsumer {
    fun accept(sample: Float)
}

/**
 * Universal PCM Audio Resampler.
 *
 * Resamples single-channel audio from any [srcRate] to [dstRate] (default 16,000 Hz) using
 * streaming linear interpolation.
 *
 * Keeps memory footprint constant by operating sample-by-sample without buffering whole audio streams.
 */
class AudioResampler(
    val srcRate: Int,
    val dstRate: Int = 16_000,
) {
    private val step = if (srcRate > 0 && dstRate > 0) srcRate.toDouble() / dstRate else 1.0
    private var srcTime = 0.0
    private var lastInputIndex = -1L
    private var prevSample = 0.0f
    private var hasPrev = false

    /**
     * Consumes one input [sample] at [srcRate] and emits zero or more interpolated samples
     * at [dstRate] to [consumer].
     */
    fun process(sample: Float, consumer: FloatConsumer) {
        if (srcRate == dstRate || srcRate <= 0) {
            consumer.accept(sample)
            return
        }

        if (!hasPrev) {
            prevSample = sample
            hasPrev = true
            lastInputIndex = 0L
            // Emit the sample at t=0
            consumer.accept(sample)
            srcTime += step
            return
        }

        val currIndex = lastInputIndex + 1
        // Epsilon of 1e-9 prevents floating point precision jitter from delaying exact step boundaries
        while (srcTime <= currIndex + 1e-9) {
            val alpha = (srcTime - lastInputIndex).toFloat().coerceIn(0.0f, 1.0f)
            val interpolated = prevSample + alpha * (sample - prevSample)
            consumer.accept(interpolated)
            srcTime += step
        }

        prevSample = sample
        lastInputIndex = currIndex
    }

    companion object {
        /**
         * Helper to resample an entire [FloatArray] in memory.
         */
        fun resample(samples: FloatArray, srcRate: Int, dstRate: Int = 16_000): FloatArray {
            if (samples.isEmpty() || srcRate == dstRate || srcRate <= 0) return samples
            val out = FloatChunkBuffer(chunkSize = 16384)
            val resampler = AudioResampler(srcRate, dstRate)
            for (s in samples) {
                resampler.process(s, out)
            }
            return out.toFloatArray()
        }
    }
}

/**
 * Unboxed primitive float buffer organized in chunks to prevent large contiguous heap allocations
 * and avoid GC overhead when accumulating long audio recordings.
 */
class FloatChunkBuffer(private val chunkSize: Int = 16384) : FloatConsumer {
    private val chunks = ArrayList<FloatArray>()
    private var current = FloatArray(chunkSize)
    private var currentPos = 0
    var totalSamples = 0
        private set

    override fun accept(sample: Float) {
        if (currentPos == chunkSize) {
            chunks.add(current)
            current = FloatArray(chunkSize)
            currentPos = 0
        }
        current[currentPos++] = sample
        totalSamples++
    }

    fun add(sample: Float) = accept(sample)

    fun toFloatArray(): FloatArray {
        val result = FloatArray(totalSamples)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        if (currentPos > 0) {
            System.arraycopy(current, 0, result, offset, currentPos)
        }
        return result
    }
}
