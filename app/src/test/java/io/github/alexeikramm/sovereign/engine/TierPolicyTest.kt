package io.github.alexeikramm.sovereign.engine

import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier policy (D-027). Pure, so every branch is checkable here instead of on a phone in
 * airplane mode — and the branches that matter are the two fallbacks, which are exactly the paths
 * nobody exercises by accident.
 */
class TierPolicyTest {

    @Test
    fun `local preference with a loaded model stays local`() {
        val choice = TierPolicy.choose(EnginePreference.LOCAL, LocalAsrEngine.Availability.Loaded)

        assertEquals(Engine.LOCAL, choice.engine)
        assertEquals("on-device", choice.note)
    }

    @Test
    fun `local preference still picks local when the model is installed but not loaded`() {
        // NotLoaded is the normal state after a process start: loading is the first utterance's job.
        val choice = TierPolicy.choose(EnginePreference.LOCAL, LocalAsrEngine.Availability.NotLoaded)

        assertEquals(Engine.LOCAL, choice.engine)
    }

    @Test
    fun `a missing model falls back to the server and says why`() {
        val choice = TierPolicy.choose(EnginePreference.LOCAL, LocalAsrEngine.Availability.NotInstalled)

        assertEquals(Engine.REMOTE, choice.engine)
        assertTrue("the reason has to be visible", choice.note.contains("not downloaded"))
    }

    @Test
    fun `an unavailable engine falls back to the server and carries the runtime's reason`() {
        val choice = TierPolicy.choose(
            EnginePreference.LOCAL,
            LocalAsrEngine.Availability.Unavailable("failed to create native OnlineRecognizer"),
        )

        assertEquals(Engine.REMOTE, choice.engine)
        assertTrue(choice.note.contains("failed to create native OnlineRecognizer"))
    }

    @Test
    fun `an explicit server preference is respected even when local is ready`() {
        val choice = TierPolicy.choose(EnginePreference.REMOTE, LocalAsrEngine.Availability.Loaded)

        assertEquals(Engine.REMOTE, choice.engine)
        assertTrue(choice.note.contains("settings"))
    }

    @Test
    fun `the default preference is local, and unknown stored values do not crash startup`() {
        assertEquals(EnginePreference.LOCAL, EnginePreference.fromStored(null))
        assertEquals(EnginePreference.LOCAL, EnginePreference.fromStored("something-from-an-older-build"))
        assertEquals(EnginePreference.REMOTE, EnginePreference.fromStored("remote"))
    }

    @Test
    fun `prefetch only when the local tier is what the user will use, and it is not loaded yet`() {
        // D-010 rule 2 fired for real: the measured cold load is 1912 ms, over the ~1.5 s threshold.
        assertTrue(TierPolicy.shouldPrefetch(EnginePreference.LOCAL, LocalAsrEngine.Availability.NotLoaded))
        assertFalse("already loaded — nothing to pay", TierPolicy.shouldPrefetch(EnginePreference.LOCAL, LocalAsrEngine.Availability.Loaded))
        assertFalse("nothing to load", TierPolicy.shouldPrefetch(EnginePreference.LOCAL, LocalAsrEngine.Availability.NotInstalled))
        assertFalse(
            "a broken runtime must not be retried on every keyboard show",
            TierPolicy.shouldPrefetch(EnginePreference.LOCAL, LocalAsrEngine.Availability.Unavailable("nope")),
        )
        assertFalse(
            "the user asked for the server",
            TierPolicy.shouldPrefetch(EnginePreference.REMOTE, LocalAsrEngine.Availability.NotLoaded),
        )
    }

    @Test
    fun `stored values round-trip`() {
        EnginePreference.entries.forEach { preference ->
            assertEquals(preference, EnginePreference.fromStored(preference.storedValue))
        }
    }
}
