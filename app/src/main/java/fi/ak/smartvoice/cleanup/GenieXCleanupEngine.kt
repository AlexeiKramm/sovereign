package fi.ak.smartvoice.cleanup

import fi.ak.smartvoice.data.DictionaryEntry

import android.content.Context
import android.os.SystemClock
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ProfilingData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The GenieX implementation of [CleanupEngine] — `llama_cpp` on the Hexagon NPU (D-030).
 *
 * Three things here are deliberate and worth not "simplifying" later:
 *
 * - **One `LlmWrapper`, held.** The cold load measured 3198 ms on the NPU (D-030), so the model is
 *   loaded once and stays resident for the life of the owner (D-010's lazy-sticky rule, D-028's no
 *   automatic unload). The spike's probe closed its wrapper after every run, which is why its numbers
 *   showed a 3.2 s load each time; that was the probe, not the design.
 * - **One decode in flight.** Every entry point takes the same mutex, because the runtime has one model
 *   handle and D-010 rule 3 allows exactly one decode at a time.
 * - **Fail closed.** A pass that errors, returns nothing, or stops on the token guard hands back the
 *   unit verbatim with a reason. The stage never commits a partial generation, and it never throws into
 *   the pipeline.
 */
class GenieXCleanupEngine(
    private val context: Context,
    private val model: CleanupModel,
    /** `npu` is the measured choice (D-030); the settings screen can pass another to compare. */
    private val computeUnit: String = COMPUTE_UNIT_NPU,
    private val dictionary: List<String> = emptyList(),
    private val dictionaryEntries: List<DictionaryEntry> = emptyList(),
) : CleanupEngine {

    private val lock = Mutex()

    @Volatile
    private var wrapper: LlmWrapper? = null

    @Volatile
    override var lastLoadMs: Long = 0L
        private set

    override val isLoaded: Boolean get() = wrapper != null

    /** True when this instance already serves the given catalogue entry. */
    fun uses(other: CleanupModel): Boolean = other.repository == model.repository

    override fun isServing(settings: fi.ak.smartvoice.data.AppSettings): Boolean =
        settings.cleanupBackend == fi.ak.smartvoice.data.CleanupBackend.GENIEX_NPU && uses(settings.cleanupModel)

    override suspend fun load(): Result<Unit> = lock.withLock { ensureLoaded().map { } }

    private suspend fun ensureLoaded(): Result<LlmWrapper> {
        wrapper?.let { return Result.success(it) }

        val paths = CleanupModelStore.paths(context, model)
            ?: return Result.failure(
                IllegalStateException("${model.label} is not on the device — download it in Settings"),
            )

        // 2048 is enough *because* units are pause-delimited (D-032): system + dictionary + a small
        // carried tail + the unit. The token guard (512) is what bounds the output inside it, and
        // D-015's rule is to predict an overrun and trim, not to overflow the window and fail.
        val config = ModelConfig(nCtx = N_CTX, nGpuLayers = -1)
        val started = SystemClock.elapsedRealtime()
        val created = LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_path = paths.model_path,
                    tokenizer_path = paths.tokenizer_path,
                    config = config,
                    runtime_id = paths.runtime_id.ifEmpty { RUNTIME_ID },
                    compute_unit = computeUnit,
                ),
            )
            .build()
        val elapsed = SystemClock.elapsedRealtime() - started

        return created
            .onSuccess {
                wrapper = it
                lastLoadMs = elapsed
            }
    }

    override suspend fun clean(
        unit: String,
        context: String?,
        variant: CleanupPrompt.Variant,
        customRules: String?,
    ): CleanupPass = lock.withLock {
        val loaded = ensureLoaded().getOrElse { error ->
            return@withLock CleanupPass.failed(unit, "cleanup model unavailable — ${error.message}")
        }
        val loadMs = lastLoadMs

        // D-015: reset before every pass. This is not hygiene theatre — the SDK retains conversation
        // state across generate calls, which the first device run proved: with a ~110-token system
        // message, the four passes reported 151 / 36 / 26 / 114 prompt tokens, i.e. only the new
        // suffix was prefilled. Without a reset the previous unit's text — and the previous
        // *utterance's* — sits in the KV cache and can influence, or be echoed into, the next answer.
        // Re-prefilling the explicit prompt costs ~0.1 s at the 1378 tps measured on this phone,
        // which is cheaper than inheriting hidden state from a dictation the user has moved on from.
        runCatching { loaded.reset() }

        val templated = loaded
            .applyChatTemplate(CleanupPrompt.messages(unit, context, dictionary, dictionaryEntries, variant, customRules), null, false)
            .getOrElse { error ->
                return@withLock CleanupPass.failed(unit, "chat template failed — ${error.message}")
            }
        val promptSent = templated.formattedText

        var text = ""
        var error: String? = null
        var profile: ProfilingData? = null
        loaded.generateStreamFlow(promptSent, GenerationConfig(maxTokens = CleanupPrompt.MAX_TOKENS))
            .collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> text += result.text
                    is LlmStreamResult.Completed -> profile = result.profile
                    is LlmStreamResult.Error ->
                        error = result.throwable.message ?: result.throwable::class.java.simpleName
                }
            }

        error?.let { return@withLock CleanupPass.failed(unit, "generation failed — $it", promptSent) }

        val cleaned = CleanupPrompt.parse(text)
        val stopReason = profile?.stopReason.orEmpty()

        // A pass that stops on the guard is a truncated sentence, which is worse than an unpunctuated
        // one: the unit stands as the recogniser produced it.
        if (stopReason.equals("length", ignoreCase = true)) {
            return@withLock CleanupPass.failed(
                unit,
                "hit the ${CleanupPrompt.MAX_TOKENS}-token guard, so the recogniser's text stands",
                promptSent,
            )
        }
        if (cleaned.isBlank()) {
            return@withLock CleanupPass.failed(unit, "the model returned nothing", promptSent)
        }

        CleanupPass(
            unit = unit,
            text = cleaned,
            cleaned = true,
            note = "cleaned on the $computeUnit unit · rules: ${variant.name.lowercase()}",
            loadMs = loadMs,
            ttftMs = profile?.ttftMs ?: 0.0,
            promptTimeMs = profile?.promptTimeMs ?: 0.0,
            decodeTimeMs = profile?.decodeTimeMs ?: 0.0,
            promptTokens = profile?.promptTokens ?: 0L,
            generatedTokens = profile?.generatedTokens ?: 0L,
            stopReason = stopReason,
            promptSent = promptSent,
        )
    }

    /**
     * Explicit release. Residency is the design (D-028), so nothing calls this automatically — the
     * settings screen does, to prove the model can be let go, and teardown does.
     */
    override fun unload() {
        wrapper?.close()
        wrapper = null
    }

    companion object {
        const val COMPUTE_UNIT_NPU = "npu"
        private const val RUNTIME_ID = "llama_cpp"

        /** Fits the prompt and the 512-token guard with room for the carried tail (D-015). */
        const val N_CTX = 2048
    }
}
