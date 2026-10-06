package io.github.alexeikramm.sovereign.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing rule that makes one shared `DictationSession` safe for two consumers (D-106): text is
 * capture-scoped to the arbiter holder, cosmetics are broadcast. Getting this wrong is how a desktop
 * utterance would leak into the pill's card — or vanish.
 */
class DictationRouterTest {

    private var owner: SessionArbiter.Owner? = null
    private val router = DictationRouter { owner }
    private val log = mutableListOf<String>()

    private fun pill() = DictationListener(
        onText = { c, _, _ -> log += "pill:text:$c" },
        onPartialText = { log += "pill:partial:$it" },
        onUnitCommitted = { log += "pill:commit:$it" },
        onAudioLevel = { log += "pill:level:$it" },
        onModelResidencyChanged = { a, c -> log += "pill:residency:$a:$c" },
    )

    private fun server() = DictationListener(
        onText = { c, _, _ -> log += "server:text:$c" },
        onPartialText = { log += "server:partial:$it" },
        onUnitCommitted = { log += "server:commit:$it" },
        onAudioLevel = { log += "server:level:$it" },
    )

    @Test
    fun `text goes only to the arbiter holder`() {
        router.setListener(SessionArbiter.Owner.PILL, pill())
        router.setListener(SessionArbiter.Owner.SERVER, server())
        owner = SessionArbiter.Owner.SERVER

        router.onText("hello", "hello", "")
        router.onPartialText("hel")

        assertEquals(listOf("server:text:hello", "server:partial:hel"), log)
    }

    @Test
    fun `switching the holder switches the text target`() {
        router.setListener(SessionArbiter.Owner.PILL, pill())
        router.setListener(SessionArbiter.Owner.SERVER, server())

        owner = SessionArbiter.Owner.PILL
        router.onUnitCommitted("one")
        owner = SessionArbiter.Owner.SERVER
        router.onUnitCommitted("two")

        assertEquals(listOf("pill:commit:one", "server:commit:two"), log)
    }

    @Test
    fun `cosmetic callbacks are broadcast to everyone`() {
        router.setListener(SessionArbiter.Owner.PILL, pill())
        router.setListener(SessionArbiter.Owner.SERVER, server())
        owner = SessionArbiter.Owner.PILL

        router.onAudioLevel(0.5f)
        router.onModelResidencyChanged(true, false)

        assertTrue(log.contains("pill:level:0.5"))
        assertTrue(log.contains("server:level:0.5"))
        assertTrue(log.contains("pill:residency:true:false"))
    }

    @Test
    fun `text with no active capture goes nowhere`() {
        router.setListener(SessionArbiter.Owner.PILL, pill())
        owner = null

        router.onText("x", "x", "")

        assertTrue(log.isEmpty())
    }

    @Test
    fun `clearing a listener stops its callbacks`() {
        router.setListener(SessionArbiter.Owner.PILL, pill())
        owner = SessionArbiter.Owner.PILL

        router.clearListener(SessionArbiter.Owner.PILL)
        router.onText("x", "x", "")
        router.onAudioLevel(0.1f)

        assertTrue(log.isEmpty())
    }
}
