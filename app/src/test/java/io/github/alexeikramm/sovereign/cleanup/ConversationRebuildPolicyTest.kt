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
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = 0, contextTokens = 4096))
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = 3000, contextTokens = 0))
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = 0, contextTokens = 0))
        assertFalse(ConversationRebuildPolicy.shouldRebuild(tokenCount = -1, contextTokens = 4096))
    }

    @Test
    fun `rebuilds at 85 percent of the cache and not before`() {
        val cap = 4096
        val trigger = (cap * ConversationRebuildPolicy.REBUILD_AT).toInt() // 3481

        assertFalse(ConversationRebuildPolicy.shouldRebuild(trigger - 1, cap))
        assertTrue(ConversationRebuildPolicy.shouldRebuild(trigger, cap))
        assertTrue(ConversationRebuildPolicy.shouldRebuild(cap, cap))
    }

    @Test
    fun `scales with the cache size`() {
        // A 2048-token cache triggers at ~1740, well below the 4096 figures above.
        assertFalse(ConversationRebuildPolicy.shouldRebuild(1740 - 1, 2048))
        assertTrue(ConversationRebuildPolicy.shouldRebuild(1740, 2048))
    }
}
