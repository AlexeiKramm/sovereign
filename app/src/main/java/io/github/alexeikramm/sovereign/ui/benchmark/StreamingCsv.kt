package io.github.alexeikramm.sovereign.ui.benchmark

import android.content.Context
import android.util.Log
import io.github.alexeikramm.sovereign.asr.AsrModels
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The benchmark's durable record. [append] writes one row per cell; [parse] turns the accumulated
 * file back into results, so a long unattended run can still be reviewed after the app restarts.
 *
 * The header is derived from [COLUMNS], so a schema change cannot desync the header and the rows:
 * an existing file whose first line differs is rotated aside before the first new row is written.
 */
object StreamingCsv {
    const val FILE_NAME = "streaming_benchmark.csv"

    private const val TAG = "StreamingBenchmark"

    private val COLUMNS = listOf(
        "timestamp", "asr_model_id", "cleanup", "pause_s", "healing", "cold", "fixture",
        "audio_dur_s", "units", "first_text_ms", "dead_air_ms", "endpoint_gating_ms",
        "cleanup_lag_ms", "tail_ms", "asr_active_ms", "llm_active_ms", "wall_ms",
        "visible_cadence_ms", "partial_cadence_ms", "committed_text", "raw_text",
    )
    val HEADER: String = COLUMNS.joinToString(",")

    /** The accumulated CSV (all sessions), or null until a row has been written. */
    fun file(context: Context): File? =
        File(context.filesDir, FILE_NAME).takeIf { it.isFile && it.length() > 0L }

    fun append(context: Context, result: StreamingCellResult) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.isFile && file.length() > 0L && firstLine(file) != HEADER) rotate(file)
        val isNew = !file.isFile || file.length() == 0L
        val line = encode(result)
        runCatching { file.appendText(if (isNew) "$HEADER\n$line\n" else "$line\n") }
            .onFailure { Log.e(TAG, "Failed to write $FILE_NAME: ${it.message}") }
        Log.i(TAG, "CSV: $line")
    }

    /** One CSV row for [result] (no newline). [parse] of a file containing it rebuilds the result. */
    fun encode(result: StreamingCellResult): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        return listOf(
            stamp,
            escape(result.cell.asrModel.id),
            result.cell.cleanup.toString(),
            fmt(result.cell.pauseSeconds),
            result.cell.healingSentences.toString(),
            result.cell.cold.toString(),
            result.fixtureId,
            fmt(result.audioDurationSec),
            result.unitCount.toString(),
            result.timeToFirstTextMs.toString(),
            result.deadAirMs.toString(),
            result.endpointGatingMs.toString(),
            result.cleanupLagMs.toString(),
            result.tailLatencyMs.toString(),
            result.asrActiveMs.toString(),
            result.llmActiveMs.toString(),
            result.wallMs.toString(),
            result.visibleCadenceMs.joinToString(" ") { it.toString() },
            result.partialCadenceMs.joinToString(" ") { it.toString() },
            escape(result.committedText),
            escape(result.rawText),
        ).joinToString(",")
    }

    /** Rows written by [encode] (the header row is ignored); unparseable rows are dropped. */
    fun parse(text: String): List<StreamingCellResult> {
        val rows = parseRows(text)
        if (rows.size < 2) return emptyList()
        val index = rows.first().withIndex().associate { (i, name) -> name to i }
        return rows.drop(1).mapNotNull { fields -> toResult(fields, index) }
    }

    private fun toResult(fields: List<String>, index: Map<String, Int>): StreamingCellResult? {
        fun value(name: String): String? = index[name]?.let { fields.getOrNull(it) }
        fun long(name: String): Long = value(name)?.toLongOrNull() ?: -1L
        fun longs(name: String): List<Long> =
            value(name).orEmpty().split(' ').mapNotNull { it.trim().toLongOrNull() }

        val modelId = value("asr_model_id") ?: return null
        return StreamingCellResult(
            cell = StreamingCell(
                asrModel = AsrModels.byId(modelId),
                cleanup = value("cleanup").toBoolean(),
                pauseSeconds = value("pause_s")?.toFloatOrNull() ?: 0f,
                healingSentences = value("healing")?.toIntOrNull() ?: 0,
                cold = value("cold").toBoolean(),
            ),
            fixtureId = value("fixture").orEmpty(),
            audioDurationSec = value("audio_dur_s")?.toFloatOrNull() ?: 0f,
            unitCount = value("units")?.toIntOrNull() ?: 0,
            timeToFirstTextMs = long("first_text_ms"),
            deadAirMs = long("dead_air_ms"),
            endpointGatingMs = long("endpoint_gating_ms"),
            cleanupLagMs = long("cleanup_lag_ms"),
            visibleCadenceMs = longs("visible_cadence_ms"),
            commitCadenceMs = longs("visible_cadence_ms"),
            partialCadenceMs = longs("partial_cadence_ms"),
            tailLatencyMs = long("tail_ms"),
            asrActiveMs = long("asr_active_ms"),
            llmActiveMs = long("llm_active_ms"),
            wallMs = long("wall_ms"),
            committedText = value("committed_text").orEmpty(),
            rawText = value("raw_text").orEmpty(),
        )
    }

    /** RFC4180-style row split: quoted fields may contain commas, newlines and doubled quotes. */
    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString())
                        field.clear()
                    }
                    '\n' -> {
                        row.add(field.toString())
                        field.clear()
                        rows.add(row.toList())
                        row.clear()
                    }
                    '\r' -> Unit
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }

    private fun firstLine(file: File): String? =
        runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()

    private fun rotate(file: File) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(file.parentFile, "streaming_benchmark_$stamp.csv")
        runCatching { file.renameTo(target) }
            .onFailure { Log.w(TAG, "Could not rotate $FILE_NAME: ${it.message}") }
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
