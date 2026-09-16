package fi.ak.smartvoice.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A term the recogniser should get right: the [correct] spelling, plus any mishearings
 * ([incorrect]) that should be replaced with it. An entry with no mishearings is a plain
 * "always spell this exactly" term.
 */
@Serializable
data class DictionaryEntry(
    val correct: String,
    val incorrect: List<String> = emptyList(),
)

/**
 * One line in the shared dictionary editor:
 * `incorrect -> correct`, or a bare "always spell" term when [incorrect] is null.
 */
data class DictionaryRow(
    val incorrect: String?,
    val correct: String,
)

/** Expands stored entries into the rows the editor shows. Pure, so the shape is pinned by tests. */
object DictionaryRows {
    fun of(entries: List<DictionaryEntry>): List<DictionaryRow> = entries.flatMap { entry ->
        if (entry.incorrect.isEmpty()) {
            listOf(DictionaryRow(incorrect = null, correct = entry.correct))
        } else {
            entry.incorrect.map { DictionaryRow(incorrect = it, correct = entry.correct) }
        }
    }
}

@Serializable
data class DictionaryData(
    val entries: List<DictionaryEntry> = emptyList(),
    val words: List<String> = emptyList(),
)

/**
 * JSON-backed persistence for user-defined words and domain-specific terminology.
 *
 * The on-disk shape is [DictionaryEntry] (`correct` + `incorrect`). Files written by older
 * builds used `word` + `aliases`; [RawEntry] reads both so an upgrade never discards the
 * user's dictionary.
 */
class DictionaryStore(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file = File(storageDir, "dictionary.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Reads the current on-disk shape and the pre-rename `word`/`aliases` shape into one model. */
    @Serializable
    private data class RawEntry(
        val correct: String? = null,
        val incorrect: List<String> = emptyList(),
        val word: String? = null,
        val aliases: List<String> = emptyList(),
    )

    @Serializable
    private data class RawDictionaryData(
        val entries: List<RawEntry> = emptyList(),
        val words: List<String> = emptyList(),
    )

    @Synchronized
    fun load(): List<DictionaryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) return emptyList()
            val data = json.decodeFromString<RawDictionaryData>(text)
            val merged = LinkedHashMap<String, DictionaryEntry>()
            data.entries.forEach { raw ->
                val correct = (raw.correct ?: raw.word)?.trim().orEmpty()
                if (correct.isNotBlank()) merge(merged, correct, raw.incorrect + raw.aliases)
            }
            data.words.forEach { word -> if (word.isNotBlank()) merge(merged, word.trim(), emptyList()) }
            merged.values.sortedBy { it.correct.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun merge(
        into: LinkedHashMap<String, DictionaryEntry>,
        correct: String,
        incorrect: List<String>,
    ) {
        val key = correct.lowercase()
        val heard = ((into[key]?.incorrect ?: emptyList()) + incorrect)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(correct, ignoreCase = true) }
            .distinct()
        into[key] = DictionaryEntry(into[key]?.correct ?: correct, heard)
    }

    @Synchronized
    fun getEntries(): List<DictionaryEntry> = load()

    @Synchronized
    fun getWords(): List<String> = load().map { it.correct }

    /**
     * Adds or merges a correction. A blank [incorrect] list stores a bare "always spell" term;
     * adding another mishearing to an existing [correct] merges rather than replacing it.
     */
    @Synchronized
    fun addCorrection(correct: String, incorrect: List<String> = emptyList()) {
        val trimmedCorrect = correct.trim()
        if (trimmedCorrect.isBlank()) return
        val current = load().toMutableList()
        val cleaned = incorrect.map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(trimmedCorrect, ignoreCase = true) }
            .distinct()

        val existingIdx = current.indexOfFirst { it.correct.equals(trimmedCorrect, ignoreCase = true) }
        if (existingIdx >= 0) {
            current[existingIdx] = current[existingIdx].copy(
                incorrect = (current[existingIdx].incorrect + cleaned).distinct(),
            )
        } else {
            current.add(DictionaryEntry(trimmedCorrect, cleaned))
        }
        current.sortBy { it.correct.lowercase() }
        save(current)
    }

    /** Removes a whole entry, including any mishearings attached to it. */
    @Synchronized
    fun removeEntry(correct: String) {
        val trimmed = correct.trim()
        save(load().filterNot { it.correct.equals(trimmed, ignoreCase = true) })
    }

    /**
     * Removes one mishearing. When it was the entry's last one the entry is removed too, so a
     * row the user deletes does not leave a surprise "always spell" line behind.
     */
    @Synchronized
    fun removeCorrection(correct: String, incorrect: String) {
        val trimmedCorrect = correct.trim()
        val trimmedIncorrect = incorrect.trim()
        val updated = load().mapNotNull { entry ->
            if (!entry.correct.equals(trimmedCorrect, ignoreCase = true)) return@mapNotNull entry
            val remaining = entry.incorrect.filterNot { it.equals(trimmedIncorrect, ignoreCase = true) }
            if (remaining.isEmpty()) null else entry.copy(incorrect = remaining)
        }
        save(updated)
    }

    @Synchronized
    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun save(entries: List<DictionaryEntry>) {
        val data = DictionaryData(
            entries = entries,
            words = entries.map { it.correct },
        )
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(data), Charsets.UTF_8)
    }
}
