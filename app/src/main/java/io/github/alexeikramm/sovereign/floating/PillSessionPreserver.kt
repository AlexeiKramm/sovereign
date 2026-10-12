package io.github.alexeikramm.sovereign.floating

/**
 * Resolves dictated text to preserve when the expanded pill collapses or is dismissed.
 *
 * Prioritises settled delivery text (cleaned units or raw fallback), then screen preview text,
 * then any in-flight provisional text from streaming ASR.
 */
object PillSessionPreserver {

    fun resolveTextToPreserve(
        deliveryText: String,
        previewText: String,
        provisionalText: String = "",
    ): String {
        return deliveryText.trim()
            .ifBlank { previewText.trim() }
            .ifBlank { provisionalText.trim() }
    }
}
