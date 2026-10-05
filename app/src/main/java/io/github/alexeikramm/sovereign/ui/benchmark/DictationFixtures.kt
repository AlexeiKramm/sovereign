package io.github.alexeikramm.sovereign.ui.benchmark

/**
 * Recorded dictation fixtures (SB-2): audio that behaves like real dictation — fillers,
 * self-corrections, spoken lists and numbers — which the LibriSpeech prose fixtures cannot exercise.
 *
 * The WAVs live in `fixtures/en/dictation/` (wired into APK assets through the `fixtures`
 * directory in `app/build.gradle.kts`) and are documented in `metadata.json` beside them.
 * `DictationFixturesTest` asserts this list, the scripts and `metadata.json` agree.
 *
 * The streaming benchmark sweeps every take; the unified benchmark runs `d08_pausing` alone
 * (`BenchmarkEngine.FIXTURE_ID`).
 */
data class DictationFixture(
    val id: String,
    val name: String,
    val assetWavPath: String,
    val durationSeconds: Float,
    /** One of `n/a`, `pausing`, `continuous`. */
    val pauseDensity: String,
    val features: List<String>,
    /** Ground-truth transcription, mirrored from `metadata.json`; seeds the LLM-only prompt. */
    val script: String,
    /** Language of the take and its asset directory. */
    val language: String = "en",
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
            script = "Okay, thanks.",
        ),
        DictationFixture(
            id = "d02_short_command",
            name = "Short — command",
            assetWavPath = "en/dictation/d02_short_command.wav",
            durationSeconds = 3.330f,
            pauseDensity = "pausing",
            features = listOf("numbers", "proper-nouns"),
            script = "Remind me to call Alex tomorrow at nine.",
        ),
        DictationFixture(
            id = "d03_fillers_selfcorrection",
            name = "Medium — fillers and self-correction",
            assetWavPath = "en/dictation/d03_fillers_selfcorrection.wav",
            durationSeconds = 12.150f,
            pauseDensity = "pausing",
            features = listOf("fillers", "self-correction"),
            script = "So, um, I was thinking that we should, uh, you know, move the meeting to Thursday. Actually, no — let's do Friday at two p.m.",
        ),
        DictationFixture(
            id = "d04_spoken_list",
            name = "Medium — spoken list",
            assetWavPath = "en/dictation/d04_spoken_list.wav",
            durationSeconds = 10.420f,
            pauseDensity = "pausing",
            features = listOf("list"),
            script = "Here's the plan. First, we draft the proposal. Second, we send it to Sarah. And third, we follow up next week.",
        ),
        DictationFixture(
            id = "d05_numbers_dates",
            name = "Medium — numbers and dates",
            assetWavPath = "en/dictation/d05_numbers_dates.wav",
            durationSeconds = 12.350f,
            pauseDensity = "pausing",
            features = listOf("numbers", "dates"),
            script = "The invoice is for one thousand two hundred and fifty dollars, dated March third, twenty twenty-six, and it's due in thirty days.",
        ),
        DictationFixture(
            id = "d06_proper_nouns",
            name = "Medium — proper nouns",
            assetWavPath = "en/dictation/d06_proper_nouns.wav",
            durationSeconds = 9.060f,
            pauseDensity = "pausing",
            features = listOf("proper-nouns"),
            script = "I met Doctor Kowalski in Helsinki last Tuesday, and he mentioned the project with Acme Corporation.",
        ),
        DictationFixture(
            id = "d07_continuous",
            name = "Long — continuous, no pauses",
            assetWavPath = "en/dictation/d07_continuous.wav",
            durationSeconds = 19.320f,
            pauseDensity = "continuous",
            features = listOf("fillers", "self-correction", "list", "numbers", "dates", "proper-nouns"),
            script = "Alright, so, um, here's the update from the, uh, the Helsinki meeting with Doctor Kowalski. First, we agreed that the budget is two hundred and fifty thousand euros. Second, the deadline moves to March third, twenty twenty-six. And third, Sarah will own the rollout — no wait, actually Anna will own the rollout. So yeah, that's the update, thanks.",
        ),
        DictationFixture(
            id = "d08_pausing",
            name = "Long — pausing between sentences",
            assetWavPath = "en/dictation/d08_pausing.wav",
            durationSeconds = 32.179f,
            pauseDensity = "pausing",
            features = listOf("fillers", "self-correction", "list", "numbers", "dates", "proper-nouns"),
            script = "Alright, so, um, here's the update from the, uh, the Helsinki meeting with Doctor Kowalski. First, we agreed that the budget is two hundred and fifty thousand euros. Second, the deadline moves to March third, twenty twenty-six. And third, Sarah will own the rollout — no wait, actually Anna will own the rollout. So yeah, that's the update, thanks.",
        ),
    )
}
