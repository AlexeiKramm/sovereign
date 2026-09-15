package fi.ak.smartvoice.cleanup

import fi.ak.smartvoice.data.DictionaryEntry


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
        "You clean and format transcribed speech. Return only the cleaned text, without commentary, explanations, or quotes."

    /**
     * Which editing rules a pass runs under.
     */
    enum class Variant(val label: String, val rules: String) {
        /**
         * The unified on-device cleanup profile: drops vocal fillers, repairs stutters and mid-sentence
         * self-corrections, converts spoken numbers/times/currency into digits and symbols, and formats
         * spoken enumerations into clean markdown bullet points.
         */
        SMART_CLEAN(
            label = "smart clean — fillers, lists, numbers, punctuation",
            rules = "- Remove vocal filler words (\"um\", \"uh\", \"you know\", \"like\", \"kind of\", \"tota\", \"niinku\", \"tuota\"), stutters, and verbal hesitations (\"how to say it\", \"wait no\").\n" +
                "- When the speaker corrects themselves mid-sentence, output only the intended correction (e.g., \"call Sarah, no, David\" -> \"Call David.\").\n" +
                "- Convert spoken numbers, dates, times, units, and currency into standard digits and symbols (e.g., \"one hundred and twelve\" -> \"112\", \"ten twenty AM\" -> \"10:20 AM\", \"twenty euros\" -> \"20 €\").\n" +
                "- Format spoken enumerations, sequences, and list items (e.g., \"first... second...\", groceries, tasks) as clean markdown bullet points (\"- Item\") on new lines, preceded by an introductory colon where natural.\n" +
                "- Restore natural punctuation (commas, periods, question marks). Keep the speaker's original wording and voice otherwise (no summaries or added facts).\n\n" +
                "Sentence Capitalization & Continuation Rules:\n" +
                "The text to clean continues directly from the preceding context.\n" +
                "1. If the preceding context ends mid-sentence without a period (. ! ?), your output MUST start with a LOWERCASE letter, unless the first word is a proper noun (e.g. a person's name or city). Never capitalize the first word if it continues an ongoing thought!\n" +
                "2. If the preceding context ends with a period (. ! ?) or if there is no context, start your output with a capital letter.\n" +
                "3. Keep proper nouns capitalized (people, places, organizations, e.g. \"David\", \"Helsinki\", \"Google\").\n" +
                "4. If a completed sentence ends inside the segment, insert a period and capitalize the next sentence.\n\n" +
                "Examples:\n" +
                "- Context: \"I was sitting in my car and\"\n" +
                "  Input: \"listening to the radio when my phone rang\"\n" +
                "  Output: \"listening to the radio when my phone rang.\"\n" +
                "  (Starts with lowercase \"listening\" because context ends mid-sentence)\n" +
                "- Context: \"The meeting is over.\"\n" +
                "  Input: \"we can go to lunch now\"\n" +
                "  Output: \"We can go to lunch now.\"\n" +
                "  (Starts with capital \"We\" because context ends with a period)\n" +
                "- Context: \"I want to introduce you to\"\n" +
                "  Input: \"david who is our new lead\"\n" +
                "  Output: \"David, who is our new lead.\"\n" +
                "  (Starts with capital \"David\" because David is a proper noun)",
        );

        companion object {
            val DEFAULT = SMART_CLEAN

            fun fromId(id: String?): Variant = SMART_CLEAN
        }
    }

    /** The two messages handed to the runtime's chat template. */
    data class Messages(val system: String, val user: String, val context: String?)

    fun build(
        unit: String,
        context: String?,
        dictionary: List<String> = emptyList(),
        dictionaryEntries: List<DictionaryEntry> = emptyList(),
        variant: Variant = Variant.DEFAULT,
        customRules: String? = null,
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
            if (dictionaryEntries.isNotEmpty()) {
                append("\n\nCustom Vocabulary & Spelling Replacements:\n")
                append("Always use the exact casing and spelling for these terms. If the recogniser misheard them as any of the known phonetic variants or common misspellings, replace them with the correct term:\n")
                dictionaryEntries.forEach { entry ->
                    if (entry.aliases.isNotEmpty()) {
                        append("- \"${entry.word}\" (common misspellings to replace: ${entry.aliases.joinToString(", ") { "\"$it\"" }})\n")
                    } else {
                        append("- \"${entry.word}\"\n")
                    }
                }
            } else if (dictionary.isNotEmpty()) {
                append("\n\nSpell these terms exactly as written: ")
                append(dictionary.joinToString(", "))
                append('.')
            }
            append("\n\nFormatting Rules:\n")
            append(customRules ?: variant.rules)
        }

        val user = if (usedContext == null) {
            "Text to clean:\n$unit"
        } else {
            "Preceding context (for reference only — do not repeat this in output):\n$usedContext\n\n" +
                "Text to clean:\n$unit\n\n" +
                "Directive: If the preceding context above does not end with a period (. ! ?), your output MUST start with a lowercase letter (unless it is a proper noun)."
        }

        return Messages(system = system, user = user, context = usedContext)
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
