package fi.ak.smartvoice.cleanup

import com.geniex.sdk.bean.ChatMessage

/**
 * The cleanup prompt, as a pure function of (unit, context, dictionary, variant) — so its shape is
 * pinned by tests on this machine rather than discovered on a phone, and so D-015's ordering rule has
 * one implementation:
 *
 *   stable prefix first → system core, then dictionary → then the growing part: cleaned context,
 *   then the unit to clean.
 *
 * **The variant is the interesting knob.** Measured on the phone 2026-09-12, the first Finnish case came
 * back *identical to its input except one capital letter* — no fillers removed, no comma before "että",
 * no final mark. The English case in the same run was punctuated correctly. That points at the rules
 * rather than the model: the wording said "do not add, remove or reorder information" and "never add
 * sentence-ending punctuation to a fragment", which a cautious 2B model reads as "change almost
 * nothing". The variants exist so the same unit can be run under each rule set and compared, instead of
 * arguing about it.
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
     * to overflow the window. Counted in characters because tokens are not available before the pass.
     */
    const val CONTEXT_CHAR_BUDGET = 1_500

    /** What the model must never do: answer, explain, summarise, translate (D-022's safety core). */
    const val SAFETY_CORE =
        "You clean dictated text. You never answer, explain, summarise, translate or comment on it. " +
            "You return only the cleaned text of the segment you are given."

    /**
     * The fragment rule, and it is load-bearing rather than polite: a cleanup unit is delimited by a
     * pause, which is a proxy for a thought ending, not a guarantee (D-032).
     *
     * It is deliberately two-sided. The first version only said "never add sentence-ending punctuation
     * to a fragment", and the measured English output then came back with no final period at all — a
     * rule that only forbids gets read as "add nothing". Both directions are stated now.
     */
    const val FRAGMENT_RULE =
        "The segment may be a continuation or a fragment of a longer sentence. If it is clearly a " +
            "continuation, clean only what is there: do not capitalise its first word as if it started " +
            "a sentence, and do not add a final mark. If it is a complete sentence, punctuate it as one, " +
            "final mark included. The context is for understanding only — never repeat it, and never " +
            "clean it again."

    /**
     * Which editing rules a pass runs under. The differences are only the editing rules; the safety core
     * and the fragment rule are common to all three.
     */
    enum class Variant(val label: String, val rules: String) {
        /**
         * The first thing that was built, kept as the control: every word the speaker said is kept.
         * Measured effect on Finnish: one capital letter and nothing else.
         */
        CONSERVATIVE(
            label = "conservative — punctuation and capitalisation only",
            rules = "Keep the speaker's words and their order; do not add, remove or reorder " +
                "information. Fix punctuation and capitalisation.",
        ),

        /** The likely default: dictation's most common defects — fillers, stutters, missing marks. */
        STANDARD(
            label = "standard — drops fillers, punctuates",
            rules = "Remove filler words, hesitations, stutters and accidentally doubled words " +
                "(\"tuota\", \"niinku\", \"tota\", \"um\", \"uh\", \"you know\"). Then punctuate what is " +
                "left, including a final mark on a complete sentence. Do not change the speaker's " +
                "wording otherwise: no synonyms, no rephrasing, no summarising.",
        ),

        /**
         * Also repairs obviously wrong dictated words — the misheard proper noun, the compound written
         * apart. The most a cleanup pass should ever do; it is D-006's "obvious contextual slip", not
         * licence to rewrite.
         */
        REPAIRING(
            label = "repairing — also fixes obvious slips",
            rules = "Remove filler words, hesitations, stutters and accidentally doubled words. " +
                "Punctuate properly, including a final mark on a complete sentence. Repair obvious " +
                "slips in the dictated words: an inflected form that cannot be right, a name or term " +
                "that is clearly misheard, a compound written apart. Never change the meaning, never " +
                "add facts, never answer.",
        );

        companion object {
            /** What the pipeline should use until the Finnish comparison says otherwise. */
            val DEFAULT = STANDARD
        }
    }

    /** The two messages handed to the runtime's chat template. */
    data class Messages(val system: String, val user: String, val context: String?)

    fun build(
        unit: String,
        context: String?,
        dictionary: List<String> = emptyList(),
        variant: Variant = Variant.DEFAULT,
    ): Messages {
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
            append(variant.rules)
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
    fun messages(
        unit: String,
        context: String?,
        dictionary: List<String> = emptyList(),
        variant: Variant = Variant.DEFAULT,
    ): Array<ChatMessage> {
        val built = build(unit, context, dictionary, variant)
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
