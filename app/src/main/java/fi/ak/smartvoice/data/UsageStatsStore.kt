package fi.ak.smartvoice.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UtteranceRecord(
    val id: String,
    val timestampMs: Long,
    val durationSeconds: Double,
    val rawWords: Int,
    val cleanedWords: Int,
    val engine: String,
    val deliveredWords: Int = cleanedWords,
    val wordsRemoved: Int = 0,
    val wordsChanged: Int = 0,
    val wordsAdded: Int = 0,
) {
    val wordsCleaned: Int
        get() = if (cleanedWords > 0) cleanedWords else (wordsRemoved + wordsChanged)
}

@Serializable
data class UsageStatsData(
    val totalAudioSeconds: Double = 0.0,
    val totalRawWords: Int = 0,
    val totalCleanedWords: Int = 0,
    val totalUtterances: Int = 0,
    val recentRecords: List<UtteranceRecord> = emptyList(),
    val totalDeliveredWords: Int = 0,
    val totalWordsRemoved: Int = 0,
    val totalWordsChanged: Int = 0,
    val totalWordsAdded: Int = 0,
) {
    val totalWordsCleaned: Int
        get() = totalCleanedWords

    /** Approximate minutes saved typing assuming 40 WPM average typing speed. */
    val typingMinutesSaved: Double
        get() {
            val words = if (totalDeliveredWords > 0) totalDeliveredWords else totalCleanedWords
            return (words.toDouble() / 40.0)
        }
}

class UsageStatsStore(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)
    private val file = File(filesDir, "usage_stats.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): UsageStatsData {
        if (!file.exists()) return UsageStatsData()
        return try {
            json.decodeFromString<UsageStatsData>(file.readText())
        } catch (_: Exception) {
            UsageStatsData()
        }
    }

    @Synchronized
    fun record(
        durationSeconds: Double,
        rawWords: Int,
        cleanedWords: Int,
        engine: String,
        deliveredWords: Int = cleanedWords,
        wordsRemoved: Int = 0,
        wordsChanged: Int = 0,
        wordsAdded: Int = 0,
    ) {
        val current = load()
        val newRecord = UtteranceRecord(
            id = System.currentTimeMillis().toString(),
            timestampMs = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            rawWords = rawWords,
            cleanedWords = cleanedWords,
            engine = engine,
            deliveredWords = deliveredWords,
            wordsRemoved = wordsRemoved,
            wordsChanged = wordsChanged,
            wordsAdded = wordsAdded,
        )
        val updated = current.copy(
            totalAudioSeconds = current.totalAudioSeconds + durationSeconds,
            totalRawWords = current.totalRawWords + rawWords,
            totalCleanedWords = current.totalCleanedWords + cleanedWords,
            totalUtterances = current.totalUtterances + 1,
            recentRecords = (listOf(newRecord) + current.recentRecords).take(50),
            totalDeliveredWords = current.totalDeliveredWords + deliveredWords,
            totalWordsRemoved = current.totalWordsRemoved + wordsRemoved,
            totalWordsChanged = current.totalWordsChanged + wordsChanged,
            totalWordsAdded = current.totalWordsAdded + wordsAdded,
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
