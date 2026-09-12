package fi.ak.smartvoice.spike

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import fi.ak.smartvoice.theme.SmartVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * SPIKE, NOT PRODUCT (branch `spike/geniex-npu`, decision D-029).
 *
 * Answers one question on the real phone: **does the Hexagon NPU beat the CPU for a small LLM doing
 * cleanup work?** It exists because the intended cheap route — Qualcomm's own `geniex-bench` CLI
 * under Termux — cannot work: that plugin has a hard `DT_NEEDED` on `libggml-opencl.so`, which needs
 * the vendor `libOpenCL.so` from `/vendor/lib64`, and Termux's linker namespace cannot see vendor
 * libraries. Inside an app it resolves, which is how Qualcomm's sample runs.
 *
 * The measurement is deliberately the same one the server tier reports, so the numbers are
 * comparable: time to first token, decode tokens/second, and wall time for a fixed generation.
 * Nothing here is wired into the IME, and nothing here is a design decision — see docs/spikes.md
 * for the procedure and DECISIONS D-029 for what the result decides.
 */
class NpuProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartVoiceTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NpuProbeScreen()
                }
            }
        }
    }
}

/** The two runtimes' compute-unit aliases, exactly as the SDK documents them. */
private enum class ComputeUnit(val label: String, val value: String) {
    NPU("npu", "npu"),
    HYBRID("hybrid", "hybrid"),
    GPU("gpu", "gpu"),
    CPU("cpu", "cpu"),
}

private data class ProbeModel(val label: String, val id: String, val quant: String, val sizeGb: Double)

private val MODELS = listOf(
    // Mechanism first: 0.5 GB proves the NPU path loads and generates at all.
    ProbeModel("Qwen3-0.6B", "unsloth/Qwen3-0.6B-GGUF", "Q4_0", 0.5),
    // Then the real question — the QAT Q4_0 release Qualcomm's own NPU path points at. Q4_0 is
    // required: other quantizations fall back to GPU/CPU per Qualcomm's docs.
    ProbeModel("Gemma 4 E2B QAT", "google/gemma-4-E2B-it-qat-q4_0-gguf", "Q4_0", 3.35),
)

/** Short on purpose: the cleanup pass this is standing in for is a few hundred tokens, not a story. */
private const val PROMPT = "Rewrite this dictated sentence with correct punctuation and casing: " +
    "okay so i think we should move the meeting to thursday or friday next week"

private const val MAX_TOKENS = 128

/**
 * `Build.SOC_MODEL` is API 31+ and the app's `minSdk` is 30, so the interesting identifier on this
 * device — the one GenieX and Qualcomm AI Hub use to select a per-chipset asset — needs a guard.
 */
private fun socModel(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown (API < 31)"

@Composable
private fun NpuProbeScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var model by remember { mutableStateOf(MODELS.first()) }
    var unit by remember { mutableStateOf(ComputeUnit.NPU) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }

    fun append(line: String) {
        log = log + line + "\n"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("NPU probe", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Spike (D-029): GenieX llama_cpp on the Hexagon NPU vs GPU vs CPU. Nothing is wired into " +
                "the keyboard — this only produces numbers.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "device: ${Build.MODEL} · soc: ${socModel()} · abi: ${Build.SUPPORTED_ABIS.firstOrNull()}",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Model", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODELS.forEach { candidate ->
                FilterChip(
                    selected = candidate == model,
                    onClick = { model = candidate },
                    label = { Text("${candidate.label} · ${candidate.sizeGb} GB") },
                )
            }
        }

        Text("Compute unit", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComputeUnit.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == unit,
                    onClick = { unit = candidate },
                    label = { Text(candidate.label) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    if (activity == null) return@Button
                    busy = true
                    append("--- init")
                    activity.lifecycleScope.launch {
                        runCatching {
                            // 0.4.0's init takes a callback, unlike the older docs.
                            GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
                                override fun onSuccess() = Unit
                                override fun onFailure(message: String) = Unit
                            })
                        }.onFailure { append("init threw: ${it.message}") }
                        append("init returned")
                        busy = false
                    }
                },
            ) { Text("1 · init") }

            OutlinedButton(
                enabled = !busy && activity != null,
                onClick = {
                    if (activity == null) return@OutlinedButton
                    busy = true
                    progress = 0f
                    append("--- pull ${model.id}:${model.quant}")
                    activity.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                ModelManagerWrapper.pullFlow(
                                    ModelPullInput(
                                        model_name = model.id,
                                        precision = model.quant,
                                        hub = HubSource.AUTO,
                                    ),
                                ).collect { event ->
                                    when (event) {
                                        is ModelManagerWrapper.PullEvent.Progress -> {
                                            val total = event.files.sumOf { it.total_bytes }
                                            val done = event.files.sumOf { it.downloaded_bytes }
                                            if (total > 0) progress = (done.toFloat() / total)
                                        }
                                        is ModelManagerWrapper.PullEvent.Completed -> {
                                            progress = 1f
                                            append("pull complete")
                                        }
                                        is ModelManagerWrapper.PullEvent.Error ->
                                            append("pull failed rc=${event.code}: ${event.message}")
                                    }
                                }
                            }
                            val paths = ModelManagerWrapper.getPaths(model.id)
                            append("paths: ${paths?.model_path ?: "none"} (runtime ${paths?.runtime_id})")
                        } catch (t: Throwable) {
                            append("pull threw: ${t.message}")
                        }
                        busy = false
                    }
                },
            ) { Text("2 · pull") }

            Button(
                enabled = !busy && activity != null,
                onClick = {
                    if (activity == null) return@Button
                    busy = true
                    append("--- bench ${model.label} on ${unit.label}")
                    activity.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val paths = ModelManagerWrapper.getPaths(model.id)
                                if (paths == null) {
                                    append("not pulled yet — run step 2 first")
                                    return@withContext
                                }
                                val config = ModelConfig(nCtx = 1024, nGpuLayers = -1)
                                // Load time is its own number: on the NPU the weights are loaded into
                                // the accelerator, and chunk 5 would pay that once per process.
                                val loadStarted = System.currentTimeMillis()
                                val loaded = LlmWrapper.builder()
                                    .llmCreateInput(
                                        LlmCreateInput(
                                            model_path = paths.model_path,
                                            tokenizer_path = paths.tokenizer_path,
                                            config = config,
                                            runtime_id = paths.runtime_id.ifEmpty { "llama_cpp" },
                                            compute_unit = unit.value,
                                        ),
                                    )
                                    .build()
                                val loadMs = System.currentTimeMillis() - loadStarted
                                val wrapper = loaded.getOrElse { error ->
                                    append("load failed after ${loadMs}ms: ${error.message}")
                                    return@withContext
                                }

                                val templated = wrapper
                                    .applyChatTemplate(arrayOf(ChatMessage("user", PROMPT)), null, false)
                                    .getOrElse { error ->
                                        append("chat template failed: ${error.message}")
                                        wrapper.close()
                                        return@withContext
                                    }

                                var firstTokenMs = -1L
                                var tokens = 0
                                var text = ""
                                val started = System.currentTimeMillis()
                                val wallMs = measureTimeMillis {
                                    wrapper.generateStreamFlow(
                                        templated.formattedText,
                                        GenerationConfig(maxTokens = MAX_TOKENS),
                                    ).collect { result ->
                                        when (result) {
                                            is LlmStreamResult.Token -> {
                                                if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - started
                                                tokens++
                                                text += result.text
                                            }
                                            is LlmStreamResult.Completed -> append(
                                                "completed (profile: ${result.profile})",
                                            )
                                            is LlmStreamResult.Error -> append("generate error: ${result.throwable}")
                                        }
                                    }
                                }
                                val decodeMs = if (firstTokenMs > 0) wallMs - firstTokenMs else wallMs
                                val decodeTps = if (decodeMs > 0) tokens * 1000.0 / decodeMs else 0.0

                                append("RESULT model=${model.id} unit=${unit.value}")
                                append("  load=${loadMs}ms ttft=${firstTokenMs}ms wall=${wallMs}ms tokens=$tokens")
                                append("  decode=%.1f tok/s".format(decodeTps))
                                append("  text: ${text.take(200)}")
                                wrapper.close()
                            }
                        } catch (t: Throwable) {
                            append("bench threw: ${t.javaClass.simpleName}: ${t.message}")
                        }
                        busy = false
                    }
                },
            ) { Text("3 · bench") }
        }

        progress?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    log.ifBlank { "log will appear here" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
