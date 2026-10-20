package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode

/** The words for the random modes; the persisted value stays a plain id (see [RandomMode]). */
val RandomMode.labelRes: Int
    @StringRes get() = when (this) {
        RandomMode.CLASSIC -> R.string.random_mode_classic
        RandomMode.FAST -> R.string.random_mode_fast
    }
