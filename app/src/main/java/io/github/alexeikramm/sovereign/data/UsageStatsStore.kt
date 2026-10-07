package io.github.alexeikramm.sovereign.data

import android.content.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UtteranceRecord(
    val id: String,
    val timestampMs: Long,
    val durationSeconds: Double,
    val rawWords: Int,
    /** Total word-level edits (removed + changed + added); the canonical count (D-124). */
    @JsonNames("cleanedWords")
    val wordsEdited: Int,
    val engine: String,
    val deliveredWords: Int = wordsEdited,
    val wordsRemoved: Int = 0,
    val wordsChanged: Int = 0,
    val wordsAdded: Int = 0,
    val asrDurationMs: Long = 0L,
    val llmDurationMs: Long = 0L,
    val wallClockMs: Long = 0L,
    val latencyMs: Long = 0L,
) {

    /** Real-time processing speed multiplier for ASR: audio duration / ASR active time */
    val asrRealTimeSpeed: Double
        get() = if (asrDurationMs > 0L && durationSeconds > 0.0) {
            (durationSeconds * 1000.0) / asrDurationMs
        } else 0.0

    /** Real-time processing speed multiplier for LLM: audio duration / LLM active time */
    val llmRealTimeSpeed: Double
        get() = if (llmDurationMs > 0L && durationSeconds > 0.0) {
            (durationSeconds * 1000.0) / llmDurationMs
        } else 0.0

    /** Total active AI compute duration (ASR decode + LLM cleanup) */
    val totalComputeMs: Long
        get() = asrDurationMs + (if (llmDurationMs > 0L) llmDurationMs else 0L)

    /** Pipeline compute speed multiplier during this utterance */
    val pipelineRealTimeSpeed: Double
        get() = if (totalComputeMs > 0L && durationSeconds > 0.0) {
            (durationSeconds * 1000.0) / totalComputeMs
        } else 0.0

    /** Overall end-to-end real-time speed multiplier: audio duration / total wall-clock time */
    val e2eRealTimeSpeed: Double
        get() = if (wallClockMs > 0L && durationSeconds > 0.0) {
            (durationSeconds * 1000.0) / wallClockMs
        } else 0.0
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UsageStatsData(
    val totalAudioSeconds: Double = 0.0,
    val totalRawWords: Int = 0,
    @JsonNames("totalCleanedWords")
    val totalWordsEdited: Int = 0,
    val totalUtterances: Int = 0,
    val recentRecords: List<UtteranceRecord> = emptyList(),
    val totalDeliveredWords: Int = 0,
    val totalWordsRemoved: Int = 0,
    val totalWordsChanged: Int = 0,
    val totalWordsAdded: Int = 0,
    val totalAsrActiveMs: Long = 0L,
    val totalLlmActiveMs: Long = 0L,
    val totalWallClockMs: Long = 0L,
    val totalAsrAudioSeconds: Double = 0.0,
    val totalLlmAudioSeconds: Double = 0.0,
    val totalLatencyMs: Long = 0L,
    val totalLatencyCount: Int = 0,
) {

    /** Approximate minutes saved typing assuming 40 WPM average typing speed. */
    val typingMinutesSaved: Double
        get() {
            val words = if (totalDeliveredWords > 0) totalDeliveredWords else totalWordsEdited
            return (words.toDouble() / 40.0)
        }

    /** Aggregate real-time speed multiplier for on-device ASR across tracked utterances */
    val asrRealTimeSpeed: Double
        get() {
            val audioSec = if (totalAsrAudioSeconds > 0.0) totalAsrAudioSeconds else totalAudioSeconds
            return if (totalAsrActiveMs > 0L && audioSec > 0.0) {
                (audioSec * 1000.0) / totalAsrActiveMs
            } else 0.0
        }

    /** Aggregate real-time speed multiplier for on-device LLM cleanup across utterances where LLM was used */
    val llmRealTimeSpeed: Double
        get() {
            val audioSec = if (totalLlmAudioSeconds > 0.0) totalLlmAudioSeconds else {
                recentRecords.filter { it.llmDurationMs > 0L }.sumOf { it.durationSeconds }.takeIf { it > 0.0 } ?: 0.0
            }
            return if (totalLlmActiveMs > 0L && audioSec > 0.0) {
                (audioSec * 1000.0) / totalLlmActiveMs
            } else 0.0
        }

    /** Total active AI compute duration (ASR decode + LLM cleanup) across all utterances */
    val totalComputeMs: Long
        get() = totalAsrActiveMs + totalLlmActiveMs

    /** Aggregate real-time compute speed multiplier across the AI pipeline */
    val pipelineRealTimeSpeed: Double
        get() = if (totalComputeMs > 0L && totalAudioSeconds > 0.0) {
            (totalAudioSeconds * 1000.0) / totalComputeMs
        } else 0.0

    /** Average post-speech turnaround latency from speech stop to text insertion */
    val averageLatencyMs: Long
        get() = if (totalLatencyCount > 0) totalLatencyMs / totalLatencyCount else 0L

    /** Aggregate end-to-end real-time speed multiplier across tracked utterances */
    val e2eRealTimeSpeed: Double
        get() = if (totalWallClockMs > 0L && totalAudioSeconds > 0.0) {
            (totalAudioSeconds * 1000.0) / totalWallClockMs
        } else 0.0
}

class UsageStatsStore(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)
    private val file = File(filesDir, "usage_stats.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): UsageStatsData {
        if (!file.exists()) return UsageStatsData()
        return try {
            val loaded = json.decodeFromString<UsageStatsData>(file.readText())
            val asrAudioSec = if (loaded.totalAsrAudioSeconds > 0.0) loaded.totalAsrAudioSeconds else {
                loaded.recentRecords.filter { it.asrDurationMs > 0L }.sumOf { it.durationSeconds }.takeIf { it > 0.0 } ?: loaded.totalAudioSeconds
            }
            val llmAudioSec = if (loaded.totalLlmAudioSeconds > 0.0) loaded.totalLlmAudioSeconds else {
                loaded.recentRecords.filter { it.llmDurationMs > 0L }.sumOf { it.durationSeconds }
            }
            loaded.copy(
                totalAsrAudioSeconds = asrAudioSec,
                totalLlmAudioSeconds = llmAudioSec,
            )
        } catch (_: Exception) {
            UsageStatsData()
        }
    }

    @Synchronized
    fun record(
        durationSeconds: Double,
        rawWords: Int,
        wordsEdited: Int,
        engine: String,
        deliveredWords: Int = wordsEdited,
        wordsRemoved: Int = 0,
        wordsChanged: Int = 0,
        wordsAdded: Int = 0,
        asrDurationMs: Long = 0L,
        llmDurationMs: Long = 0L,
        wallClockMs: Long = 0L,
        latencyMs: Long = 0L,
    ) {
        val current = load()
        val newRecord = UtteranceRecord(
            id = System.currentTimeMillis().toString(),
            timestampMs = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            rawWords = rawWords,
            wordsEdited = wordsEdited,
            engine = engine,
            deliveredWords = deliveredWords,
            wordsRemoved = wordsRemoved,
            wordsChanged = wordsChanged,
            wordsAdded = wordsAdded,
            asrDurationMs = asrDurationMs,
            llmDurationMs = llmDurationMs,
            wallClockMs = wallClockMs,
            latencyMs = latencyMs,
        )
        val updated = current.copy(
            totalAudioSeconds = current.totalAudioSeconds + durationSeconds,
            totalRawWords = current.totalRawWords + rawWords,
            totalWordsEdited = current.totalWordsEdited + wordsEdited,
            totalUtterances = current.totalUtterances + 1,
            recentRecords = (listOf(newRecord) + current.recentRecords).take(50),
            totalDeliveredWords = current.totalDeliveredWords + deliveredWords,
            totalWordsRemoved = current.totalWordsRemoved + wordsRemoved,
            totalWordsChanged = current.totalWordsChanged + wordsChanged,
            totalWordsAdded = current.totalWordsAdded + wordsAdded,
            totalAsrActiveMs = current.totalAsrActiveMs + asrDurationMs,
            totalLlmActiveMs = current.totalLlmActiveMs + llmDurationMs,
            totalWallClockMs = current.totalWallClockMs + wallClockMs,
            totalAsrAudioSeconds = current.totalAsrAudioSeconds + (if (asrDurationMs > 0L) durationSeconds else 0.0),
            totalLlmAudioSeconds = current.totalLlmAudioSeconds + (if (llmDurationMs > 0L) durationSeconds else 0.0),
            totalLatencyMs = current.totalLatencyMs + latencyMs,
            totalLatencyCount = current.totalLatencyCount + (if (latencyMs > 0L) 1 else 0),
        )
        try {
            file.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {}
    }
}
