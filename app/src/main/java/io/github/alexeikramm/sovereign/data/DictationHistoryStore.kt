package io.github.alexeikramm.sovereign.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DictationRecord(
    val id: String,
    val timestampMs: Long,
    val durationSeconds: Double,
    val rawText: String,
    val cleanedText: String,
    val asrModel: String,
    val llmModel: String? = null,
    val wordsCleaned: Int = 0,
    val insertedSuccessfully: Boolean = true,
)

@Serializable
data class DictationHistoryData(
    val records: List<DictationRecord> = emptyList(),
)

/**
 * Local, capped, clearable history storage for dictation transcripts.
 *
 * Privacy-first: if history is disabled in settings, callers do not invoke [record],
 * ensuring zero transcript text is written to disk.
 */
class DictationHistoryStore(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(filesDir, "dictation_history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): DictationHistoryData {
        if (!file.exists()) return DictationHistoryData()
        return try {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) DictationHistoryData() else json.decodeFromString<DictationHistoryData>(text)
        } catch (_: Exception) {
            DictationHistoryData()
        }
    }

    /**
     * Records a new utterance at the front of the list, evicting the oldest
     * records if the list exceeds [maxCapacity].
     */
    @Synchronized
    fun record(record: DictationRecord, maxCapacity: Int = DEFAULT_CAPACITY) {
        val current = load()
        val updated = (listOf(record) + current.records).take(maxCapacity.coerceAtLeast(1))
        save(DictationHistoryData(records = updated))
    }

    /** Deletes an individual record by its unique [id]. */
    @Synchronized
    fun delete(id: String) {
        val current = load()
        val updated = current.records.filterNot { it.id == id }
        if (updated.size != current.records.size) {
            save(DictationHistoryData(records = updated))
        }
    }

    /** Permanently deletes all stored history records from disk. */
    @Synchronized
    fun clear() {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun save(data: DictationHistoryData) {
        try {
            file.parentFile?.mkdirs()
            val tempFile = File(filesDir, "dictation_history.json.tmp")
            tempFile.writeText(json.encodeToString(data), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                // Fallback if atomic rename fails on some Android filesystems
                file.writeText(json.encodeToString(data), Charsets.UTF_8)
                tempFile.delete()
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}
