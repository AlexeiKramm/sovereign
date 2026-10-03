package io.github.alexeikramm.sovereign.cleanup

/**
 * When to rebuild the cleanup conversation. The KV cache grows with every unit cleaned (and every
 * reply), and the runtime never evicts; once it is full the session silently stops cleaning. So the
 * conversation is replaced before that — but not on every unit, because replacing it re-prefills the
 * ~500-token system prompt, which costs real time on an old phone.
 *
 * Pure, so the trigger is tested on this machine rather than discovered on a phone. The caller decides
 * *when* to ask (after a pass) and *how* to act (rebuild in the background); this only answers "full
 * enough?".
 */
object ConversationRebuildPolicy {

    /**
     * The fraction of the cache at which the conversation is rebuilt. Rebuilding at 80% keeps enough
     * headroom for one more segment plus its reply to land before the cache would actually overflow.
     * On the shipped Gemma 4 E2B bundle (a hard 2048-token ceiling, D-086) that is ~1638 tokens.
     */
    const val REBUILD_AT = 0.80f

    /**
     * True when [tokenCount] has reached [REBUILD_AT] of [contextTokens]. Returns false when either
     * figure is unknown (0), so an engine that cannot report its cache is never rebuilt.
     */
    fun shouldRebuild(tokenCount: Int, contextTokens: Int): Boolean {
        if (contextTokens <= 0 || tokenCount <= 0) return false
        return tokenCount >= (contextTokens * REBUILD_AT).toInt()
    }
}
