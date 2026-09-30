package fi.ak.smartvoice.cleanup

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import fi.ak.smartvoice.data.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Google LiteRT-LM implementation of [CleanupEngine].
 *
 * Runs mobile-optimized `.litertlm` models (Gemma 4 E2B, E4B) via the Google AI Edge LiteRT runtime
 * using GPU acceleration (with CPU fallback).
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmCleanupEngine(
    private val context: Context,
    val modelSpec: LiteRtLmModelSpec,
    val processor: fi.ak.smartvoice.data.LiteRtProcessor = fi.ak.smartvoice.data.LiteRtProcessor.GPU,
    val speculativeDecoding: Boolean = true,
    /**
     * Read every time a prompt is built — at session start, and on a standalone [clean] — never
     * captured into the instance. The engine is long-lived (residency is sticky), so a snapshot
     * here would silently pin the vocabulary until the next model reload. Mirrors
     * `CleanupPipeline`'s live `rules` lambda: a dependency that can change is a function.
     */
    private val vocabulary: () -> List<DictionaryEntry> = { emptyList() },
) : CleanupEngine {

    private val lock = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var sessionConversation: Conversation? = null

    @Volatile
    private var sessionTurns: Int = 0

    @Volatile
    override var lastLoadMs: Long = 0L
        private set

    override val isLoaded: Boolean get() = engine != null

    fun uses(other: LiteRtLmModelSpec): Boolean = other.id == modelSpec.id

    val effectiveProcessor: fi.ak.smartvoice.data.LiteRtProcessor =
        if (!modelSpec.supportsGpu) fi.ak.smartvoice.data.LiteRtProcessor.CPU else processor

    override fun isServing(settings: fi.ak.smartvoice.data.AppSettings): Boolean {
        val expectedProcessor = if (!modelSpec.supportsGpu) {
            fi.ak.smartvoice.data.LiteRtProcessor.CPU
        } else {
            settings.litertProcessor
        }
        return uses(settings.litertModel) &&
            effectiveProcessor == expectedProcessor &&
            speculativeDecoding == settings.litertSpeculativeDecoding
    }

    private suspend fun ensureLoaded(): Result<Engine> = withContext(Dispatchers.IO) {
        runCatching {
            val current = engine
            if (current != null) return@runCatching current

            val modelFile = LiteRtLmModelStore.getModelFile(context, modelSpec)
            if (!modelFile.exists()) {
                throw IllegalStateException("${modelSpec.label} is not on the device — download it in Models")
            }

            val started = SystemClock.elapsedRealtime()
            val backend: Backend = when (processor) {
                fi.ak.smartvoice.data.LiteRtProcessor.GPU -> {
                    if (!modelSpec.supportsGpu) {
                        Backend.CPU()
                    } else {
                        try {
                            Backend.GPU()
                        } catch (e: Throwable) {
                            Backend.CPU()
                        }
                    }
                }
                fi.ak.smartvoice.data.LiteRtProcessor.CPU -> Backend.CPU()
            }

            val effectiveSpeculative = speculativeDecoding && modelSpec.supportsSpeculativeDecoding
            ExperimentalFlags.enableSpeculativeDecoding = effectiveSpeculative
            ExperimentalFlags.enableBenchmark = true

            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
            )
            val created = Engine(config)
            created.initialize()
            engine = created
            lastLoadMs = SystemClock.elapsedRealtime() - started
            created
        }
    }

    override suspend fun load(): Result<Unit> = lock.withLock {
        ensureLoaded().map { }
    }

    override fun startSession(variant: CleanupPrompt.Variant, customRules: String?) {
        closeSession()
        sessionTurns = 0
        val eng = engine ?: return
        try {
            val promptData = CleanupPrompt.build(
                unit = "",
                context = null,
                dictionaryEntries = vocabulary(),
                variant = variant,
                customRules = customRules,
            )
            sessionConversation = eng.createConversation(conversationConfig(promptData.system))
        } catch (_: Throwable) {
            sessionConversation = null
        }
    }

    override fun closeSession() {
        val conv = sessionConversation
        sessionConversation = null
        sessionTurns = 0
        conv?.let { runCatching { it.close() } }
    }

    /**
     * One definition of the conversation's sampler and token budget, so an in-session conversation
     * and a standalone one cannot drift apart. The sampler numbers come from the model spec — the
     * engine holds none of its own, so each model is tuned in exactly one place.
     */
    private fun conversationConfig(system: String) = ConversationConfig(
        systemInstruction = Contents.Companion.of(system),
        samplerConfig = SamplerConfig(
            topK = modelSpec.sampling.topK,
            topP = modelSpec.sampling.topP,
            temperature = modelSpec.sampling.temperature,
            seed = modelSpec.sampling.seed,
        ),
        maxOutputToken = CleanupPrompt.MAX_TOKENS,
    )

    override suspend fun clean(
        unit: String,
        context: String?,
        variant: CleanupPrompt.Variant,
        customRules: String?,
    ): CleanupPass = lock.withLock {
        val eng = ensureLoaded().getOrElse { error ->
            return@withLock CleanupPass.failed(
                unit,
                "LiteRT-LM load failed: ${error.message}"
            )
        }

        val loadMs = lastLoadMs

        withContext(Dispatchers.IO) {
            val activeConv = sessionConversation
            val isSession = activeConv != null
            val conversation: Conversation
            val messageToSend: String
            if (activeConv != null) {
                // In an active session the system instruction — safety core, vocabulary and rules —
                // is already in the KV cache; only the new segment is sent.
                conversation = activeConv
                messageToSend = if (sessionTurns == 0 && !context.isNullOrBlank()) {
                    "Already cleaned (context — do not repeat it or clean it again):\n$context\n\nClean this segment:\n$unit"
                } else {
                    "Clean this segment:\n$unit"
                }
            } else {
                // No session: build a fresh conversation, reading the vocabulary now so the pass
                // sees the dictionary as it is at this moment.
                val promptData = CleanupPrompt.build(
                    unit = unit,
                    context = context,
                    dictionaryEntries = vocabulary(),
                    variant = variant,
                    customRules = customRules,
                )
                conversation = eng.createConversation(conversationConfig(promptData.system))
                messageToSend = promptData.user
            }

            val started = SystemClock.elapsedRealtime()
            try {
                var ttftMs = 0.0
                var firstToken = true
                val textBuilder = StringBuilder()
                var generatedTokens = 0L

                val messageFlow = conversation.sendMessageAsync(messageToSend)
                messageFlow.collect { msg ->
                    val textContent = msg.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (textContent.isNotEmpty()) {
                        if (firstToken) {
                            ttftMs = (SystemClock.elapsedRealtime() - started).toDouble()
                            firstToken = false
                        }
                        textBuilder.append(textContent)
                        generatedTokens++
                    }
                }

                var totalDecodeTimeMs = (SystemClock.elapsedRealtime() - started).toDouble()
                val rawText = textBuilder.toString()
                val cleaned = CleanupPrompt.parse(rawText)

                val totalActiveMs = SystemClock.elapsedRealtime() - started
                if (cleaned.isBlank()) {
                    return@withContext CleanupPass.failed(unit, "LiteRT-LM returned empty response", messageToSend, activeMs = totalActiveMs)
                }

                if (isSession) {
                    sessionTurns++
                }

                var actualTokens = generatedTokens
                var actualTtftMs = ttftMs

                // Query native benchmark info from LiteRT-LM to get exact token counts and throughput,
                // which is critical during speculative decoding where multiple tokens are emitted per callback chunk.
                val benchInfo = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                if (benchInfo != null && benchInfo.lastDecodeTokenCount > 0) {
                    actualTokens = benchInfo.lastDecodeTokenCount.toLong()
                    if (benchInfo.timeToFirstTokenInSecond > 0.0) {
                        actualTtftMs = benchInfo.timeToFirstTokenInSecond * 1000.0
                    }
                    if (benchInfo.lastDecodeTokensPerSecond > 0.0 && actualTokens > 0) {
                        // Calculate decode duration from native token generation speed
                        totalDecodeTimeMs = (actualTokens * 1000.0) / benchInfo.lastDecodeTokensPerSecond
                    }
                } else {
                    // Fallback token estimation when native counter is inactive or 0:
                    // In Gemma vocab, cleaned English text averages ~1.3 tokens per word.
                    val words = cleaned.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                    val estimatedTokens = (words * 1.32).toLong().coerceAtLeast(1L)
                    actualTokens = maxOf(generatedTokens, estimatedTokens)
                }

                var promptTokens = 0L
                var promptDurationMs = actualTtftMs
                if (benchInfo != null && benchInfo.lastPrefillTokenCount > 0) {
                    promptTokens = benchInfo.lastPrefillTokenCount.toLong()
                    if (benchInfo.lastPrefillTokensPerSecond > 0.0 && promptTokens > 0) {
                        promptDurationMs = (promptTokens * 1000.0) / benchInfo.lastPrefillTokensPerSecond
                    }
                }

                val backendLabel = processor.label
                val specTag = if (speculativeDecoding && modelSpec.supportsSpeculativeDecoding) ", spec: on" else ""
                val cacheTag = if (isSession) " [cached]" else ""
                CleanupPass(
                    unit = unit,
                    text = cleaned,
                    cleaned = true,
                    note = "cleaned on LiteRT-LM ($backendLabel$specTag)$cacheTag · rules: ${variant.name.lowercase()}",
                    loadMs = loadMs,
                    ttftMs = actualTtftMs,
                    promptTimeMs = promptDurationMs,
                    decodeTimeMs = totalDecodeTimeMs,
                    promptTokens = promptTokens,
                    generatedTokens = actualTokens,
                    promptSent = messageToSend,
                    activeMs = totalActiveMs,
                )
            } catch (e: Exception) {
                if (isSession) {
                    closeSession()
                }
                CleanupPass.failed(unit, "LiteRT-LM error: ${e.message}", messageToSend, activeMs = SystemClock.elapsedRealtime() - started)
            } finally {
                if (!isSession) {
                    runCatching { conversation.close() }
                }
            }
        }
    }

    override fun unload() {
        closeSession()
        runCatching { engine?.close() }
        engine = null
        System.gc()
    }
}
