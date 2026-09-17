package fi.ak.smartvoice.download

/**
 * Keys for the `Data` carried by a download work item. All of them live here so the request, the
 * progress and the failure reason cannot drift apart across the worker and the UI.
 */
internal object DownloadKeys {
    const val KIND = "kind"
    const val REQUEST_ID = "requestId"

    const val OVERALL = "overall"
    const val FILE_NAME = "fileName"
    const val FILE_INDEX = "fileIndex"
    const val COMPLETED_FILES = "completedFiles"
    const val TOTAL_FILES = "totalFiles"
    const val DOWNLOADED_BYTES = "downloadedBytes"
    const val TOTAL_BYTES = "totalBytes"
    const val STAGE = "stage"

    const val REASON = "reason"
}
