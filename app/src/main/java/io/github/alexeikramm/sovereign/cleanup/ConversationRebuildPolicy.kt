package io.github.alexeikramm.sovereign.cleanup

/**
 * When to rebuild the cleanup conversation. The KV cache grows with every unit cleaned (and every
 * reply), and the runtime never evicts; once it is full the session silently stops cleaning. So the
 * conversation is replaced before that — but not on every unit, because replacing it re-prefills the
 * ~500-token system prompt, which costs real time on an old phone.
 *
 * There are **two** reasons to replace it, and they answer different questions:
 * - **[RebuildReason.CAPACITY]** — the cache is nearly full (today's shipped rule). About reliability.
 * - **[RebuildReason.TURNS]** — the conversation has carried [maxTurns] segments, so the model is
 *   about to condition on a long trail of its own previous outputs. About *quality*: a small model
 *   drifts as its context fills, and the Cleanup Lab exists to find how many turns it runs well for.
 *
 * Pure, so the trigger is tested on this machine rather than discovered on a phone. The caller decides
 * *when* to ask (after a pass) and *how* to act (rebuild in the background); this only answers why.
 */
object ConversationRebuildPolicy {

    /**
     * The fraction of the cache at which the conversation is rebuilt. Rebuilding at 80% keeps enough
     * headroom for one more segment plus its reply to land before the cache would actually overflow.
     * The token figure depends on the engine's `contextTokens`; it is only a safe ceiling while that
     * figure matches what the bundle physically serves (see `LiteRtLmCleanupEngine.CONTEXT_TOKENS`).
     */
    const val REBUILD_AT = 0.80f

    /** `maxTurns = NO_TURN_LIMIT` keeps the shipped behaviour: only the cache triggers a rebuild. */
    const val NO_TURN_LIMIT = 0

    /** Why a conversation is being replaced. */
    enum class RebuildReason { TURNS, CAPACITY }

    /**
     * The reason to rebuild after a pass that leaves [turns] segments in a conversation holding
     * [tokenCount] tokens, or null when no rule fires. [maxTurns] ≤ 0 disables the turn rule.
     *
     * The turn rule is checked first: it is the cheap, deterministic one, and when both fire the
     * interesting fact is that the turn budget ran out.
     */
    fun reasonFor(
        tokenCount: Int,
        contextTokens: Int,
        turns: Int,
        maxTurns: Int = NO_TURN_LIMIT,
    ): RebuildReason? {
        if (maxTurns > 0 && turns >= maxTurns) return RebuildReason.TURNS
        if (contextTokens <= 0 || tokenCount <= 0) return null
        return if (tokenCount >= (contextTokens * REBUILD_AT).toInt()) RebuildReason.CAPACITY else null
    }

    /**
     * True when [tokenCount] has reached [REBUILD_AT] of [contextTokens]. Returns false when either
     * figure is unknown (0), so an engine that cannot report its cache is never rebuilt.
     */
    fun shouldRebuild(tokenCount: Int, contextTokens: Int): Boolean {
        if (contextTokens <= 0 || tokenCount <= 0) return false
        return tokenCount >= (contextTokens * REBUILD_AT).toInt()
    }

    /** True when [turns] has reached a positive [maxTurns] budget. */
    fun turnsExhausted(turns: Int, maxTurns: Int): Boolean = maxTurns > 0 && turns >= maxTurns

    /** The combined trigger: capacity **or** turn budget. */
    fun shouldRebuild(
        tokenCount: Int,
        contextTokens: Int,
        turns: Int,
        maxTurns: Int,
    ): Boolean = reasonFor(tokenCount, contextTokens, turns, maxTurns) != null
}
