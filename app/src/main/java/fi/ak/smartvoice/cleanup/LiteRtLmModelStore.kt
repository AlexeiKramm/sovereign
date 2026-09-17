package fi.ak.smartvoice.cleanup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the LiteRT-LM cleanup models live on disk. Downloading is not this object's business any
 * more: the shared `download/ArtifactDownloader` handles both model families, so there is exactly
 * one resumable-transfer implementation to reason about.
 */
object LiteRtLmModelStore {

    fun getDirectory(context: Context): File {
        val dir = File(context.filesDir, "models/litertlm")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(context: Context, spec: LiteRtLmModelSpec): File {
        return File(getDirectory(context), spec.fileName)
    }

    fun isInstalled(context: Context, spec: LiteRtLmModelSpec): Boolean {
        val file = getModelFile(context, spec)
        return file.isFile && file.length() == spec.approximateBytes
    }

    suspend fun remove(context: Context, spec: LiteRtLmModelSpec): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = getModelFile(context, spec)
                val part = File(getDirectory(context), "${spec.fileName}.part")
                if (file.exists()) file.delete()
                if (part.exists()) part.delete()
                Unit
            }
        }
}
