package fi.ak.smartvoice.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Universal Android Audio Decoder.
 *
 * Decodes audio from any format supported by the Android platform (WAV, MP3, M4A, AAC, OGG, FLAC)
 * into normalized 16-bit 16kHz mono FloatArray samples ready for sherpa-onnx speech recognition.
 */
object AudioDecoder {

    suspend fun decodeToPcm(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        // Fast-path: try WAV header parsing directly
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val audio = WavReader.read(stream)
                if (audio.sampleRate == 16000 && audio.channels == 1) {
                    return@withContext audio.samples
                }
            }
        } catch (_: Exception) {}

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
                extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("No audio track found in selected file")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Unknown audio MIME")
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 16000
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val pcmBytes = ArrayList<Byte>(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false

            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.get(chunk)
                        for (b in chunk) pcmBytes.add(b)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val rawBytes = pcmBytes.toByteArray()
            val shortCount = rawBytes.size / 2
            val shortBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shortCount / channelCount
            val monoSamples = FloatArray(frames)
            for (i in 0 until frames) {
                var sum = 0f
                for (ch in 0 until channelCount) {
                    sum += shortBuffer.get(i * channelCount + ch) / 32768.0f
                }
                monoSamples[i] = sum / channelCount
            }

            if (sampleRate == 16000 || sampleRate <= 0) {
                monoSamples
            } else {
                resample(monoSamples, sampleRate, 16000)
            }
        } catch (e: Exception) {
            runCatching { extractor.release() }
            throw e
        }
    }

    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, input.size - 1)
            output[i] = input[srcIdx]
        }
        return output
    }
}
