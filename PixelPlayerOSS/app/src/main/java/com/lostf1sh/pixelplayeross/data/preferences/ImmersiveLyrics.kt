package com.lostf1sh.pixelplayeross.data.preferences

/**
 * Immersive lyrics are **not offered in 1.0** (B32-Q7): hiding the controls and enlarging the text does not work yet, and is fixed for
 * 1.1. Everything that makes it work stays in the code (the setting's key and its timeout, the lyrics sheet's timer and its enlarged
 * layout, the view model's temporary switch); what is gone is every way to ask for it, and the answer the app gives to a stored "on".
 *
 * A listener who had it on keeps the value in the preference file, and it must not leave them in a mode they cannot turn off any more
 * because the switch that did is hidden. [effective] is the one place that says so: while [AVAILABLE] is false it answers "off" whatever
 * is stored, and every reader of the preference (the repository's flow, and the lyrics sheet, which asks again) goes through it. Turning
 * [AVAILABLE] on in 1.1 gives the stored choice back unchanged.
 */
object ImmersiveLyrics {
    /** False in 1.0. */
    const val AVAILABLE: Boolean = false

    /** Whether immersive lyrics are in force, given what is stored. */
    fun effective(stored: Boolean, available: Boolean = AVAILABLE): Boolean = available && stored
}
