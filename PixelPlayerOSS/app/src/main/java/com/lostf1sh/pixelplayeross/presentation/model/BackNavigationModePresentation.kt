package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode

/**
 * The words for the Back-navigation modes.
 *
 * The mapping lives in the presentation layer rather than on the enum, so the persisted value stays
 * a plain string that a stored settings file can round-trip without dragging resource ids along with
 * it. The three labels are the three mode names and nothing more; each mode's *meaning* is the hint,
 * which the Settings row shows for whichever mode is currently selected.
 */
val BackNavigationMode.labelRes: Int
    @StringRes get() = when (this) {
        BackNavigationMode.BUTTONS -> R.string.back_navigation_mode_buttons
        BackNavigationMode.GESTURES -> R.string.back_navigation_mode_gestures
        BackNavigationMode.BOTH -> R.string.back_navigation_mode_both
    }

/** One line explaining what the mode does, in the mode's own terms. */
val BackNavigationMode.hintRes: Int
    @StringRes get() = when (this) {
        BackNavigationMode.BUTTONS -> R.string.back_navigation_mode_buttons_hint
        BackNavigationMode.GESTURES -> R.string.back_navigation_mode_gestures_hint
        BackNavigationMode.BOTH -> R.string.back_navigation_mode_both_hint
    }
