package io.github.alexeikramm.sovereign.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Cumulative ASR seam-revision counts (D-140).
 *
 * The streaming recogniser now runs continuously for a whole utterance (no per-pause reset). A
 * **revision** is the recogniser changing text it had already committed before the segment boundary.
 * We do not repair it — we do not yet know whether it happens at all — so we count it over a long
 * period and decide from the evidence. Should read 0 for a well-behaved greedy transducer.
 */
@Serializable
data class AsrRevisionData(
    /** Utterances on which at least one committed word was revised. */
    val revisions: Long = 0,
    /** Total characters of committed text changed across those revisions. */
    val divergedChars: Long = 0,
)

/** The cumulative store. Deliberately separate from usage stats: a raw-pipeline health counter. */
class AsrRevisionStore(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file = File(filesDir, "asr_revision_stats.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): AsrRevisionData {
        if (!file.exists()) return AsrRevisionData()
        return try {
            json.decodeFromString<AsrRevisionData>(file.readText())
        } catch (_: Exception) {
            AsrRevisionData()
        }
    }

    /** Adds one utterance's revision figures to the cumulative totals. */
    @Synchronized
    fun record(revisions: Long, divergedChars: Long) {
        if (revisions <= 0L && divergedChars <= 0L) return
        val current = load()
        val updated = current.copy(
            revisions = current.revisions + revisions,
            divergedChars = current.divergedChars + divergedChars,
        )
        try {
            file.writeText(json.encodeToString(updated))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
