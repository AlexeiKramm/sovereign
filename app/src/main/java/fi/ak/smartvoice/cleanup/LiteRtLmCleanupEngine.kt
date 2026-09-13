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
import com.google.ai.edge.litertlm.SamplerConfig
import fi.ak.smartvoice.data.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Google LiteRT-LM implementation of [CleanupEngine].
 *
 * Runs mobile-optimized `.litertlm` models (Gemma, Qwen, Granite) via the Google AI Edge LiteRT runtime
 * using GPU acceleration (with CPU fallback).
 */
class LiteRtLmCleanupEngine(
    private val context: Context,
    val modelSpec: LiteRtLmModelSpec,
    val processor: fi.ak.smartvoice.data.LiteRtProcessor = fi.ak.smartvoice.data.LiteRtProcessor.GPU,
    private val dictionary: List<String> = emptyList(),
    private val dictionaryEntries: List<DictionaryEntry> = emptyList(),
) : CleanupEngine {

    private val lock = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    override var lastLoadMs: Long = 0L
        private set

    override val isLoaded: Boolean get() = engine != null

    fun uses(other: LiteRtLmModelSpec): Boolean = other.id == modelSpec.id

    override fun isServing(settings: fi.ak.smartvoice.data.AppSettings): Boolean =
        settings.cleanupBackend == fi.ak.smartvoice.data.CleanupBackend.LITERT_LM &&
            uses(settings.litertModel) &&
            processor == settings.litertProcessor

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
                    try {
                        Backend.GPU()
                    } catch (e: Throwable) {
                        Backend.CPU()
                    }
                }
                fi.ak.smartvoice.data.LiteRtProcessor.CPU -> Backend.CPU()
                fi.ak.smartvoice.data.LiteRtProcessor.NPU -> {
                    try {
                        Backend.NPU(context.applicationInfo.nativeLibraryDir)
                    } catch (e: Throwable) {
                        try {
                            Backend.GPU()
                        } catch (e2: Throwable) {
                            Backend.CPU()
                        }
                    }
                }
            }

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
        val promptData = CleanupPrompt.build(
            unit = unit,
            context = context,
            dictionary = dictionary,
            dictionaryEntries = dictionaryEntries,
            variant = variant,
            customRules = customRules,
        )

        withContext(Dispatchers.IO) {
            var conversation: Conversation? = null
            try {
                val convConfig = ConversationConfig(
                    systemInstruction = Contents.Companion.of(promptData.system),
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = 0.1,
                        seed = 0,
                    ),
                    maxOutputToken = CleanupPrompt.MAX_TOKENS,
                )
                conversation = eng.createConversation(convConfig)

                val started = SystemClock.elapsedRealtime()
                var ttftMs = 0.0
                var firstToken = true
                val textBuilder = StringBuilder()
                var generatedTokens = 0L

                val messageFlow = conversation.sendMessageAsync(promptData.user)
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

                val totalDecodeTimeMs = (SystemClock.elapsedRealtime() - started).toDouble()
                val rawText = textBuilder.toString()
                val cleaned = CleanupPrompt.parse(rawText)

                if (cleaned.isBlank()) {
                    return@withContext CleanupPass.failed(unit, "LiteRT-LM returned empty response", promptData.user)
                }

                val backendLabel = processor.label
                CleanupPass(
                    unit = unit,
                    text = cleaned,
                    cleaned = true,
                    note = "cleaned on LiteRT-LM ($backendLabel) · rules: ${variant.name.lowercase()}",
                    loadMs = loadMs,
                    ttftMs = ttftMs,
                    decodeTimeMs = totalDecodeTimeMs,
                    generatedTokens = generatedTokens,
                    promptSent = promptData.user,
                )
            } catch (e: Exception) {
                CleanupPass.failed(unit, "LiteRT-LM error: ${e.message}", promptData.user)
            } finally {
                runCatching { conversation?.close() }
            }
        }
    }

    override fun unload() {
        runCatching { engine?.close() }
        engine = null
        System.gc()
    }
}
