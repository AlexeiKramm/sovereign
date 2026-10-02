package io.github.alexeikramm.sovereign.asr

/**
 * `AudioRecord` hands us little-endian 16-bit PCM because the server's contract wants
 * WAV s16le; the on-device runtime wants `FloatArray` in `[-1, 1)`. One conversion, one place.
 *
 * Kept as pure functions with no Android types so the byte order and the scaling can be unit
 * tested — an endianness bug here would not crash, it would produce confident nonsense, which is
 * the worst kind of failure to find on a phone.
 */
object AudioFrames {

    /**
     * Converts up to [count] bytes of little-endian s16 PCM into [out]. Returns the number of
     * samples written. A trailing odd byte is ignored: half a sample is not a sample, and
     * `AudioRecord` can in principle end a read mid-frame.
     */
    fun toFloat(samples: ByteArray, count: Int, out: FloatArray): Int {
        val usable = minOf(count, samples.size) and 0x7FFFFFFE // round down to a whole sample
        var written = 0
        var index = 0
        while (index < usable && written < out.size) {
            val low = samples[index].toInt() and 0xFF
            val high = samples[index + 1].toInt() // signed on purpose: the high byte carries the sign
            out[written] = ((high shl 8) or low).toShort() / 32768f
            written++
            index += 2
        }
        return written
    }

    /** Convenience for callers that want a fresh array per read (device path). */
    fun toFloat(samples: ByteArray, count: Int): FloatArray {
        val out = FloatArray(count / 2)
        val written = toFloat(samples, count, out)
        return if (written == out.size) out else out.copyOf(written)
    }
}
