package fi.ak.smartvoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Push-to-talk capture: 16 kHz mono s16le straight off `AudioRecord`, held in RAM and handed to
 * the caller as a WAV byte array on stop.
 *
 * No file is ever written — the "no audio to disk" rule (DECISIONS D-012) costs nothing at this
 * duration and keeps the privacy story simple.
 *
 * Source is `MIC`, not `VOICE_RECOGNITION`: the platform's voice-processing path applies
 * device-dependent noise suppression and AGC, and chunk 0 exists to prove the transport, not to
 * tune audio. Switching is a one-line change once there is something to compare against.
 */
class WavRecorder(private val sampleRate: Int = SAMPLE_RATE) {

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    val isRecording: Boolean get() = running

    /** Elapsed audio is derived from bytes captured, so it cannot drift from the actual payload. */
    val capturedSeconds: Double
        get() = pcm.size().toDouble() / (sampleRate * BYTES_PER_SAMPLE)

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO; the screen requests it
    fun start() {
        check(!running) { "already recording" }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        require(minBuffer > 0) { "AudioRecord.getMinBufferSize returned $minBuffer for $sampleRate Hz" }
        val bufferSize = maxOf(minBuffer, sampleRate * BYTES_PER_SAMPLE) // >= 1 s of slack

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            CHANNEL,
            ENCODING,
            bufferSize * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (state=${recorder.state})"
        }

        pcm.reset()
        record = recorder
        running = true
        recorder.startRecording()

        val buffer = ByteArray(bufferSize)
        captureThread = Thread({
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcm.write(buffer, 0, read)
                } else if (read < 0) {
                    break
                }
            }
        }, "smartvoice-capture").apply { start() }
    }

    /**
     * Stops capture and returns a complete WAV file. Returns null when nothing was captured,
     * which the screen reports rather than sending an empty upload.
     */
    fun stop(): ByteArray? {
        if (!running && pcm.size() == 0) return null
        running = false
        captureThread?.join(CAPTURE_JOIN_TIMEOUT_MS)
        captureThread = null

        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null

        val raw = pcm.toByteArray()
        pcm.reset()
        if (raw.isEmpty()) return null
        return WavWriter.build(raw, sampleRate)
    }

    /** Abandons the current capture without producing a payload (cancel must commit nothing). */
    fun cancel() {
        running = false
        captureThread?.join(CAPTURE_JOIN_TIMEOUT_MS)
        captureThread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        pcm.reset()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CAPTURE_JOIN_TIMEOUT_MS = 1_000L
    }
}
