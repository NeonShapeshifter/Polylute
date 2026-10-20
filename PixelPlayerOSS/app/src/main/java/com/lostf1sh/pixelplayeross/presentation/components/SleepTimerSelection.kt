package com.lostf1sh.pixelplayeross.presentation.components

/** A draft is inert until the user confirms it. Counted play repeats the current song. */
sealed interface SleepTimerSelection {
    data object Off : SleepTimerSelection
    data class Duration(val minutes: Int) : SleepTimerSelection { init { require(minutes in 1..1439) } }
    data object EndOfTrack : SleepTimerSelection
    data class RepeatCurrent(val count: Int) : SleepTimerSelection { init { require(count in 1..10) } }
}

internal fun customTimerMinutes(hours: String, minutes: String): Int? {
    val h = hours.toIntOrNull() ?: return null
    val m = minutes.toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return (h * 60 + m).takeIf { it > 0 }
}
