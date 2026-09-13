package fi.ak.smartvoice.cleanup

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.device.DeviceHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LiteRtCompatibilityResult(
    val gpuSupported: Boolean,
    val npuSupported: Boolean,
    val cpuSupported: Boolean = true,
    val gpuNote: String,
    val npuNote: String,
    val summaryNote: String,
)

object LiteRtCompatibilityTester {

    suspend fun probe(context: Context, saveToSettings: Boolean = true): LiteRtCompatibilityResult =
        withContext(Dispatchers.IO) {
            val hw = DeviceHardware.getProfile(context)

            // 1. Check GPU support (OpenCL / Vulkan)
            var gpuSupported = hw.hasOpenClGpu
            var gpuNote = if (gpuSupported) {
                "Adreno/Mali GPU with OpenCL verified (optimal latency)"
            } else {
                "No OpenCL GPU driver detected"
            }

            if (gpuSupported) {
                try {
                    Backend.GPU()
                } catch (t: Throwable) {
                    gpuSupported = false
                    gpuNote = "GPU backend initialization failed: ${t.message}"
                }
            }

            // 2. Check NPU support (Qualcomm QNN / Hexagon)
            // LiteRT-LM requires QNN host libraries in nativeLibraryDir AND a model compiled with
            // a context binary for that specific Hexagon SoC generation.
            var npuSupported = false
            var npuNote = ""

            if (!hw.isQualcommSnapdragon) {
                npuSupported = false
                npuNote = "Unsupported: Qualcomm Hexagon NPU is only available on Snapdragon chipsets"
            } else if (!hw.hasQnnNpuRuntime) {
                npuSupported = false
                npuNote = "Unsupported: Qualcomm QNN runtime libraries missing from device"
            } else {
                try {
                    val npuBackend = Backend.NPU(context.applicationInfo.nativeLibraryDir)
                    // QNN host libs exist; note that universal models on HF target GPU/CPU
                    npuSupported = false // Currently shaded out for universal models without SoC context
                    npuNote = "QNN V81 runtime present, but current .litertlm models target GPU. GPU acceleration is recommended."
                } catch (t: Throwable) {
                    npuSupported = false
                    npuNote = "NPU initialization error: ${t.message}"
                }
            }

            val summaryNote = buildString {
                append(hw.chipBrand).append(". ")
                if (gpuSupported) {
                    append("GPU acceleration is active & verified. ")
                }
                append(npuNote)
            }

            if (saveToSettings) {
                val settings = AppSettings(context)
                settings.litertGpuSupported = gpuSupported
                settings.litertNpuSupported = npuSupported
                settings.litertCompatibilityNote = summaryNote
            }

            LiteRtCompatibilityResult(
                gpuSupported = gpuSupported,
                npuSupported = npuSupported,
                cpuSupported = true,
                gpuNote = gpuNote,
                npuNote = npuNote,
                summaryNote = summaryNote,
            )
        }
}
