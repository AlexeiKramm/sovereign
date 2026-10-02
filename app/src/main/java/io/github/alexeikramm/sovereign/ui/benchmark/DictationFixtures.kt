package io.github.alexeikramm.sovereign.ui.benchmark

/**
 * Recorded dictation fixtures (SB-2): audio that behaves like real dictation — fillers,
 * self-corrections, spoken lists and numbers — which the LibriSpeech prose fixtures cannot exercise.
 *
 * They are judged **by eye** (raw / cleaned / inline diff), so there is no ground truth;
 * [pauseDensity] and [features] describe what each take is for. The WAVs live in
 * `fixtures/en/dictation/` (wired into APK assets through the `fixtures` directory in
 * `app/build.gradle.kts`) and are documented in `metadata.json` beside them; `DictationFixturesTest`
 * asserts this list and that file agree.
 */
data class DictationFixture(
    val id: String,
    val name: String,
    val assetWavPath: String,
    val durationSeconds: Float,
    /** One of `n/a`, `pausing`, `continuous`. */
    val pauseDensity: String,
    val features: List<String>,
)

object DictationFixtures {
    val FIXTURES: List<DictationFixture> = listOf(
        DictationFixture(
            id = "d01_okay_thanks",
            name = "Short — acknowledgement",
            assetWavPath = "en/dictation/d01_okay_thanks.wav",
            durationSeconds = 1.340f,
            pauseDensity = "n/a",
            features = emptyList(),
        ),
        DictationFixture(
            id = "d02_short_command",
            name = "Short — command",
            assetWavPath = "en/dictation/d02_short_command.wav",
            durationSeconds = 3.330f,
            pauseDensity = "pausing",
            features = listOf("numbers", "proper-nouns"),
        ),
        DictationFixture(
            id = "d03_fillers_selfcorrection",
            name = "Medium — fillers and self-correction",
            assetWavPath = "en/dictation/d03_fillers_selfcorrection.wav",
            durationSeconds = 12.150f,
            pauseDensity = "pausing",
            features = listOf("fillers", "self-correction"),
        ),
        DictationFixture(
            id = "d04_spoken_list",
            name = "Medium — spoken list",
            assetWavPath = "en/dictation/d04_spoken_list.wav",
            durationSeconds = 10.420f,
            pauseDensity = "pausing",
            features = listOf("list"),
        ),
        DictationFixture(
            id = "d05_numbers_dates",
            name = "Medium — numbers and dates",
            assetWavPath = "en/dictation/d05_numbers_dates.wav",
            durationSeconds = 12.350f,
            pauseDensity = "pausing",
            features = listOf("numbers", "dates"),
        ),
        DictationFixture(
            id = "d06_proper_nouns",
            name = "Medium — proper nouns",
            assetWavPath = "en/dictation/d06_proper_nouns.wav",
            durationSeconds = 9.060f,
            pauseDensity = "pausing",
            features = listOf("proper-nouns"),
        ),
        DictationFixture(
            id = "d07_continuous",
            name = "Long — continuous, no pauses",
            assetWavPath = "en/dictation/d07_continuous.wav",
            durationSeconds = 19.320f,
            pauseDensity = "continuous",
            features = listOf("fillers", "self-correction", "list", "numbers", "dates", "proper-nouns"),
        ),
        DictationFixture(
            id = "d08_pausing",
            name = "Long — pausing between sentences",
            assetWavPath = "en/dictation/d08_pausing.wav",
            durationSeconds = 32.179f,
            pauseDensity = "pausing",
            features = listOf("fillers", "self-correction", "list", "numbers", "dates", "proper-nouns"),
        ),
    )
}
