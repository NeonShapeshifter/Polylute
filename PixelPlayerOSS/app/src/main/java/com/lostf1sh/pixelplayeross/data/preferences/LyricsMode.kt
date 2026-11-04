package com.lostf1sh.pixelplayeross.data.preferences

/**
 * B56 phase 3.4 (#24): how the lyrics show in the full player. Replaces the "Lyrics under the cover" on/off switch of B41.
 *
 *  - [OVER_COVER]: the two lines on the player page (today's switch on), and the Lyrics page;
 *  - [FULL_PAGE]: nothing on the player page (today's switch off), the Lyrics page stays;
 *  - [OFF]: nothing on the player page and **no Lyrics page**: the player is Menu, Player, Details.
 *
 * Stored under its own key ([storageKey]); the old boolean (`lyrics_under_cover_v1`) is read once, only while the new key does not exist yet
 * ([fromLegacyBoolean]): true is OVER_COVER, false is FULL_PAGE, absent is the default.
 */
enum class LyricsMode(val storageKey: String) {
    OVER_COVER("over_cover"),
    FULL_PAGE("full_page"),
    OFF("off");

    /** The two lines of synced lyrics on the player page. */
    val showsLinesOnPlayer: Boolean get() = this == OVER_COVER

    /** The Lyrics page of the player's pager. */
    val hasLyricsPage: Boolean get() = this != OFF

    companion object {
        val DEFAULT = OVER_COVER

        fun fromStorageKey(key: String?): LyricsMode? = entries.firstOrNull { it.storageKey == key }

        /** What the old on/off switch meant in the three-valued setting. */
        fun fromLegacyBoolean(underCover: Boolean?): LyricsMode = when (underCover) {
            false -> FULL_PAGE
            true, null -> OVER_COVER
        }
    }
}
