package io.github.alexeikramm.sovereign.floating

/**
 * Resolves dictated text to preserve when the expanded pill collapses or is dismissed.
 *
 * Combines settled delivery text (cleaned units or raw fallback) with any in-flight provisional
 * text from streaming ASR ("gray text" that hasn't undergone cleanup yet), matching what was
 * visible on screen before collapse.
 */
object PillSessionPreserver {

    fun resolveTextToPreserve(
        deliveryText: String,
        previewText: String,
        provisionalText: String = "",
    ): String {
        val settled = deliveryText.trim().ifBlank { previewText.trim() }
        val provisional = provisionalText.trim()

        return when {
            settled.isNotBlank() && provisional.isNotBlank() -> {
                val separator = if (settled.lastOrNull()?.isWhitespace() == true) "" else " "
                "$settled$separator$provisional"
            }
            settled.isNotBlank() -> settled
            provisional.isNotBlank() -> provisional
            else -> ""
        }
    }
}
