package com.lostf1sh.pixelplayeross.presentation.navigation

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")

    /**
     * The Library, on its Songs tab, scrolled to one song and marked (B32-Q2). It is a destination with nothing of its own to draw: the
     * shell shows the Library root for as long as it is the top of the back stack, and Back leaves it for the page that asked (Stats).
     */
    object LibraryLocate : Screen("library_locate/{songId}") {
        fun createRoute(songId: String) = "library_locate/${Uri.encode(songId)}"
        const val PREFIX = "library_locate/"
    }
    object Settings : Screen("settings")
    object SettingsCategory : Screen("settings_category/{categoryId}") {
        fun createRoute(categoryId: String) = "settings_category/$categoryId"
    }
    object PaletteStyle : Screen("palette_style_settings")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/$playlistId"
    }

    object SmartCollections : Screen("smart_collections")

    /**
     * One automatic collection (Mix diario, En repetición...), by its stored key.
     *
     * A single parameterised route for all of them: they differ only in the rule that picks their songs.
     */
    object AutoCollection : Screen("auto_collection/{collectionKey}") {
        fun createRoute(collectionKey: String) = "auto_collection/$collectionKey"
    }
    object RecentlyPlayed : Screen("recently_played")
    object Stats : Screen("stats")
    object Duplicates : Screen("duplicates")
    object GenreDetail : Screen("genre_detail/{genreId}") {
        fun createRoute(genreId: String) = "genre_detail/$genreId"
    }
    object AlbumDetail : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: Long) = "album_detail/$albumId"
    }

    object ArtistDetail : Screen("artist_detail/{artistId}") {
        fun createRoute(artistId: Long) = "artist_detail/$artistId"
    }

    object EditTransition : Screen("edit_transition?playlistId={playlistId}") {
        fun createRoute(playlistId: String?) =
            if (playlistId != null) "edit_transition?playlistId=$playlistId" else "edit_transition"
    }

    object About : Screen("about")
    object Licenses : Screen("licenses")
    object SpecialThanks : Screen("special_thanks")
    object Maintenance : Screen("dap_maintenance")
    object KnownDacs : Screen("known_dacs")
    object DacProfile : Screen("dac_profile/{profileId}") {
        fun createRoute(profileId: String) = "dac_profile/${Uri.encode(profileId)}"
    }
    object KnownHeadphones : Screen("known_headphones")
    object HeadphoneProfile : Screen("headphone_profile/{profileId}") {
        fun createRoute(profileId: String) = "headphone_profile/${Uri.encode(profileId)}"
    }
    object SafetyMaxVolume : Screen("safety_max_volume/{profileId}") {
        fun createRoute(profileId: String) = "safety_max_volume/$profileId"
    }

    object ArtistSettings : Screen("artist_settings")
    object DelimiterConfig : Screen("delimiter_config")
    object WordDelimiterConfig : Screen("word_delimiter_config")
    object Equalizer : Screen("equalizer/{headphoneProfileId}") {
        fun createRoute(headphoneProfileId: String) =
            "equalizer/${Uri.encode(headphoneProfileId)}"
    }
    object DeviceCapabilities : Screen("device_capabilities")

    object AudioBookmarks : Screen("audio_bookmarks")
    object AudioBookmarkFolder : Screen("audio_bookmarks/{songId}") {
        fun createRoute(songId: String) = "audio_bookmarks/${Uri.encode(songId)}"
    }

}
