package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R

/**
 * Spanish names for the default genre suggestions whose name differs from the canonical English key.
 *
 * `GenreIconProvider.DEFAULT_GENRES` holds the canonical genre strings: they are matched against the
 * library's tags and can be written as a custom genre, so they must not change. Genres whose name is
 * already correct in Spanish (Rock, Pop, Jazz, Salsa…) have no entry here and are drawn as-is.
 */
@StringRes
fun defaultGenreLabelRes(genre: String): Int? = when (genre) {
    "Classical" -> R.string.genre_classical
    "Electronic" -> R.string.genre_electronic
    "Alternative" -> R.string.genre_alternative
    "Reggaeton" -> R.string.genre_reggaeton
    "Oldies" -> R.string.genre_oldies
    "Soundtrack" -> R.string.genre_soundtrack
    "Gaming" -> R.string.genre_gaming
    "Sleep" -> R.string.genre_sleep
    "Workout" -> R.string.genre_workout
    "Party" -> R.string.genre_party
    "Focus" -> R.string.genre_focus
    "Gospel" -> R.string.genre_gospel
    "Children's" -> R.string.genre_children
    "World" -> R.string.genre_world
    "Dance" -> R.string.genre_dance
    "Easy Listening" -> R.string.genre_easy_listening
    else -> null
}
