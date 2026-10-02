package io.github.alexeikramm.sovereign.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warm predicate behind the resting pill's breathing (D-045). The interesting cases are the two
 * that would otherwise be invisible on the phone: cleanup off with no LLM resident (still warm), and
 * cleanup on with the LLM missing (not warm, even though ASR is loaded).
 */
class ModelResidencyTest {

    @Test
    fun `ASR alone is not warm while cleanup is enabled`() {
        assertFalse(ModelResidency.isFullyWarm(asrLoaded = true, cleanupEnabled = true, cleanupLoaded = false))
    }

    @Test
    fun `both engines loaded is warm`() {
        assertTrue(ModelResidency.isFullyWarm(asrLoaded = true, cleanupEnabled = true, cleanupLoaded = true))
    }

    @Test
    fun `cleanup disabled needs only ASR`() {
        assertTrue(ModelResidency.isFullyWarm(asrLoaded = true, cleanupEnabled = false, cleanupLoaded = false))
    }

    @Test
    fun `nothing is warm without ASR`() {
        assertFalse(ModelResidency.isFullyWarm(asrLoaded = false, cleanupEnabled = false, cleanupLoaded = false))
        assertFalse(
            "an LLM without ASR is not a usable configuration",
            ModelResidency.isFullyWarm(asrLoaded = false, cleanupEnabled = true, cleanupLoaded = true),
        )
    }

    @Test
    fun `unload is blocked while recording`() {
        assertFalse(ModelResidency.canUnload(recording = true, processing = false))
    }

    @Test
    fun `unload is blocked while processing`() {
        assertFalse(ModelResidency.canUnload(recording = false, processing = true))
    }

    @Test
    fun `unload is allowed only when fully idle`() {
        assertTrue(ModelResidency.canUnload(recording = false, processing = false))
        assertFalse("recording and processing cannot both be false here", ModelResidency.canUnload(true, true))
    }

    @Test
    fun `idle timeout of zero never unloads`() {
        assertFalse(ModelResidency.idleTimeoutReached(idleMillis = Long.MAX_VALUE, timeoutMinutes = 0))
    }

    @Test
    fun `idle timeout fires at the threshold`() {
        val twoMinutes = 2 * ModelResidency.MILLIS_PER_MINUTE
        assertFalse(ModelResidency.idleTimeoutReached(twoMinutes - 1, timeoutMinutes = 2))
        assertTrue(ModelResidency.idleTimeoutReached(twoMinutes, timeoutMinutes = 2))
    }
}
