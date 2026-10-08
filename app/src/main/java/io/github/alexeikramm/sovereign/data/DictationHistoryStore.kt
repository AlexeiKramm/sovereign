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
data class DictationRecord(
    val id: String,
    val timestampMs: Long,
    val durationSeconds: Double,
    val rawText: String,
    val cleanedText: String,
    val asrModel: String,
    val llmModel: String? = null,
    /** Total word-level edits (removed + changed + added); the canonical count (D-124). */
    @JsonNames("wordsCleaned")
    val wordsEdited: Int = 0,
    val insertedSuccessfully: Boolean = true,
)

@Serializable
data class DictationHistoryData(
    val records: List<DictationRecord> = emptyList(),
)

/**
 * Local, capped, clearable history storage for dictation transcripts.
 *
 * Privacy-first: the file is sealed with a **non-exportable Android Keystore** key (the same
 * [KeystoreSecretCipher] the paired registry uses, under a distinct alias), so transcript text never
 * reaches disk in the clear. A pre-encryption plaintext file is imported and re-sealed on the next
 * write. If history is disabled in settings, callers do not invoke [record] at all.
 *
 * The file keeps its historical `.json` name; its contents are now a sealed binary blob.
 */
class DictationHistoryStore(
    private val filesDir: File,
    private val cipher: SecretCipher = KeystoreSecretCipher(HISTORY_KEY_ALIAS),
) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): DictationHistoryData {
        if (!file.exists()) return DictationHistoryData()
        return try {
            val bytes = file.readBytes()
            val plain = try {
                cipher.decrypt(bytes)
            } catch (_: Exception) {
                // A pre-encryption history file is still plain JSON; import it so it is not lost.
                if (looksLikeJson(bytes)) bytes else return DictationHistoryData()
            }
            val text = plain.toString(Charsets.UTF_8).trim()
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
            val plain = json.encodeToString(data).toByteArray(Charsets.UTF_8)
            // Refuse to fall back to plaintext: if sealing fails, do not write at all.
            val bytes = try {
                cipher.encrypt(plain)
            } catch (_: Exception) {
                return
            }
            val tempFile = File(filesDir, "$FILE_NAME.tmp")
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(file)) {
                // Fallback if atomic rename fails on some Android filesystems
                file.writeBytes(bytes)
                tempFile.delete()
            }
        } catch (_: Exception) {}
    }

    /** Whether [bytes] start (after whitespace) with a JSON container — a legacy plaintext file. */
    private fun looksLikeJson(bytes: ByteArray): Boolean {
        for (b in bytes) {
            when (b) {
                ' '.code.toByte(), '\t'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte() -> continue
                '{'.code.toByte(), '['.code.toByte() -> return true
                else -> return false
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
        const val FILE_NAME = "dictation_history.json"

        /** A Keystore alias distinct from the paired-registry key. */
        const val HISTORY_KEY_ALIAS = "sovereign_dictation_history"
    }
}
