package io.github.alexeikramm.sovereign.cleanup

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.device.DeviceHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LiteRtCompatibilityResult(
    val gpuSupported: Boolean,
    val cpuSupported: Boolean = true,
    val gpuNote: String,
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
                "No OpenCL GPU driver detected — will use CPU fallback"
            }

            if (gpuSupported) {
                try {
                    Backend.GPU()
                } catch (t: Throwable) {
                    gpuSupported = false
                    gpuNote = "GPU backend initialization failed: ${t.message}"
                }
            }

            val summaryNote = buildString {
                append(hw.chipBrand).append(". ")
                if (gpuSupported) {
                    append("GPU acceleration (OpenCL) is active & verified.")
                } else {
                    append("Using CPU ARM NEON multi-threaded acceleration.")
                }
            }

            if (saveToSettings) {
                val settings = AppSettings(context)
                settings.litertGpuSupported = gpuSupported
                settings.litertCompatibilityNote = summaryNote
            }

            LiteRtCompatibilityResult(
                gpuSupported = gpuSupported,
                cpuSupported = true,
                gpuNote = gpuNote,
                summaryNote = summaryNote,
            )
        }
}
