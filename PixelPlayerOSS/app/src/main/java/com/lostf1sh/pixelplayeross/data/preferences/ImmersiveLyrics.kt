package com.lostf1sh.pixelplayeross.data.preferences

/**
 * Immersive lyrics: after a quiet moment on the lyrics page the controls fold away and the text grows; any touch brings them back.
 *
 * They were hidden in 1.0 (B32-Q7) because the mode did not work, and are back in 1.1 (B40-F5). The gate stays, as the one place that says
 * whether the feature is offered: while [AVAILABLE] is false the answer to every stored "on" is "off", so a listener is never left in a mode
 * whose switch is gone, and every reader of the preference (the repository's flow, and the lyrics sheet, which asks again) goes through it.
 */
object ImmersiveLyrics {
    /** True from 1.1. */
    const val AVAILABLE: Boolean = true

    /** Whether immersive lyrics are in force, given what is stored. */
    fun effective(stored: Boolean, available: Boolean = AVAILABLE): Boolean = available && stored
}
