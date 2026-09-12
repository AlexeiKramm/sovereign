package fi.ak.smartvoice.asr

import java.io.File
import java.security.MessageDigest

/**
 * The pinned on-device ASR artifact, and the only place that knows it exists.
 *
 * D-020 chose the cache-aware streaming transducer (Nemotron 3.5 ASR streaming 0.6B int8, 560 ms
 * chunks) as the local engine over an offline model fed by VAD segments. Chunk size is a
 * **download-time** property of the artifact: `560ms` in the repository name is the slowest and
 * most accurate of the five published chunkings (80/160/320/560/1120 ms), and the whole point of
 * the choice was that the *tail* after the user stops is one chunk plus the end-of-stream flush
 * instead of a whole in-flight segment.
 *
 * Why the files are pinned by size **and** sha256 rather than just downloaded: a swapping model
 * would look like a code regression (worse Finnish, different punctuation) and cost a debugging
 * session, which is exactly the failure mode DECISIONS D-007 warns about. The digests come from
 * Hugging Face's own LFS metadata for the pinned revision, so they are the publisher's, not ours.
 *
 * Nothing here writes to the repo: the files land in the app's private `filesDir`, the download
 * is the only write, and audio is never persisted (D-012).
 */
data class AsrModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

object AsrModels {

    /** `csukuangfj2/...`, the k2-fsa maintainer's artifact namespace for sherpa-onnx exports. */
    const val REPO = "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11"

    /** Immutable revision. Do not point this at `main`: the digests below are revision-specific. */
    const val REVISION = "ab43d895f5985b1bbab8b6eac8607fcdc05343f3"

    /** Directory name on the device. Also what a human sees when listing app storage. */
    const val DIRECTORY = "nemotron-3.5-streaming-0.6b-560ms-int8"

    /**
     * Order matters for the download UI: the encoder is 96 % of the payload, so it goes first and
     * the progress bar means something from the start.
     */
    val FILES = listOf(
        AsrModelFile(
            name = "encoder.int8.onnx",
            bytes = 657_601_403L,
            sha256 = "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68",
        ),
        AsrModelFile(
            name = "decoder.int8.onnx",
            bytes = 14_978_075L,
            sha256 = "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0",
        ),
        AsrModelFile(
            name = "joiner.int8.onnx",
            bytes = 9_504_438L,
            sha256 = "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce",
        ),
        AsrModelFile(
            name = "tokens.txt",
            bytes = 131_440L,
            sha256 = "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a",
        ),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.bytes }

    /** Chunk the artifact was exported at. Informational: the model owns its chunking. */
    const val CHUNK_MS = 560

    /** 16 kHz, which is what `AudioRecord` is already producing for the server tier. */
    const val SAMPLE_RATE = 16_000

    /** 560 ms at 16 kHz — used for reporting, not for buffering: the runtime takes any frame size. */
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000

    /** Model directory under the app's private files dir. */
    fun directory(filesDir: File): File = File(File(filesDir, "models"), DIRECTORY)

    fun downloadUrl(file: AsrModelFile): String =
        "https://huggingface.co/$REPO/resolve/$REVISION/${file.name}"

    /** Files that are absent or the wrong size. A size check is free; the digest is not. */
    fun incomplete(directory: File): List<AsrModelFile> =
        FILES.filter { !File(directory, it.name).let { f -> f.isFile && f.length() == it.bytes } }

    fun isInstalled(directory: File): Boolean = incomplete(directory).isEmpty()

    /**
     * Digest of a file, streamed so a 657 MB model never has to fit in memory.
     * Only called on a file whose size already matches, which is the cheap gate.
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
