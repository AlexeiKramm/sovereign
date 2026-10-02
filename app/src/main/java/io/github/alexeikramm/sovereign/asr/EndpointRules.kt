package io.github.alexeikramm.sovereign.asr

/**
 * The recogniser's endpoint rules, and — the part that matters — what they mean.
 *
 * sherpa-onnx activates a rule when (its own `RuleActivated`, `csrc/endpoint.cc`):
 *
 * ```
 * contain_nonsilence = utterance_length > trailing_silence
 * activated = (contain_nonsilence || !must_contain_nonsilence)
 *           && trailing_silence >= min_trailing_silence
 *           && utterance_length >= min_utterance_length
 * ```
 *
 * **Read the first clause twice, because it is inverted from how it reads.** `must_contain_nonsilence =
 * false` means the rule applies *even when nothing was decoded* — it **loosens** the rule; it does not
 * restrict it to empty segments. Getting that backwards shipped this: rule2 at a hardcoded 1.4 s with
 * `must_contain_nonsilence = false`, so **every 1.4 s of silence ended a unit no matter what**, while the
 * "unit pause" setting fed rule1 alone. Raising that setting to tens of seconds could not lift the boundary,
 * which is exactly what came back from the phone: *"I increased both the unit pause and fallback to tens of
 * seconds, but it seems like it still sometimes just cuts … It always also seems to be at the sentence
 * edges"* (D-039).
 *
 * The library's own defaults carry the same trap — rule1{false, 2.4, 0}, rule2{true, 1.2, 0},
 * rule3{false, 0, 20} — and rule3 is the sharpest of them: with `min_trailing_silence = 0` it needs **no
 * silence at all**, so at 20 s of utterance it cuts mid-word.
 *
 * So the shipped configuration has exactly one rule that can fire: the configured pause, whether or not
 * anything was decoded. The other two are given unreachable thresholds rather than a plausible number,
 * because a plausible number is how this happened.
 *
 * Pure data, so the rules are tested on this machine instead of in a phone report.
 */
data class EndpointRuleSpec(
    val mustContainNonSilence: Boolean,
    val minTrailingSilence: Float,
    val minUtteranceLength: Float,
)

/** The three rules, in the order the runtime evaluates them. */
data class EndpointRuleSet(
    val rule1: EndpointRuleSpec,
    val rule2: EndpointRuleSpec,
    val rule3: EndpointRuleSpec,
) {
    /** One rule, mirroring the runtime's arithmetic above. */
    fun fires(rule: EndpointRuleSpec, trailingSilence: Float, utteranceLength: Float): Boolean {
        val containNonSilence = utteranceLength > trailingSilence
        return (containNonSilence || !rule.mustContainNonSilence) &&
            trailingSilence >= rule.minTrailingSilence &&
            utteranceLength >= rule.minUtteranceLength
    }

    /** Would *any* of the three rules end the segment here? The question a phone report asks. */
    fun cutsAt(trailingSilence: Float, utteranceLength: Float): Boolean =
        fires(rule1, trailingSilence, utteranceLength) ||
            fires(rule2, trailingSilence, utteranceLength) ||
            fires(rule3, trailingSilence, utteranceLength)
}

object EndpointRules {

    /**
     * Long enough that no dictation reaches it. A rule given this threshold is switched off — used rather
     * than a large-but-plausible number so that nothing can quietly cut a sentence if a setting grows.
     */
    const val NEVER_SECONDS = 86_400f

    /**
     * A pause below this would be satisfied by almost any frame boundary, so a mistyped setting could not
     * simply become "cut constantly". A floor, not a default: 0 on the auto-stop means *never*, but 0 here
     * would mean *always*, and the two must not be one keystroke apart without a guard.
     */
    const val MIN_PAUSE_SECONDS = 0.2f

    /**
     * The rules for a pause of [pauseSeconds]: rule1 ends the segment on that much silence (with or without
     * decoded text), rule2 and rule3 are switched off.
     *
     * Genuinely unbroken speech therefore stays one unit and is cleaned once at the end. That is D-032's
     * accepted cost, and the alternative is the mid-sentence cut D-032 exists to avoid: a unit cut in the
     * middle of a sentence invites the cleanup model to punctuate it as if the sentence ended there.
     */
    fun forPause(pauseSeconds: Float): EndpointRuleSet = EndpointRuleSet(
        rule1 = EndpointRuleSpec(
            mustContainNonSilence = false,
            minTrailingSilence = maxOf(MIN_PAUSE_SECONDS, pauseSeconds),
            minUtteranceLength = 0f,
        ),
        rule2 = EndpointRuleSpec(
            mustContainNonSilence = false,
            minTrailingSilence = NEVER_SECONDS,
            minUtteranceLength = NEVER_SECONDS,
        ),
        rule3 = EndpointRuleSpec(
            mustContainNonSilence = false,
            minTrailingSilence = NEVER_SECONDS,
            minUtteranceLength = NEVER_SECONDS,
        ),
    )
}
