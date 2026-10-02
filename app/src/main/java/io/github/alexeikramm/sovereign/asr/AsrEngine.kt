package io.github.alexeikramm.sovereign.asr

/**
 * The recogniser seam for [LocalAsrSession], so the per-unit orchestration can be driven by a fake
 * under JUnit. [LocalAsrEngine] is the production implementation — the streaming transducer or the
 * offline VAD driver — and keeps the Android natives behind it.
 *
 * The signatures are deliberately the ones [LocalAsrSession] actually calls; `count` is explicit
 * rather than defaulted so an implementation cannot accidentally inherit a different contract.
 */
interface AsrEngine {
    fun load(): LocalAsrEngine.Availability

    fun startSession(language: String, hotwords: String)

    fun accept(samples: FloatArray, count: Int): String?

    fun isSpeechDetected(): Boolean

    fun isEndpoint(): Boolean

    fun result(): String

    fun resetSegment()

    fun finish(): String

    fun abort()
}
