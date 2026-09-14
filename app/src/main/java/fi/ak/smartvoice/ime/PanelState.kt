package fi.ak.smartvoice.ime

/**
 * What the panel shows, split into the two halves so they cannot drift into each other (D-041).
 *
 * The panel had one status line, and every device round trip added a number to it: the engine, the cut
 * counters, the live quiet time, the stop countdown, the cold-load note. Each addition was right at the
 * time — a threshold nobody can observe is a threshold that gets guessed at, and three corrections
 * (D-037, D-038, D-039) each came out of a number that only existed because that line carried it. The
 * result, though, was a line written for its author.
 *
 * So both halves stay, separated:
 *
 * - [headline] is what a person needs while dictating: one line, no counters, no milliseconds.
 * - [details] is the diagnostics, shown on a tap rather than in the way — and **not deleted**, because
 *   the next wrong threshold will need it exactly as much as the last three did.
 *
 * Pure, so the wording is tested on this machine instead of eyeballed on a phone.
 */
data class PanelState(
    /** One line, for the person holding the phone. */
    val headline: String,
    /** The diagnostics: engine, counters, timings, notes. Behind a tap. */
    val details: String = "",
    val phase: Phase = Phase.IDLE,
    /** Seconds of audio captured in this utterance. */
    val elapsedSeconds: Double = 0.0,
    /** Whether the last frame of audio was speech — the honest answer at a ~1 s frame rate. */
    val level: Level = Level.UNKNOWN,
    /**
     * The recogniser's hypothesis for the unit currently being spoken. **Display only**: nothing here
     * has been committed, and the text at the cursor only ever arrives through the commit path (D-021).
     */
    val preview: String = "",
) {
    enum class Phase { IDLE, LISTENING, WORKING, ERROR }

    /** See [Level]: the audio is measured per frame, and the frames are of the order of a second. */
    enum class Level { UNKNOWN, QUIET, SPEECH }

    /** True when there is something worth showing under the headline. */
    val hasPreview: Boolean get() = preview.isNotBlank()
}

object PanelText {

    /**
     * The headline while listening: the elapsed time, and whether the mic is hearing anything.
     *
     * The level is a *word*, not a meter. A meter would need frame-level audio (this app sees frames of
     * about a second), and a bar that twitches once a second is worse than no bar — but "hearing you"
     * answers the question a person actually has ("is it working?"), and it is honest at this frame rate.
     */
    fun listeningLine(seconds: Double, level: PanelState.Level, cleanupNote: String? = null): String =
        buildString {
            append("Listening — %.1f s".format(seconds))
            levelWord(level)?.let { append(" · $it") }
            cleanupNote?.let { append(" · $it") }
        }

    fun levelWord(level: PanelState.Level): String? = when (level) {
        PanelState.Level.SPEECH -> "hearing you"
        PanelState.Level.QUIET -> "quiet"
        PanelState.Level.UNKNOWN -> null
    }

    /**
     * The level, from the audio measurement's quiet time: a fraction of a second of quiet means the last
     * frame carried speech. Deliberately coarse — see [listeningLine] for why there is no meter.
     */
    fun levelFor(quietFor: Double): PanelState.Level = when {
        quietFor < SPEECH_QUIET_SECONDS -> PanelState.Level.SPEECH
        else -> PanelState.Level.QUIET
    }

    /** How long the audio must have been quiet before the panel stops saying "hearing you". */
    const val SPEECH_QUIET_SECONDS = 0.5

    /**
     * The preview, clamped to [maxChars]. The **tail** is kept rather than the head: while dictating, the
     * words you just said are the ones you are checking, and an over-long segment must never push the mic
     * key off the screen.
     */
    fun previewLine(text: String, maxChars: Int = PREVIEW_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length <= maxChars) trimmed else "…" + trimmed.takeLast(maxChars)
    }

    /** How long a preview may get before it is clamped. */
    const val PREVIEW_CHARS = 120

    /**
     * The headline as shown, with the marker that says the details are there to be opened. The wording
     * lives here rather than in the View for the same reason as every other string in this file: it is
     * asserted on this machine, and a string built inside a `setText` call cannot be.
     */
    fun headlineWithMarker(state: PanelState, collapsed: Boolean): String = when {
        state.details.isBlank() -> state.headline
        collapsed -> state.headline + "  ▾"
        else -> state.headline + "  ▴"
    }

    /**
     * Whether a space should be prepended before new utterance text based on the
     * character immediately before the cursor. If the previous character is non-whitespace
     * (e.g. '.', '!', '?', or a letter), returns true so consecutive utterances don't fuse.
     */
    fun shouldPrependSpace(precedingChar: Char?): Boolean {
        if (precedingChar == null) return false
        return !precedingChar.isWhitespace()
    }

    private val LIST_ITEM_REGEX = Regex("""^([-*•]|\d+[\.\)])\s.*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Whether [text] begins with a markdown bullet point or numbered item marker.
     */
    fun isListItem(text: String): Boolean = LIST_ITEM_REGEX.matches(text.trim())

    /**
     * Determines the prefix separator ("\n", " ", or "") to prepend before [incoming] when appending to [preceding].
     *
     * - Returns "\n" if incoming explicitly starts with a newline, or is a list item while preceding text exists and does not already end with a newline.
     * - Returns "" if preceding text ends with whitespace or newline, or when there is no preceding text.
     * - Returns " " for normal sentence or word continuation.
     */
    fun unitPrefix(preceding: String?, incoming: String, isFirstUnit: Boolean = false): String {
        val trimmedIncoming = incoming.trim()
        if (trimmedIncoming.isEmpty()) return ""

        val rawIncomingStartsNewline = incoming.trimStart(' ', '\t').startsWith("\n")
        val precedingText = preceding.orEmpty()
        val precedingEndsNewline = precedingText.endsWith("\n")
        val precedingEndsColon = precedingText.trimEnd().endsWith(":")

        if (rawIncomingStartsNewline || isListItem(trimmedIncoming)) {
            return when {
                precedingEndsNewline -> ""
                precedingText.isNotEmpty() -> "\n"
                isFirstUnit -> ""
                else -> ""
            }
        }

        if (precedingEndsColon) {
            return "\n"
        }

        if (precedingText.isEmpty()) {
            return ""
        }

        val lastChar = precedingText.lastOrNull()
        return if (shouldPrependSpace(lastChar)) " " else ""
    }
}
