package io.github.alexeikramm.sovereign.ui.cleanuplab

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The lab's durable record: one row per **run** (aggregate metrics) and one per **unit** (raw, cleaned,
 * and the context position it ran at), so the whole quality-vs-context analysis can be done off-device.
 *
 * Both files are schema-versioned by their header — a file whose first line differs is rotated aside
 * before the first new row is written, so a schema change cannot silently desync columns.
 */
object CleanupLabCsv {
    const val RUN_FILE = "cleanup_lab_runs.csv"
    const val UNIT_FILE = "cleanup_lab_units.csv"

    private const val TAG = "CleanupLab"

    private val RUN_COLUMNS = listOf(
        "timestamp", "fixture_id", "asr_model", "pause_s", "llm_model", "max_turns", "healing",
        "profile", "units", "failed_units", "raw_words", "cleaned_words", "removed", "changed",
        "added", "raw_fillers", "leftover_fillers", "filler_removal", "raw_stutters",
        "leftover_stutters", "content_removed", "digits_cleaned", "wall_ms", "asr_active_ms",
        "llm_active_ms", "raw_text", "cleaned_text",
    )
    private val UNIT_COLUMNS = listOf(
        "timestamp", "fixture_id", "llm_model", "max_turns", "healing", "unit_index",
        "audio_start_s", "audio_end_s", "tokens_before", "tokens_after", "turns_in_context",
        "rebuilt_after", "rebuild_reason", "cleaned_ok", "active_ms", "ttft_ms", "decode_ms",
        "prompt_tokens", "generated_tokens", "raw_unit", "model_input", "cleaned_unit",
    )

    val RUN_HEADER: String = RUN_COLUMNS.joinToString(",")
    val UNIT_HEADER: String = UNIT_COLUMNS.joinToString(",")

    fun runsFile(context: Context): File? = existing(context, RUN_FILE)
    fun unitsFile(context: Context): File? = existing(context, UNIT_FILE)

    private fun existing(context: Context, name: String): File? =
        File(context.filesDir, name).takeIf { it.isFile && it.length() > 0L }

    /** Appends the aggregate row and every per-unit row for [result]. */
    fun append(context: Context, result: LabRunResult) {
        appendRow(context, RUN_FILE, RUN_HEADER, encodeRun(result))
        for (unit in result.units) {
            appendRow(context, UNIT_FILE, UNIT_HEADER, encodeUnit(result, unit))
        }
        Log.i(TAG, "stored run ${result.config.label}: ${result.units.size} units")
    }

    fun encodeRun(result: LabRunResult): String {
        val m = result.metrics
        return listOf(
            stamp(),
            escape(result.config.fixtureId),
            escape(result.config.asrModelId),
            fmt(result.config.pauseSeconds.toDouble()),
            escape(result.config.llmModelId),
            result.config.maxTurns.toString(),
            result.config.healingSentences.toString(),
            escape(result.config.profileName),
            m.totalUnits.toString(),
            m.failedUnits.toString(),
            m.rawWords.toString(),
            m.cleanedWords.toString(),
            m.wordsRemoved.toString(),
            m.wordsChanged.toString(),
            m.wordsAdded.toString(),
            m.rawFillers.toString(),
            m.leftoverFillers.toString(),
            fmt(m.fillerRemovalRate),
            m.rawStutters.toString(),
            m.leftoverStutters.toString(),
            m.contentWordsRemoved.toString(),
            m.digitsInCleaned.toString(),
            result.wallMs.toString(),
            result.asrActiveMs.toString(),
            result.llmActiveMs.toString(),
            escape(result.rawText),
            escape(result.cleanedText),
        ).joinToString(",")
    }

    fun encodeUnit(result: LabRunResult, unit: LabUnitResult): String = listOf(
        stamp(),
        escape(result.config.fixtureId),
        escape(result.config.llmModelId),
        result.config.maxTurns.toString(),
        result.config.healingSentences.toString(),
        unit.index.toString(),
        fmt(unit.audioStartSeconds.toDouble()),
        fmt(unit.audioEndSeconds.toDouble()),
        unit.tokensBefore.toString(),
        unit.tokensAfter.toString(),
        unit.turnsInConversation.toString(),
        unit.rebuiltAfter.toString(),
        escape(unit.rebuildReason.orEmpty()),
        unit.cleanedOk.toString(),
        unit.activeMs.toString(),
        fmt(unit.ttftMs),
        fmt(unit.decodeTimeMs),
        unit.promptTokens.toString(),
        unit.generatedTokens.toString(),
        escape(unit.rawUnit),
        escape(unit.modelInput),
        escape(unit.cleanedUnit),
    ).joinToString(",")

    private fun appendRow(context: Context, name: String, header: String, line: String) {
        val file = File(context.filesDir, name)
        if (file.isFile && file.length() > 0L && firstLine(file) != header) rotate(file)
        val isNew = !file.isFile || file.length() == 0L
        runCatching { file.appendText(if (isNew) "$header\n$line\n" else "$line\n") }
            .onFailure { Log.e(TAG, "Failed to write $name: ${it.message}") }
        Log.i(TAG, "CSV $name: $line")
    }

    private fun firstLine(file: File): String? =
        runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()

    private fun rotate(file: File) {
        val target = File(file.parentFile, "${file.nameWithoutExtension}_${stampCompact()}.csv")
        runCatching { file.renameTo(target) }
            .onFailure { Log.w(TAG, "Could not rotate ${file.name}: ${it.message}") }
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    private fun stampCompact(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
