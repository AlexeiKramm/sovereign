package io.github.alexeikramm.sovereign.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the 44-byte RIFF/WAVE header for 16-bit PCM and prepends it to raw samples.
 *
 * The server (`local-voice-stack`) passes 16 kHz mono s16le through with no ffmpeg step, so
 * this is the one format that costs nothing on either side — see docs/contract.md. Anything
 * else is normalized server-side on a best-effort basis, which is exactly what we do not want
 * to depend on.
 *
 * Kept free of Android APIs on purpose: it is the one piece of the audio path that a plain
 * JVM unit test can exercise (see WavWriterTest).
 */
object WavWriter {
    const val HEADER_SIZE = 44
    const val BITS_PER_SAMPLE = 16

    fun build(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels > 0) { "channels must be positive, was $channels" }

        val byteRate = sampleRate * channels * (BITS_PER_SAMPLE / 8)
        val blockAlign = channels * (BITS_PER_SAMPLE / 8)

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)                     // RIFF chunk size = file size - 8
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                                // PCM fmt chunk size
        header.putShort(1)                               // audio format: 1 = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        return header.array() + pcm
    }
}
