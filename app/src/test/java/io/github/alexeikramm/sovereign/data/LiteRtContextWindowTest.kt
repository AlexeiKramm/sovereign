package io.github.alexeikramm.sovereign.data

import io.github.alexeikramm.sovereign.cleanup.LiteRtLmCleanupEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The cleanup cache-window options. Two things must hold: the default is the device-verified 4K, and
 * the engine's compile-time fallback stays equal to that default (the enum value cannot be a
 * `const`, so nothing else would catch them drifting apart).
 */
class LiteRtContextWindowTest {

    @Test
    fun `default is the device-verified 4K window`() {
        assertSame(LiteRtContextWindow.K4, LiteRtContextWindow.DEFAULT)
        assertEquals(4096, LiteRtContextWindow.DEFAULT.tokens)
    }

    @Test
    fun `the two offered windows are 4K and 8K`() {
        assertEquals(listOf(4096, 8192), LiteRtContextWindow.entries.map { it.tokens })
        assertEquals(listOf("4K", "8K"), LiteRtContextWindow.entries.map { it.label })
    }

    @Test
    fun `fromTokens normalises unknown or corrupt values to the default`() {
        assertSame(LiteRtContextWindow.K8, LiteRtContextWindow.fromTokens(8192))
        assertSame(LiteRtContextWindow.K4, LiteRtContextWindow.fromTokens(4096))
        assertSame(LiteRtContextWindow.DEFAULT, LiteRtContextWindow.fromTokens(0))
        assertSame(LiteRtContextWindow.DEFAULT, LiteRtContextWindow.fromTokens(2048))
        assertSame(LiteRtContextWindow.DEFAULT, LiteRtContextWindow.fromTokens(-1))
    }

    @Test
    fun `the engine fallback matches the default window`() {
        assertEquals(LiteRtContextWindow.DEFAULT.tokens, LiteRtLmCleanupEngine.CONTEXT_TOKENS)
    }
}
