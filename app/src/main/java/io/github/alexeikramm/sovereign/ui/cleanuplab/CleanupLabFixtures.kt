package io.github.alexeikramm.sovereign.ui.cleanuplab

import android.content.Context
import io.github.alexeikramm.sovereign.ui.benchmark.DictationFixtures
import java.io.File
import java.io.InputStream

/**
 * A fixture the lab can replay. Short takes live in APK assets (they are small and committed); a
 * **long** take lives on the device at `filesDir/fixtures/<id>.wav` and is staged by adb, because a
 * multi-minute WAV is far too large to commit or bundle (see `PLAN.md` §Locked decisions).
 */
data class LabFixture(
    val id: String,
    val name: String,
    val assetPath: String?,
    val deviceRelativePath: String?,
    val durationSeconds: Float,
    val language: String,
) {
    fun deviceFile(context: Context): File? =
        deviceRelativePath?.let { File(context.filesDir, it) }

    /** True when the audio is actually reachable (asset present, or the device file exists). */
    fun exists(context: Context): Boolean =
        assetPath != null || deviceFile(context)?.isFile == true

    fun open(context: Context): InputStream {
        val asset = assetPath
        if (asset != null) return context.assets.open(asset)
        val file = deviceFile(context)
            ?: error("${name} has neither an asset nor a device file")
        check(file.isFile) { "Device fixture not staged: ${file.absolutePath}" }
        return file.inputStream()
    }
}

object CleanupLabFixtures {
    const val LONG_ID = "d09_long_dictation"
    const val LONG_RELATIVE_PATH = "fixtures/$LONG_ID.wav"

    /** Measured from the staged WAV (ffprobe): 33:20.9. */
    const val LONG_DURATION_SECONDS = 2000.856f

    fun long(): LabFixture = LabFixture(
        id = LONG_ID,
        name = "Long — spontaneous dictation (33 min)",
        assetPath = null,
        deviceRelativePath = LONG_RELATIVE_PATH,
        durationSeconds = LONG_DURATION_SECONDS,
        language = "en",
    )

    /** The long fixture first (it is the point of the lab), then the committed short takes. */
    fun all(context: Context): List<LabFixture> {
        val short = DictationFixtures.FIXTURES.map { fixture ->
            LabFixture(
                id = fixture.id,
                name = fixture.name,
                assetPath = fixture.assetWavPath,
                deviceRelativePath = null,
                durationSeconds = fixture.durationSeconds,
                language = fixture.language,
            )
        }
        return listOf(long()) + short
    }
}
