package fi.ak.smartvoice.asr

import fi.ak.smartvoice.download.RemoteFile
import java.io.File

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

    /** This model's files as the shared downloader wants them, resolved to pinned URLs. */
    fun remoteFiles(): List<RemoteFile> = files.map { file ->
        RemoteFile(name = file.name, bytes = file.bytes, sha256 = file.sha256, url = downloadUrl(file))
    }
}

object AsrModels {

    const val DEFAULT_MODEL_ID = "nemotron-3.5-streaming"

    val NEMOTRON = AsrModelSpec(
        id = DEFAULT_MODEL_ID,
        label = "Nemotron 3.5 Streaming (560ms, 0.6B int8)",
        description = "Standard streaming • Balanced accuracy & speed (~682 MB)",
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

    val NEMOTRON_320MS = AsrModelSpec(
        id = "nemotron-3.5-streaming-320ms",
        label = "Nemotron 3.5 Streaming (320ms, 0.6B int8)",
        description = "Ultra-fast streaming • Lowest dictation latency (~682 MB)",
        type = AsrModelType.STREAMING_TRANSDUCER,
        repo = "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11",
        revision = "424ce58898995b713f84341f2e1492f9207a26aa",
        directoryName = "nemotron-3.5-streaming-0.6b-320ms-int8",
        encoderFileName = "encoder.int8.onnx",
        decoderFileName = "decoder.int8.onnx",
        joinerFileName = "joiner.int8.onnx",
        tokensFileName = "tokens.txt",
        files = listOf(
            AsrModelFile(
                name = "encoder.int8.onnx",
                bytes = 657_601_518L,
                sha256 = "f79c3fcc149f268b54b7d5754bdc2ba5c47c16b1fc70d15728a56f6efbf60ca5",
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

    val WHISPER_BASE = AsrModelSpec(
        id = "whisper-base",
        label = "Whisper Base (int8)",
        description = "Multilingual • Lightweight offline model (~160 MB)",
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
        description = "Multilingual • High accuracy offline model (~375 MB)",
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
        description = "Highest accuracy • Best for long dictation (~1.0 GB)",
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
        description = "Fast English dictation • High accuracy offline (~670 MB)",
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
        NEMOTRON_320MS,
        WHISPER_BASE,
        WHISPER_SMALL,
        WHISPER_TURBO,
        PARAKEET_TDT_V3,
    )

    fun byId(id: String): AsrModelSpec = CATALOG.firstOrNull { it.id == id } ?: NEMOTRON

    const val SAMPLE_RATE = 16_000
}
