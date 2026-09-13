package fi.ak.smartvoice.asr

import java.io.File
import java.security.MessageDigest

enum class AsrModelType {
    STREAMING_TRANSDUCER,
    OFFLINE_WHISPER,
    OFFLINE_TRANSDUCER,
    OFFLINE_CTC,
}

data class AsrModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

data class AsrModelSpec(
    val id: String,
    val label: String,
    val description: String,
    val type: AsrModelType,
    val repo: String,
    val revision: String,
    val directoryName: String,
    val files: List<AsrModelFile>,
    val defaultLanguage: String = "auto",
    val encoderFileName: String = "",
    val decoderFileName: String = "",
    val joinerFileName: String = "",
    val modelFileName: String = "",
    val tokensFileName: String = "tokens.txt",
) {
    val totalBytes: Long = files.sumOf { it.bytes }

    fun directory(filesDir: File): File = File(File(filesDir, "models"), directoryName)

    fun downloadUrl(file: AsrModelFile): String =
        "https://huggingface.co/$repo/resolve/$revision/${file.name}"

    fun incomplete(directory: File): List<AsrModelFile> =
        files.filter { !File(directory, it.name).let { f -> f.isFile && f.length() == it.bytes } }

    fun isInstalled(directory: File): Boolean = incomplete(directory).isEmpty()
}

object AsrModels {

    const val DEFAULT_MODEL_ID = "nemotron-3.5-streaming"

    val NEMOTRON = AsrModelSpec(
        id = DEFAULT_MODEL_ID,
        label = "Nemotron 3.5 Streaming (0.6B int8)",
        description = "560 ms chunks • ~682 MB, 4 files • Streaming transducer",
        type = AsrModelType.STREAMING_TRANSDUCER,
        repo = "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        revision = "ab43d895f5985b1bbab8b6eac8607fcdc05343f3",
        directoryName = "nemotron-3.5-streaming-0.6b-560ms-int8",
        encoderFileName = "encoder.int8.onnx",
        decoderFileName = "decoder.int8.onnx",
        joinerFileName = "joiner.int8.onnx",
        tokensFileName = "tokens.txt",
        files = listOf(
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
        ),
    )

    val WHISPER_TINY = AsrModelSpec(
        id = "whisper-tiny",
        label = "Whisper Tiny (int8)",
        description = "Lightweight multilingual offline model • ~103 MB",
        type = AsrModelType.OFFLINE_WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-tiny",
        revision = "65176e2deb88badc814a94058666cadccc29b61c",
        directoryName = "sherpa-onnx-whisper-tiny",
        encoderFileName = "tiny-encoder.int8.onnx",
        decoderFileName = "tiny-decoder.int8.onnx",
        tokensFileName = "tiny-tokens.txt",
        files = listOf(
            AsrModelFile(
                name = "tiny-decoder.int8.onnx",
                bytes = 89_855_401L,
                sha256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            ),
            AsrModelFile(
                name = "tiny-encoder.int8.onnx",
                bytes = 12_937_772L,
                sha256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            ),
            AsrModelFile(
                name = "tiny-tokens.txt",
                bytes = 816_730L,
                sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        ),
    )

    val WHISPER_BASE = AsrModelSpec(
        id = "whisper-base",
        label = "Whisper Base (int8)",
        description = "Balanced multilingual offline model • ~160 MB",
        type = AsrModelType.OFFLINE_WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-base",
        revision = "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
        directoryName = "sherpa-onnx-whisper-base",
        encoderFileName = "base-encoder.int8.onnx",
        decoderFileName = "base-decoder.int8.onnx",
        tokensFileName = "base-tokens.txt",
        files = listOf(
            AsrModelFile(
                name = "base-decoder.int8.onnx",
                bytes = 130_672_026L,
                sha256 = "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
            ),
            AsrModelFile(
                name = "base-encoder.int8.onnx",
                bytes = 29_120_534L,
                sha256 = "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
            ),
            AsrModelFile(
                name = "base-tokens.txt",
                bytes = 816_730L,
                sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        ),
    )

    val WHISPER_SMALL = AsrModelSpec(
        id = "whisper-small",
        label = "Whisper Small (int8)",
        description = "High accuracy multilingual offline model • ~375 MB",
        type = AsrModelType.OFFLINE_WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-small",
        revision = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
        directoryName = "sherpa-onnx-whisper-small",
        encoderFileName = "small-encoder.int8.onnx",
        decoderFileName = "small-decoder.int8.onnx",
        tokensFileName = "small-tokens.txt",
        files = listOf(
            AsrModelFile(
                name = "small-decoder.int8.onnx",
                bytes = 262_226_114L,
                sha256 = "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            ),
            AsrModelFile(
                name = "small-encoder.int8.onnx",
                bytes = 112_442_483L,
                sha256 = "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            ),
            AsrModelFile(
                name = "small-tokens.txt",
                bytes = 816_730L,
                sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        ),
    )

    val WHISPER_TURBO = AsrModelSpec(
        id = "whisper-turbo",
        label = "Whisper Large-v3-Turbo (int8)",
        description = "State-of-the-art multilingual accuracy & speed • ~1.03 GB",
        type = AsrModelType.OFFLINE_WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-turbo",
        revision = "2ca6ff69fc878651b770880507669577ac41c2ff",
        directoryName = "sherpa-onnx-whisper-turbo",
        encoderFileName = "turbo-encoder.int8.onnx",
        decoderFileName = "turbo-decoder.int8.onnx",
        tokensFileName = "turbo-tokens.txt",
        files = listOf(
            AsrModelFile(
                name = "turbo-decoder.int8.onnx",
                bytes = 361_080_764L,
                sha256 = "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547",
            ),
            AsrModelFile(
                name = "turbo-encoder.int8.onnx",
                bytes = 674_716_297L,
                sha256 = "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b",
            ),
            AsrModelFile(
                name = "turbo-tokens.txt",
                bytes = 816_730L,
                sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        ),
    )

    val PARAKEET_TDT_V3 = AsrModelSpec(
        id = "parakeet-tdt-v3",
        label = "Parakeet TDT 0.6B v3 (int8)",
        description = "Latest NVIDIA NeMo Parakeet v3 TDT Transducer • ~670 MB",
        type = AsrModelType.OFFLINE_TRANSDUCER,
        repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        revision = "2bda32ec70b097a55adaa07d9a7173915b43cc78",
        directoryName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        encoderFileName = "encoder.int8.onnx",
        decoderFileName = "decoder.int8.onnx",
        joinerFileName = "joiner.int8.onnx",
        tokensFileName = "tokens.txt",
        defaultLanguage = "en",
        files = listOf(
            AsrModelFile(
                name = "encoder.int8.onnx",
                bytes = 652_184_281L,
                sha256 = "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
            ),
            AsrModelFile(
                name = "decoder.int8.onnx",
                bytes = 11_845_275L,
                sha256 = "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e",
            ),
            AsrModelFile(
                name = "joiner.int8.onnx",
                bytes = 6_355_277L,
                sha256 = "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3",
            ),
            AsrModelFile(
                name = "tokens.txt",
                bytes = 93_939L,
                sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ),
        ),
    )

    val CATALOG: List<AsrModelSpec> = listOf(
        NEMOTRON,
        WHISPER_TINY,
        WHISPER_BASE,
        WHISPER_SMALL,
        WHISPER_TURBO,
        PARAKEET_TDT_V3,
    )

    fun byId(id: String): AsrModelSpec = CATALOG.firstOrNull { it.id == id } ?: NEMOTRON

    /** Backward-compatibility aliases for Nemotron 3.5 default */
    val DEFAULT_MODEL: AsrModelSpec = NEMOTRON
    val REPO: String get() = NEMOTRON.repo
    val REVISION: String get() = NEMOTRON.revision
    val DIRECTORY: String get() = NEMOTRON.directoryName
    val FILES: List<AsrModelFile> get() = NEMOTRON.files
    val TOTAL_BYTES: Long get() = NEMOTRON.totalBytes
    const val CHUNK_MS = 560
    const val SAMPLE_RATE = 16_000
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000

    fun directory(filesDir: File): File = NEMOTRON.directory(filesDir)

    fun downloadUrl(file: AsrModelFile): String = NEMOTRON.downloadUrl(file)

    fun incomplete(directory: File): List<AsrModelFile> = NEMOTRON.incomplete(directory)

    fun isInstalled(directory: File): Boolean = NEMOTRON.isInstalled(directory)

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
