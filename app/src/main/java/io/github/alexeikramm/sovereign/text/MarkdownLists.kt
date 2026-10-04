package io.github.alexeikramm.sovereign.text

/**
 * Deterministic Markdown list hygiene for delivered dictation, applied **once per finished capture**
 * (see `DictationPipeline.finish` and `UtteranceFinalizer.forInsertion`) — never per segment, so a
 * pause that lands mid-list-item is never cut apart.
 *
 * Why it exists: in Markdown (and therefore in Obsidian) a non-blank line immediately after a list
 * item is a *lazy continuation* of that item. The cleanup model does not reliably add the blank line
 * that ends a list, so following prose gets swallowed into the last bullet. That is the app's join,
 * not the model's writing, so it cannot be fixed by the prompt.
 */
object MarkdownLists {

    /**
     * Inserts a blank line between a list block and the first following non-blank, non-list line, so
     * that prose is not absorbed into the list. Purely additive: it never removes or reorders text,
     * and it never touches lines that are already separated.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val lines = text.split('\n')
        val out = ArrayList<String>(lines.size + 4)
        var previousWasListItem = false
        for (line in lines) {
            val isListItem = PanelText.isListItem(line)
            if (!isListItem && line.isNotBlank() && previousWasListItem) {
                out.add("")
            }
            out.add(line)
            previousWasListItem = isListItem
        }
        return out.joinToString("\n")
    }

    /** True when [text]'s last non-blank line is a Markdown list item. */
    fun endsWithListItem(text: String): Boolean {
        val lastNonBlank = text.lineSequence().lastOrNull { it.isNotBlank() } ?: return false
        return PanelText.isListItem(lastNonBlank)
    }
}
