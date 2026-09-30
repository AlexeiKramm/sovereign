package fi.ak.smartvoice.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Universal Android Audio Decoder.
 *
 * Decodes audio from any format supported by the Android platform (WAV, MP3, M4A, AAC, OGG, FLAC)
 * into normalized 16-bit 16kHz mono FloatArray samples ready for sherpa-onnx speech recognition.
 *
 * Uses streaming unboxed decoding and downmixing/resampling to ensure low memory footprint
 * even on long audio files (e.g. 30+ minutes).
 */
object AudioDecoder {

    suspend fun decodeToPcm(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        // Fast-path: try WAV header parsing directly
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val audio = WavReader.read(stream)
                return@withContext if (audio.sampleRate == 16000) {
                    audio.samples
                } else {
                    AudioResampler.resample(audio.samples, audio.sampleRate, 16000)
                }
            }
        } catch (_: Exception) {}

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
                extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("No audio track found in selected file")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Unknown audio MIME")
            val localCodec = MediaCodec.createDecoderByType(mime)
            codec = localCodec
            localCodec.configure(format, null, null, 0)
            localCodec.start()

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 16000
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT

            val pcmOutput = FloatChunkBuffer(chunkSize = 16384)
            var resampler = AudioResampler(sampleRate, 16000)

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false

            while (!isOutputEos) {
                if (!isInputEos) {
                    val inIndex = localCodec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = localCodec.getInputBuffer(inIndex)
                        if (buffer != null) {
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                localCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEos = true
                            } else {
                                localCodec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = localCodec.outputFormat
                        val newSampleRate = if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else sampleRate
                        val newChannelCount = if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else channelCount
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (newSampleRate != sampleRate) {
                            sampleRate = newSampleRate
                            resampler = AudioResampler(sampleRate, 16000)
                        }
                        channelCount = maxOf(1, newChannelCount)
                    } else if (outIndex >= 0) {
                        val outBuffer = localCodec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floatBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                val numFrames = floatBuffer.remaining() / channelCount
                                if (channelCount == 1) {
                                    while (floatBuffer.hasRemaining()) {
                                        resampler.process(floatBuffer.get(), pcmOutput)
                                    }
                                } else {
                                    for (f in 0 until numFrames) {
                                        var sum = 0.0f
                                        for (ch in 0 until channelCount) {
                                            sum += floatBuffer.get()
                                        }
                                        val monoSample = sum / channelCount
                                        resampler.process(monoSample, pcmOutput)
                                    }
                                }
                            } else {
                                // Standard 16-bit signed PCM
                                val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val numFrames = shortBuffer.remaining() / channelCount
                                if (channelCount == 1) {
                                    while (shortBuffer.hasRemaining()) {
                                        val monoSample = shortBuffer.get() / 32768.0f
                                        resampler.process(monoSample, pcmOutput)
                                    }
                                } else {
                                    for (f in 0 until numFrames) {
                                        var sum = 0
                                        for (ch in 0 until channelCount) {
                                            sum += shortBuffer.get()
                                        }
                                        val monoSample = (sum.toFloat() / channelCount) / 32768.0f
                                        resampler.process(monoSample, pcmOutput)
                                    }
                                }
                            }
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEos = true
                        }
                        localCodec.releaseOutputBuffer(outIndex, false)
                        if (isOutputEos) break
                    }
                    outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            pcmOutput.toFloatArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
