package io.github.alexeikramm.sovereign.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses standard 16-bit PCM WAV audio files into normalized [FloatArray] samples in the range [-1.0f, 1.0f].
 *
 * This matches the sample format expected by sherpa-onnx [com.k2fsa.sherpa.onnx.OnlineStream.acceptWaveform].
 */
object WavReader {

    data class WavAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int,
        val durationSeconds: Float,
    )

    fun read(inputStream: InputStream): WavAudio {
        val bytes = inputStream.readBytes()
        return read(bytes)
    }

    fun read(bytes: ByteArray): WavAudio {
        require(bytes.size >= 44) { "WAV data too small: ${bytes.size} bytes" }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        require(riff == "RIFF") { "Invalid RIFF header: $riff" }

        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        require(wave == "WAVE") { "Invalid WAVE header: $wave" }

        var offset = 12
        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            offset += 8

            when (chunkId) {
                "fmt " -> {
                    val audioFormat = buffer.getShort(offset)
                    require(audioFormat.toInt() == 1) { "Only uncompressed PCM format is supported (was $audioFormat)" }
                    channels = buffer.getShort(offset + 2).toInt()
                    sampleRate = buffer.getInt(offset + 4)
                    bitsPerSample = buffer.getShort(offset + 14).toInt()
                    offset += chunkSize
                }
                "data" -> {
                    dataOffset = offset
                    dataSize = chunkSize
                    offset += chunkSize
                }
                else -> {
                    offset += chunkSize
                }
            }
        }

        require(dataOffset != -1) { "No 'data' chunk found in WAV" }
        require(bitsPerSample == 16) { "Only 16-bit PCM is supported, was $bitsPerSample-bit" }

        val actualDataSize = minOf(dataSize, bytes.size - dataOffset)
        val numSamplesTotal = actualDataSize / 2
        val numFrames = numSamplesTotal / channels

        val samples = FloatArray(numFrames)
        var sampleIdx = 0

        if (channels == 1) {
            for (i in 0 until numFrames) {
                val shortVal = buffer.getShort(dataOffset + i * 2)
                samples[sampleIdx++] = shortVal / 32768.0f
            }
        } else {
            // Stereo or multichannel: downmix to mono
            for (i in 0 until numFrames) {
                var sum = 0f
                for (ch in 0 until channels) {
                    sum += buffer.getShort(dataOffset + (i * channels + ch) * 2) / 32768.0f
                }
                samples[sampleIdx++] = sum / channels
            }
        }

        val duration = if (sampleRate > 0) numFrames.toFloat() / sampleRate else 0f
        return WavAudio(
            samples = samples,
            sampleRate = sampleRate,
            channels = channels,
            durationSeconds = duration,
        )
    }
}
