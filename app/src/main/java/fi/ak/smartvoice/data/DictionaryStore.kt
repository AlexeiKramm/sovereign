package fi.ak.smartvoice.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A custom term with optional phonetic variants or common misspellings to guide LLM cleanup.
 */
@Serializable
data class DictionaryEntry(
    val word: String,
    val aliases: List<String> = emptyList(),
)

@Serializable
data class DictionaryData(
    val entries: List<DictionaryEntry> = emptyList(),
    val words: List<String> = emptyList(),
)

/**
 * JSON-backed persistence for user-defined words and domain-specific terminology.
 */
class DictionaryStore(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file = File(storageDir, "dictionary.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): List<DictionaryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) return emptyList()
            val data = json.decodeFromString<DictionaryData>(text)
            if (data.entries.isNotEmpty()) {
                data.entries.sortedBy { it.word.lowercase() }
            } else if (data.words.isNotEmpty()) {
                data.words.map { DictionaryEntry(it, emptyList()) }.sortedBy { it.word.lowercase() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getEntries(): List<DictionaryEntry> = load()

    @Synchronized
    fun getWords(): List<String> = load().map { it.word }

    @Synchronized
    fun addEntry(word: String, aliases: List<String> = emptyList()) {
        val trimmed = word.trim()
        if (trimmed.isBlank()) return
        val current = load().toMutableList()
        val cleanedAliases = aliases.map { it.trim() }.filter { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }.distinct()

        val existingIdx = current.indexOfFirst { it.word.equals(trimmed, ignoreCase = true) }
        if (existingIdx >= 0) {
            val combinedAliases = (current[existingIdx].aliases + cleanedAliases).distinct()
            current[existingIdx] = DictionaryEntry(current[existingIdx].word, combinedAliases)
        } else {
            current.add(DictionaryEntry(trimmed, cleanedAliases))
        }
        current.sortBy { it.word.lowercase() }
        save(current)
    }

    @Synchronized
    fun addWord(word: String) {
        addEntry(word, emptyList())
    }

    @Synchronized
    fun removeEntry(word: String) {
        val trimmed = word.trim()
        val current = load().filterNot { it.word.equals(trimmed, ignoreCase = true) }
        save(current)
    }

    @Synchronized
    fun removeWord(word: String) {
        removeEntry(word)
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
            words = entries.map { it.word },
        )
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(data), Charsets.UTF_8)
    }
}
