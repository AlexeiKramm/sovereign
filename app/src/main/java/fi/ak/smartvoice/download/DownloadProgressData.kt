package fi.ak.smartvoice.download

import androidx.work.Data

/** `DownloadProgress` → WorkManager `Data`, so progress survives process death with the work. */
fun DownloadProgress.toData(): Data = Data.Builder()
    .putFloat(DownloadKeys.OVERALL, overall)
    .putString(DownloadKeys.FILE_NAME, fileName)
    .putInt(DownloadKeys.FILE_INDEX, fileIndex)
    .putInt(DownloadKeys.COMPLETED_FILES, completedFiles)
    .putInt(DownloadKeys.TOTAL_FILES, totalFiles)
    .putLong(DownloadKeys.DOWNLOADED_BYTES, downloadedBytes)
    .putLong(DownloadKeys.TOTAL_BYTES, totalBytes)
    .build()

/** Null when the work has not written progress yet — which is what "queued, not started" looks like. */
fun downloadProgressFrom(data: Data): DownloadProgress? {
    val overall = data.getFloat(DownloadKeys.OVERALL, Float.NaN)
    if (overall.isNaN()) return null
    return DownloadProgress(
        overall = overall,
        fileName = data.getString(DownloadKeys.FILE_NAME).orEmpty(),
        fileIndex = data.getInt(DownloadKeys.FILE_INDEX, 0),
        completedFiles = data.getInt(DownloadKeys.COMPLETED_FILES, 0),
        totalFiles = data.getInt(DownloadKeys.TOTAL_FILES, 0),
        downloadedBytes = data.getLong(DownloadKeys.DOWNLOADED_BYTES, 0L),
        totalBytes = data.getLong(DownloadKeys.TOTAL_BYTES, 0L),
    )
}

/** The failure reason a terminal outcome leaves in the work's output data. */
fun downloadFailureData(reason: String): Data = Data.Builder()
    .putString(DownloadKeys.REASON, reason)
    .build()

fun downloadFailureReason(data: Data): String? = data.getString(DownloadKeys.REASON)
