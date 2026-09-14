package fi.ak.smartvoice.cleanup

import fi.ak.smartvoice.data.DictionaryEntry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt is a contract with the model, and every clause in it exists because something went wrong
 * without it: the safety core because an LLM asked to clean text will happily answer it instead, the
 * fragment rule because a pause-delimited unit may be half a sentence and a model that treats it as
 * complete will invent a sentence end (D-032), and the ordering because D-015's stable prefix is the
 * only part that could ever be cached.
 */
class CleanupPromptTest {

    @Test
    fun `the system message carries the safety core and formatting rules`() {
        val built = CleanupPrompt.build("hello there", null)
        assertTrue(built.system.contains("clean and format transcribed speech"))
        assertTrue(built.system.contains("Formatting Rules:"))
        assertFalse("no context means no context text", built.user.contains("Preceding context"))
    }

    @Test
    fun `user message structures context and text to clean clearly`() {
        val built = CleanupPrompt.build("next words", "previous words")
        assertTrue(built.user.contains("Preceding context"))
        assertTrue(built.user.contains("previous words"))
        assertTrue(built.user.contains("Text to clean"))
        assertTrue(built.user.contains("next words"))
        assertTrue(built.user.contains("rewrite to connect seamlessly"))
    }

    @Test
    fun `every variant keeps the safety core, and only the editing rules differ`() {
        CleanupPrompt.Variant.entries.forEach { variant ->
            val system = CleanupPrompt.build("anything", null, variant = variant).system
            assertTrue("${variant.name} must keep the safety core", system.contains("clean and format"))
            assertTrue("${variant.name} must carry its own rules", system.contains(variant.rules))
        }
        assertTrue(CleanupPrompt.Variant.SMART_CLEAN.rules.contains("filler"))
        assertTrue(CleanupPrompt.Variant.SMART_CLEAN.rules.contains("bullet points"))
        assertTrue(CleanupPrompt.Variant.SMART_CLEAN.rules.contains("numbers"))
    }

    @Test
    fun `the variant labels are distinct, so the bench cannot show two identical rows`() {
        val labels = CleanupPrompt.Variant.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(CleanupPrompt.Variant.SMART_CLEAN, CleanupPrompt.Variant.DEFAULT)
        assertEquals(CleanupPrompt.Variant.SMART_CLEAN, CleanupPrompt.Variant.fromId("smart_clean"))
        assertEquals(CleanupPrompt.Variant.SMART_CLEAN, CleanupPrompt.Variant.fromId("legacy_standard"))
    }

    @Test
    fun `dictionary entries with aliases produce explicit replacement prompt rules`() {
        val entries = listOf(
            DictionaryEntry("Parakeet", listOf("parakit", "parakeete")),
            DictionaryEntry("Kotlin", emptyList()),
        )
        val built = CleanupPrompt.build(
            unit = "i saw a parakit",
            context = null,
            dictionaryEntries = entries,
        )
        assertTrue(built.system.contains("Custom Vocabulary & Spelling Replacements:"))
        assertTrue(built.system.contains("- \"Parakeet\" (common misspellings to replace: \"parakit\", \"parakeete\")"))
        assertTrue(built.system.contains("- \"Kotlin\""))
    }

    @Test
    fun `the prefix order is system, then dictionary, and the context comes before the unit`() {
        val built = CleanupPrompt.build(
            unit = "and also book the room",
            context = "Okay, so we should move the meeting to Friday.",
            dictionary = listOf("Gemma", "GenieX"),
        )
        val systemDictionary = built.system.indexOf("Spell these terms exactly as written")
        assertTrue("dictionary lives in the system message", systemDictionary > 0)
        assertTrue("the dictionary follows the safety core", built.system.contains("Gemma, GenieX"))

        val contextAt = built.user.indexOf("Okay, so we should move the meeting to Friday.")
        val unitAt = built.user.indexOf("and also book the room")
        assertTrue(contextAt in 0 until unitAt)
        assertTrue(built.user.contains("do not repeat"))
    }

    @Test
    fun `a blank or absent context is not sent as an empty context block`() {
        assertNull(CleanupPrompt.build("just this", null).context)
        assertNull(CleanupPrompt.build("just this", "").context)
        assertNull(CleanupPrompt.build("just this", "   ").context)
        assertEquals("just this", CleanupPrompt.build("just this", null).user.substringAfter("Text to clean:\n"))
    }

    @Test
    fun `an over-long context is trimmed from the front, keeping the most recent text`() {
        val long = "OLDEST " + "x".repeat(CleanupPrompt.CONTEXT_CHAR_BUDGET) + " MOST RECENT"
        val built = CleanupPrompt.build("next unit", long)
        val used = built.context!!
        assertTrue("the budget is respected", used.length <= CleanupPrompt.CONTEXT_CHAR_BUDGET + 1)
        assertTrue("the tail survives", used.endsWith("MOST RECENT"))
        assertFalse("the head is dropped", used.contains("OLDEST"))
        assertTrue("the truncation is visible", used.startsWith("…"))
    }


    @Test
    fun `the parser strips fences and a leading label but keeps every word`() {
        assertEquals(
            "Okay, so I think we should move it.",
            CleanupPrompt.parse("```\nOkay, so I think we should move it.\n```"),
        )
        assertEquals(
            "Okay, so I think we should move it.",
            CleanupPrompt.parse("Cleaned text: Okay, so I think we should move it."),
        )
        assertEquals(
            "Okay, so I think we should move it.",
            CleanupPrompt.parse("  \"Okay, so I think we should move it.\"  "),
        )
    }

    @Test
    fun `the parser does not invent text or reflow a clean output`() {
        val already = "Joo, ja sit mä kysyin Matiakselta, että onko se Gemma-malli jo ladattu."
        assertEquals(already, CleanupPrompt.parse(already))
        assertEquals("", CleanupPrompt.parse("   \n  "))
    }

    @Test
    fun `the token guard is generous enough for a long segment, which is the point`() {
        // D-032 rule 3: the cap is a runaway guard, not a limit on how much may be cleaned. A 60 s
        // pause-delimited unit is ~200 tokens of speech (3.4 tok/s), so the guard must clear that.
        assertTrue("guard must not truncate ordinary long segments", CleanupPrompt.MAX_TOKENS >= 384)
    }
}
