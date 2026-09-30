package fi.ak.smartvoice.engine

import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.net.TranscribeResponse

/** Which tier produced a transcript. In the UI these are the words "local" and "server". */
enum class Engine(val label: String) {
    LOCAL("local"),
    REMOTE("server"),
}

/** What the user picked. Stored in [fi.ak.smartvoice.data.AppSettings]. */
enum class EnginePreference(val storedValue: String, val label: String) {
    LOCAL("local", "On-device (local)"),
    REMOTE("remote", "Server (home PC)"),
    ;

    companion object {
        /** Unknown or absent values fall back to the default rather than throwing on startup. */
        fun fromStored(value: String?): EnginePreference =
            entries.firstOrNull { it.storedValue == value } ?: LOCAL
    }
}

/**
 * The tier decision, plus a sentence for the status line explaining it.
 *
 * The note is not decoration: PLAN §8's rule is that no state may be silent, and "the model isn't
 * downloaded so this went to the server" is exactly the kind of thing the user has to be able to
 * see while dictating in someone else's app — there is no screen to go and check.
 */
data class EngineChoice(val engine: Engine, val note: String)

/**
 * Tier policy, decided 2026-09-12 (D-027): **local by default, server as an explicit choice.**
 *
 * The reasoning is the product's own premise — the app is supposed to work on a plane, and the
 * server is a private PC that may be asleep, off, or on the other side of a dead VPN. Making the
 * local engine the default means the common case has no dependency on anything outside the phone,
 * and a user who wants the server's cleanup quality picks it in settings.
 *
 * Pure on purpose: it takes the local engine's state as a value and returns a decision, so the
 * policy is testable without a device, a model, or a network.
 */
object TierPolicy {

    fun choose(preference: EnginePreference, local: LocalAsrEngine.Availability): EngineChoice = when (preference) {
        EnginePreference.REMOTE -> EngineChoice(Engine.REMOTE, "server (chosen in settings)")

        EnginePreference.LOCAL -> when (local) {
            // NotLoaded means "installed but not loaded yet", which the first utterance pays for.
            LocalAsrEngine.Availability.Loaded,
            LocalAsrEngine.Availability.NotLoaded,
            -> EngineChoice(Engine.LOCAL, "on-device")

            LocalAsrEngine.Availability.NotInstalled ->
                EngineChoice(Engine.REMOTE, "model not downloaded — falling back to the server")

            is LocalAsrEngine.Availability.Unavailable ->
                EngineChoice(Engine.REMOTE, "local engine unavailable (${local.reason}) — using the server")
        }
    }

    /**
     * D-010 rule 2: load on first use — **unless the measured cold load exceeds ~1.5 s**, in which
     * case promote to prefetching while the keyboard is visible.
     *
     * Measured on the target phone 2026-09-12: **1912 ms**, so prefetching is on (STATUS). The
     * threshold is in the rule, not here, and this function is the rule made checkable: prefetch
     * only when the local tier is what the user will actually use, and only when there is something
     * left to load.
     */
    fun shouldPrefetch(preference: EnginePreference, local: LocalAsrEngine.Availability): Boolean =
        preference == EnginePreference.LOCAL && local == LocalAsrEngine.Availability.NotLoaded
}

/**
 * One utterance's result, from either tier.
 *
 * The two tiers do not produce the same shape — the server returns stages, latency splits,
 * corrections and warnings; the local engine returns text, punctuation included, and nothing else
 * (it has no second stage at all until D-024 is settled). Rather than inventing empty values for
 * the server's fields, the response is nullable and the status line is built per engine.
 */
data class DictationOutcome(
    val text: String,
    val engine: Engine,
    val note: String,
    val audioSeconds: Double,
    val elapsedMs: Long,
    val response: TranscribeResponse? = null,
    val timings: PipelineTimings? = null,
)
