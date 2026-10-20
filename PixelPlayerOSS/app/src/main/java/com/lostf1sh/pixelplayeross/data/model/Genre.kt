package com.lostf1sh.pixelplayeross.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Genre(
    val id: String,
    val name: String,
    val iconResId: Int? = null,
    val lightColorHex: String? = null,
    val onLightColorHex: String? = null,
    val darkColorHex: String? = null,
    val onDarkColorHex: String? = null
) {
    companion object {
        const val UNKNOWN_ID = "unknown"
        const val UNKNOWN_NAME = "Unknown"

        /**
         * The id a genre has in the library (and so in the genre page's route): the lower-case name with spaces and slashes as underscores,
         * and "unknown" for the unknown genre. One definition for the repository that lists the genres and for whatever has to name one
         * (B32-Q2: Stats opens a genre by the name its rows carry).
         */
        fun idFor(name: String): String =
            if (name.isBlank() || name.equals(UNKNOWN_NAME, ignoreCase = true)) UNKNOWN_ID
            else name.lowercase().replace(" ", "_").replace("/", "_")
    }
}
