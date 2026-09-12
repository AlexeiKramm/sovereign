package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpoint rules: which ones can fire, and when (D-039).
 *
 * This is the regression test for the bug that survived three device rounds and a "raise the settings to
 * tens of seconds" attempt, because the cutting was never done by the setting. It pins the semantics we
 * depend on, and it keeps the two configurations that caused it written down.
 */
class EndpointRulesTest {

    /** Utterance lengths worth checking: shorter than the pause, around it, and far past it. */
    private val lengths = listOf(0.2f, 1f, 2f, 5f, 20f, 61f, 300f)

    /** Silences below the pause, including the two numbers that used to cut (1.2 s and 1.4 s). */
    private val shortSilences = listOf(0f, 0.2f, 1.2f, 1.4f, 2.4f, 5f, 9.99f)

    @Test
    fun `the configured pause is the shortest silence that can end a unit`() {
        val rules = EndpointRules.forPause(10f)
        for (length in lengths) {
            for (silence in shortSilences) {
                assertFalse(
                    "silence $silence s must not cut at length $length s",
                    rules.cutsAt(silence, length),
                )
            }
            assertTrue("the configured pause cuts at length $length s", rules.cutsAt(10f, length))
        }
    }

    @Test
    fun `unbroken speech is never cut, however long it runs`() {
        // D-032's accepted cost: no silence, no unit boundary — the unit is cleaned once at the end.
        val rules = EndpointRules.forPause(2f)
        assertFalse(rules.cutsAt(0f, 20f))
        assertFalse(rules.cutsAt(0f, 60f))
        assertFalse(rules.cutsAt(1.9f, 600f))
        assertTrue(rules.cutsAt(2f, 600f))
    }

    @Test
    fun `a long pause with no speech at all does not cut either`() {
        // Nothing decoded: rule1 still applies (must_contain_nonsilence = false loosens it), but an empty
        // segment carries no text, so the session emits no unit. Harmless by construction.
        val rules = EndpointRules.forPause(2f)
        assertTrue("the rule fires, and the session drops the empty unit", rules.cutsAt(2f, 2f))
    }

    @Test
    fun `the shipped configuration has exactly one rule that can fire`() {
        val rules = EndpointRules.forPause(30f)
        assertTrue(rules.fires(rules.rule1, 30f, 5f))
        // Both switches sit at a day of utterance *and* a day of silence — a conjunction no dictation
        // reaches, which is why they are unreachable rather than merely large. (At exactly a day they
        // would hold, so "never" here means "never in a dictation", stated rather than implied.)
        for (length in listOf(1f, 60f, 600f, 3_600f)) {
            assertFalse("rule2 stays off at $length s", rules.fires(rules.rule2, length, length))
            assertFalse("rule3 stays off at $length s", rules.fires(rules.rule3, length, length))
            assertFalse("nothing cuts unbroken speech", rules.cutsAt(0f, length))
        }
    }

    @Test
    fun `the configuration that caused the bug still cuts at a sentence edge`() {
        // Rule2 as it shipped: must_contain_nonsilence = false (which *loosens* it) at a hardcoded 1.4 s,
        // plus rule3 at the 20 s safety valve with no silence required. Written down so that the next
        // reader can see why "raise the setting" changed nothing.
        val shippedBefore = EndpointRuleSet(
            rule1 = EndpointRuleSpec(mustContainNonSilence = true, minTrailingSilence = 30f, minUtteranceLength = 0f),
            rule2 = EndpointRuleSpec(mustContainNonSilence = false, minTrailingSilence = 1.4f, minUtteranceLength = 0f),
            rule3 = EndpointRuleSpec(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
        )
        assertTrue("1.4 s of silence at a sentence edge cut", shippedBefore.cutsAt(1.4f, 5f))
        assertTrue("and 20 s of speech cut with no silence at all", shippedBefore.cutsAt(0f, 20f))
        assertFalse("the pause setting of 30 s never got the chance", shippedBefore.fires(shippedBefore.rule1, 29f, 40f))

        val fixed = EndpointRules.forPause(30f)
        assertFalse(fixed.cutsAt(1.4f, 5f))
        assertFalse(fixed.cutsAt(0f, 20f))
        assertTrue(fixed.cutsAt(30f, 40f))
    }

    @Test
    fun `the runtime's own defaults are the same trap`() {
        val libraryDefaults = EndpointRuleSet(
            rule1 = EndpointRuleSpec(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
            rule2 = EndpointRuleSpec(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
            rule3 = EndpointRuleSpec(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
        )
        assertTrue("rule2 needs speech and 1.2 s of silence", libraryDefaults.cutsAt(1.2f, 4f))
        assertFalse("but not 1.2 s before any speech", libraryDefaults.fires(libraryDefaults.rule2, 1.2f, 1.2f))
        assertTrue("rule1 does not even need speech", libraryDefaults.fires(libraryDefaults.rule1, 2.4f, 2.4f))
        assertTrue("rule3 cuts at 20 s mid-word", libraryDefaults.cutsAt(0f, 20f))
    }

    @Test
    fun `a mistyped pause cannot become cut constantly`() {
        // 0 means *never* on the auto-stop and *always* here, and the two fields look alike on screen, so
        // the rule floors itself rather than trusting the number that arrives.
        val rules = EndpointRules.forPause(0f)
        assertEquals(EndpointRules.MIN_PAUSE_SECONDS, rules.rule1.minTrailingSilence, 0.0001f)
        assertFalse("zero silence does not cut", rules.cutsAt(0f, 1f))
        assertTrue("the floor does", rules.cutsAt(EndpointRules.MIN_PAUSE_SECONDS, 1f))
    }
}
