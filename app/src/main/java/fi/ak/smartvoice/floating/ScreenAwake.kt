package fi.ak.smartvoice.floating

/**
 * Whether the pill should hold the display awake (D-050).
 *
 * The service owns the window and the `FLAG_KEEP_SCREEN_ON` bit; this owns only the *decision*, so
 * the one rule that matters — the screen is held on exactly while the microphone is capturing and
 * the user has left the setting on — is checkable in a JVM test instead of by timing a phone's
 * screen timeout. The `ModelResidency` discipline (`engine/ModelResidency.kt`).
 */
object ScreenAwake {

    /**
     * The hold is on **only** while a capture is live. A review card, a finalising utterance and a
     * loaded-but-idle engine are all "not recording": the pill is a hands-free surface, and the
     * screen has no reason to stay on once the user has stopped talking.
     */
    fun shouldHold(recording: Boolean, enabled: Boolean): Boolean = recording && enabled
}
