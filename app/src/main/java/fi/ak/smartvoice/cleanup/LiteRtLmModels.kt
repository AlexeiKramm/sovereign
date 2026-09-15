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
        repository = "GabrieleConte/Qwen3.5-0.8B-LiteRT",
        fileName = "qwen35_mm_q8_ekv2048.litertlm",
        sha256 = "92999fe4a9242c983e99892d6e57f368e8cd7a4534bc9a383a9551155b7f70a5",
        approximateBytes = 1_159_757_824L,
        note = "Ultra-compact 0.8B model (dynamic int8, 2048 context). Fast inference with low memory footprint.",
        targetHardware = "GPU / CPU",
        supportsSpeculativeDecoding = false,
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
