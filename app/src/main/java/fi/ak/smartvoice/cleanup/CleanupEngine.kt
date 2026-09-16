package fi.ak.smartvoice.cleanup

/**
 * One cleanup pass over one unit — the unit in, the cleaned text out, and the numbers that say what
 * it cost.
 *
 * `cleaned = false` is not an error path to be hidden: it means the pass failed and the **recogniser's
 * own text stands**, verbatim, with `note` saying why. Failing closed is the whole reason a failed
 * pass is representable at all (D-024's rule about parsers: fail closed, never apply part of a
 * generation), and a truncated or empty generation is exactly a failure rather than a short answer.
 */
data class CleanupPass(
    val unit: String,
    val text: String,
    val cleaned: Boolean,
    val note: String,
    val loadMs: Long = 0L,
    val ttftMs: Double = 0.0,
    val promptTimeMs: Double = 0.0,
    val decodeTimeMs: Double = 0.0,
    val promptTokens: Long = 0L,
    val generatedTokens: Long = 0L,
    val promptSent: String = "",
) {
    companion object {
        /** The fail-closed pass: the unit is returned untouched, and the reason is always stated. */
        fun failed(unit: String, note: String, promptSent: String = "") =
            CleanupPass(unit = unit, text = unit, cleaned = false, note = note, promptSent = promptSent)
    }
}

/**
 * The cleanup stage behind an interface, because the runtime hosting it was chosen by measurement
 * (D-030) and the choice is deliberately reversible (D-029 item 2): GenieX is the implementation,
 * LiteRT-LM would be another, and the streaming pipeline (chunk 5c) must not care which one it has.
 */
interface CleanupEngine {

    /** True once the model is loaded and resident. Residency is sticky by design (D-010, D-028). */
    val isLoaded: Boolean

    /** Cold-load time of the current instance, 0 before the first successful load. */
    val lastLoadMs: Long

    /** Loads once and keeps it; safe to call before every pass. */
    suspend fun load(): Result<Unit>

    /**
     * Cleans one pause-delimited unit (D-032). `context` is the cleaned text of this utterance so
     * far, or null on the first unit. Never throws: a pass that fails comes back with
     * `cleaned = false` and the unit untouched.
     */
    suspend fun clean(
        unit: String,
        context: String?,
        variant: CleanupPrompt.Variant = CleanupPrompt.Variant.DEFAULT,
        customRules: String? = null,
    ): CleanupPass

    /** Releases the model. Called on teardown and by the settings screen's explicit release. */
    fun unload()

    /** True when this engine instance serves the model and backend requested in settings. */
    fun isServing(settings: fi.ak.smartvoice.data.AppSettings): Boolean = true

    /** Optional: opens a session for the current utterance to reuse KV cache across units. */
    fun startSession(variant: CleanupPrompt.Variant = CleanupPrompt.Variant.DEFAULT, customRules: String? = null) {}

    /** Optional: closes the session and releases conversation KV cache. */
    fun closeSession() {}
}
