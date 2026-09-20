package fi.ak.smartvoice.ui.benchmark

import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class MemoryStats(
    val peakMb: Double,
    val avgMb: Double,
)

object ProcessMemorySnapshot {
    /**
     * Reads the current app process physical RAM footprint in megabytes.
     * Uses [android.os.Debug.MemoryInfo.totalPss] which includes CPU heap, native C++ memory,
     * mmap'd model weights, and OpenCL/GPU DMA-BUF graphics buffers.
     *
     * This metric is strictly isolated to the fi.ak.smartvoice process.
     * Falls back to /proc/self/status VmRSS or JVM heap if Debug.MemoryInfo is unavailable.
     */
    fun getProcessRamMb(): Double {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val pssKb = memInfo.totalPss
            if (pssKb > 0) {
                pssKb / 1024.0
            } else {
                getFallbackRssMb()
            }
        } catch (_: Throwable) {
            getFallbackRssMb()
        }
    }

    private fun getFallbackRssMb(): Double {
        return try {
            val file = File("/proc/self/status")
            if (file.canRead()) {
                file.bufferedReader().useLines { lines ->
                    var rssKb: Long? = null
                    for (line in lines) {
                        if (line.startsWith("VmRSS:")) {
                            rssKb = line.substringAfter("VmRSS:").trim().substringBefore(" ").toLongOrNull()
                            break
                        }
                    }
                    if (rssKb != null) rssKb / 1024.0 else getJvmHeapMb()
                }
            } else {
                getJvmHeapMb()
            }
        } catch (_: Throwable) {
            getJvmHeapMb()
        }
    }

    private fun getJvmHeapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }
}

/**
 * Periodically samples process Total PSS memory during benchmark runs to calculate
 * peak and average RAM consumption.
 */
class MemorySampler(private val intervalMs: Long = 100L) {
    private val samples = mutableListOf<Double>()
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        stop()
        samples.clear()
        val initial = ProcessMemorySnapshot.getProcessRamMb()
        samples.add(initial)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = ProcessMemorySnapshot.getProcessRamMb()
                synchronized(samples) {
                    samples.add(current)
                }
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun currentPeakMb(): Double {
        val now = ProcessMemorySnapshot.getProcessRamMb()
        synchronized(samples) {
            samples.add(now)
            return samples.maxOrNull() ?: now
        }
    }

    @Synchronized
    fun stop(): MemoryStats {
        job?.cancel()
        job = null
        val current = ProcessMemorySnapshot.getProcessRamMb()
        val (peak, avg) = synchronized(samples) {
            samples.add(current)
            val p = samples.maxOrNull() ?: current
            val a = if (samples.isNotEmpty()) samples.average() else current
            Pair(p, a)
        }
        return MemoryStats(
            peakMb = peak,
            avgMb = avg,
        )
    }
}
