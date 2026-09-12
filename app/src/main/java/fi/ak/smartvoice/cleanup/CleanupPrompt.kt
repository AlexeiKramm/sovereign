package fi.ak.smartvoice.cleanup

import com.geniex.sdk.bean.ChatMessage

/**
 * The cleanup prompt, as a pure function of (unit, context, dictionary) — so its shape is pinned by
 * tests on this machine rather than discovered on a phone, and so D-015's ordering rule has one
 * implementation:
 *
 *   stable prefix first → system core, then dictionary → then the growing part: cleaned context,
 *   then the unit to clean.
 *
 * The prefix is stable on purpose: it is the only part that could ever be cached across units
 * (D-014 mode 1), and the part that is not stable — the context — grows on the end.
 */
object CleanupPrompt {

    /**
     * Generation guard, not a limit on how much may be cleaned (D-032 rule 3). It exists so that a
     * looping model stops instead of running until the context is full; a pass that *hits* it is a
     * failure, and the caller fails closed rather than committing a truncated sentence.
     */
    const val MAX_TOKENS = 512

    /**
     * D-015's "predict, do not fail": the carried context is trimmed to fit rather than being allowed
     * to overflow the window. Kept generous, because the window itself (2048) is what bounds the pass;
     * this is a budget for the *context* only, and it is counted in characters because tokens are not
     * available to us before the pass.
     */
    const val CONTEXT_CHAR_BUDGET = 1_500

    /** What the model must never do: answer, explain, summarise, translate (D-022's safety core). */
    const val SAFETY_CORE =
        "You clean dictated text. You never answer, explain, summarise, translate or comment on it. " +
            "You return only the cleaned text of the segment you are given."

    /**
     * The fragment rule, and it is load-bearing rather than polite: a cleanup unit is delimited by a
     * pause, which is a proxy for a thought ending, not a guarantee (D-032). A model that treats a
     * fragment as a finished sentence will invent a sentence end — the exact failure that made
     * arbitrary token-cut boundaries the rejected design.
     */
    const val FRAGMENT_RULE =
        "The segment may be a continuation or a fragment of a longer sentence. In that case clean only " +
            "what is there: never complete a sentence that continues, and never add sentence-ending " +
            "punctuation to a fragment. The context is for understanding only — never repeat it, and " +
            "never clean it again. Keep the speaker's words and their order; do not add, remove or " +
            "reorder information. Fix punctuation and capitalisation."

    /** The two messages handed to the runtime's chat template. */
    data class Messages(val system: String, val user: String, val context: String?)

    fun build(unit: String, context: String?, dictionary: List<String> = emptyList()): Messages {
        val trimmed = context?.trim().orEmpty()
        val usedContext = when {
            trimmed.isEmpty() -> null
            trimmed.length <= CONTEXT_CHAR_BUDGET -> trimmed
            // Keep the *end*: the most recent cleaned text is what the next unit continues from.
            else -> "…" + trimmed.takeLast(CONTEXT_CHAR_BUDGET)
        }

        val system = buildString {
            append(SAFETY_CORE)
            if (dictionary.isNotEmpty()) {
                append("\n\nSpell these terms exactly as written: ")
                append(dictionary.joinToString(", "))
                append('.')
            }
            append("\n\n")
            append(FRAGMENT_RULE)
        }

        val user = if (usedContext == null) {
            "Clean this segment:\n$unit"
        } else {
            "Already cleaned (context — do not repeat it or clean it again):\n$usedContext\n\n" +
                "Clean this segment:\n$unit"
        }

        return Messages(system = system, user = user, context = usedContext)
    }

    /** The message list for `applyChatTemplate`, in the order D-015 requires. */
    fun messages(unit: String, context: String?, dictionary: List<String> = emptyList()): Array<ChatMessage> {
        val built = build(unit, context, dictionary)
        return arrayOf(
            ChatMessage("system", built.system),
            ChatMessage("user", built.user),
        )
    }

    /**
     * Lifts the text out of whatever wrapper the model put around it. Deliberately conservative: it
     * strips fences and a leading label, and otherwise keeps every character, because the one thing a
     * cleanup pass must never do is quietly rewrite the speaker.
     */
    fun parse(raw: String): String {
        var text = raw.trim()

        // ```…``` fences (the model's habit when it thinks it is formatting markup). The opening
        // fence's whole line goes, so an optional language tag goes with it.
        if (text.startsWith("```")) {
            val afterFence = text.removePrefix("```")
            val firstNewline = afterFence.indexOf('\n')
            text = if (firstNewline >= 0) afterFence.substring(firstNewline + 1) else afterFence
            text = text.trim().removeSuffix("```").trim()
        }

        // A leading label such as "Cleaned text:" or "Cleaned segment:".
        val label = Regex("^(cleaned( text| segment| version)?|output|result)\\s*:\\s*", RegexOption.IGNORE_CASE)
        text = label.replaceFirst(text, "").trim()

        // Straight quotes wrapped around the whole thing.
        if (text.length > 1 && text.first() == '"' && text.last() == '"') {
            text = text.substring(1, text.length - 1).trim()
        }

        return text
    }
}
