package io.github.alexeikramm.sovereign.cleanup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation-rebuild trigger. Pure, so the boundary is pinned here rather than discovered when a
 * long dictation silently stops being cleaned.
 */
class ConversationRebuildPolicyTest {

    @Test
    fun `null or unknown sizes never trigger a rebuild`() {
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = 0, contextTokens = 2048))
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = 3000, contextTokens = 0))
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = 0, contextTokens = 0))
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = -1, contextTokens = 2048))
    }

    @Test
    fun `rebuilds at 80 percent of the cache and not before`() {
        val cap = 2048
        val trigger = (cap * ConversationRebuildPolicy.REBUILD_AT).toInt() // 1638

        assertFalse(ConversationRebuildPolicy.shouldRebuild(trigger - 1, cap))
        assertTrue(ConversationRebuildPolicy.shouldRebuild(trigger, cap))
        assertTrue(ConversationRebuildPolicy.shouldRebuild(cap, cap))
    }

    @Test
    fun `the shipped model ceiling is respected`() {
        // The Gemma 4 E2B bundle is compiled with a hard 2048-token sequence dimension (D-086), so a
        // 2048 cap must trigger at 1638 — comfortably before the native failure at ~2014 tokens.
        assertFalse(ConversationRebuildPolicy.shouldRebuild(1637, 2048))
        assertTrue(ConversationRebuildPolicy.shouldRebuild(1638, 2048))
        assertTrue(
            "the trigger must leave room above it before the native 2048-token wall",
            (2048 * ConversationRebuildPolicy.REBUILD_AT).toInt() < 2000,
        )
    }
}
