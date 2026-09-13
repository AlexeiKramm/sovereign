package fi.ak.smartvoice.cleanup

/**
 * Model specifications for Google LiteRT-LM runtime (.litertlm models).
 *
 * Direct LFS URLs are pinned and sha256-verified for reproducible offline on-device inference.
 */
data class LiteRtLmModelSpec(
    val id: String,
    val label: String,
    val repository: String,
    val fileName: String,
    val sha256: String,
    val approximateBytes: Long,
    val note: String,
    val targetSoc: String = "ANY",
    val targetHardware: String = "GPU / CPU (Universal)",
) {
    fun downloadUrl(): String = "https://huggingface.co/$repository/resolve/main/$fileName"

    fun isInstalled(context: android.content.Context): Boolean =
        LiteRtLmModelStore.isInstalled(context, this)

    fun isDeviceCompatible(deviceSoc: String): Boolean {
        if (targetSoc.equals("ANY", ignoreCase = true)) return true
        return targetSoc.equals(deviceSoc, ignoreCase = true)
    }
}

object LiteRtLmModels {

    val GRANITE_350M = LiteRtLmModelSpec(
        id = "granite-350m",
        label = "IBM Granite 4.0 350M (q8)",
        repository = "litert-community/granite-4.0-350m-litert-lm",
        fileName = "granite-4.0-350m_q8_ekv1280.litertlm",
        sha256 = "c8e9a29493f62b7c44461fb36980987c4c1454c75e95f57ba0539a8edc9dce76",
        approximateBytes = 468_209_584L,
        note = "Ultra-fast compact 350M parameter model for low-latency cleanup",
    )

    val QWEN_08B = LiteRtLmModelSpec(
        id = "qwen-0.8b",
        label = "Qwen 3.5 0.8B (int8)",
        repository = "litert-community/Qwen3.5-0.8B",
        fileName = "Qwen3.5-0.8B_int8.litertlm",
        sha256 = "684d4d34adf7176eb47f6026ff65c33d42584737254e5524a8d1ad62edc21b98",
        approximateBytes = 963_184_864L,
        note = "Modern edge multilingual 0.8B parameter model",
    )

    val GEMMA_2B_GPU = LiteRtLmModelSpec(
        id = "gemma-2b-gpu",
        label = "Gemma 4 E2B GPU",
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it-gpu.litertlm",
        sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
        approximateBytes = 2_008_432_640L,
        note = "Official Google Gemma 2B architecture optimized for mobile GPU",
    )

    val ALL: List<LiteRtLmModelSpec> = listOf(
        GRANITE_350M,
        QWEN_08B,
        GEMMA_2B_GPU,
    )

    val DEFAULT: LiteRtLmModelSpec get() = ALL.first()

    fun byId(id: String?): LiteRtLmModelSpec =
        ALL.firstOrNull { it.id == id } ?: DEFAULT
}
