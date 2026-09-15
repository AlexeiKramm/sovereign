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
    val supportsSpeculativeDecoding: Boolean = true,
    val supportsGpu: Boolean = true,
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

    val GEMMA_4_E2B = LiteRtLmModelSpec(
        id = "gemma-4-e2b",
        label = "Gemma 4 E2B (it)",
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        approximateBytes = 2_588_147_712L,
        note = "Fast on-device cleanup. Optimized for low latency and battery efficiency.",
        targetHardware = "GPU / CPU",
    )

    val GEMMA_4_E4B = LiteRtLmModelSpec(
        id = "gemma-4-e4b",
        label = "Gemma 4 E4B (it)",
        repository = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        approximateBytes = 3_659_530_240L,
        note = "Higher quality rewriting. Best formatting and error correction.",
        targetHardware = "GPU / CPU",
    )

    val QWEN_3_5_0_8B = LiteRtLmModelSpec(
        id = "qwen-3.5-0.8b",
        label = "Qwen 3.5 0.8B",
        repository = "litert-community/Qwen3.5-0.8B",
        fileName = "Qwen3.5-0.8B_int8.litertlm",
        sha256 = "684d4d34adf7176eb47f6026ff65c33d42584737254e5524a8d1ad62edc21b98",
        approximateBytes = 963_184_864L,
        note = "Text-only 0.8B model (dynamic int8, 2048 context). Low memory CPU inference.",
        targetHardware = "CPU (Low memory)",
        supportsSpeculativeDecoding = false,
        supportsGpu = false,
    )

    val ALL: List<LiteRtLmModelSpec> = listOf(
        GEMMA_4_E2B,
        GEMMA_4_E4B,
        QWEN_3_5_0_8B,
    )

    val DEFAULT: LiteRtLmModelSpec get() = ALL.first()

    fun byId(id: String?): LiteRtLmModelSpec =
        ALL.firstOrNull { it.id == id } ?: DEFAULT
}
