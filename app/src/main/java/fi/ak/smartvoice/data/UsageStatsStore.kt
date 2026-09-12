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
)

@Serializable
data class UsageStatsData(
    val totalAudioSeconds: Double = 0.0,
    val totalRawWords: Int = 0,
    val totalCleanedWords: Int = 0,
    val totalUtterances: Int = 0,
    val recentRecords: List<UtteranceRecord> = emptyList(),
) {
    /** Approximate minutes saved typing assuming 40 WPM average typing speed. */
    val typingMinutesSaved: Double
        get() = (totalCleanedWords.toDouble() / 40.0)

    val editWordDelta: Int
        get() = totalCleanedWords - totalRawWords
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
    fun record(durationSeconds: Double, rawWords: Int, cleanedWords: Int, engine: String) {
        val current = load()
        val newRecord = UtteranceRecord(
            id = System.currentTimeMillis().toString(),
            timestampMs = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            rawWords = rawWords,
            cleanedWords = cleanedWords,
            engine = engine,
        )
        val updated = current.copy(
            totalAudioSeconds = current.totalAudioSeconds + durationSeconds,
            totalRawWords = current.totalRawWords + rawWords,
            totalCleanedWords = current.totalCleanedWords + cleanedWords,
            totalUtterances = current.totalUtterances + 1,
            recentRecords = (listOf(newRecord) + current.recentRecords).take(50),
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
