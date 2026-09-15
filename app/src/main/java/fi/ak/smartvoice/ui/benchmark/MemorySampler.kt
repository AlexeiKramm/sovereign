package fi.ak.smartvoice.ui.benchmark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class MemoryStats(
    val baselineMb: Double,
    val peakMb: Double,
    val avgMb: Double,
    val deltaMb: Double,
)

object ProcessMemorySnapshot {
    /**
     * Reads current process Resident Set Size (RSS) in megabytes from `/proc/self/status`.
     * This captures the full physical footprint (native C++ LiteRT engine, OpenCL driver buffers,
     * mmap'd model weights, and JVM heap).
     * Falls back to JVM heap if `/proc/self/status` is inaccessible.
     */
    fun getRssMb(): Double {
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
                    if (rssKb != null) rssKb / 1024.0 else getFallbackRssMb()
                }
            } else {
                getFallbackRssMb()
            }
        } catch (_: Throwable) {
            getFallbackRssMb()
        }
    }

    private fun getFallbackRssMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }
}

/**
 * Periodically samples process RSS memory during benchmark runs to calculate
 * baseline, peak, and average RAM consumption.
 */
class MemorySampler(private val intervalMs: Long = 80L) {
    private val samples = mutableListOf<Double>()
    private var baselineMb: Double = 0.0
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        stop()
        samples.clear()
        baselineMb = ProcessMemorySnapshot.getRssMb()
        samples.add(baselineMb)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = ProcessMemorySnapshot.getRssMb()
                synchronized(samples) {
                    samples.add(current)
                }
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun currentPeakMb(): Double {
        val now = ProcessMemorySnapshot.getRssMb()
        synchronized(samples) {
            samples.add(now)
            return samples.maxOrNull() ?: now
        }
    }

    @Synchronized
    fun stop(): MemoryStats {
        job?.cancel()
        job = null
        val current = ProcessMemorySnapshot.getRssMb()
        val (peak, avg) = synchronized(samples) {
            samples.add(current)
            val p = samples.maxOrNull() ?: current
            val a = if (samples.isNotEmpty()) samples.average() else current
            Pair(p, a)
        }
        val delta = (peak - baselineMb).coerceAtLeast(0.0)
        return MemoryStats(
            baselineMb = baselineMb,
            peakMb = peak,
            avgMb = avg,
            deltaMb = delta,
        )
    }
}
