package com.lostf1sh.pixelplayeross.data.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.R

@Immutable
enum class LibraryTabId(
    val storageKey: String,
    @StringRes val titleRes: Int,
    val defaultSort: SortOption
) {
    SONGS("SONGS", R.string.library_tab_songs, SortOption.SongTitleAZ),
    ALBUMS("ALBUMS", R.string.library_tab_albums, SortOption.AlbumTitleAZ),
    ARTISTS("ARTIST", R.string.library_tab_artists, SortOption.ArtistNameAZ),
    PLAYLISTS("PLAYLISTS", R.string.library_tab_playlists, SortOption.PlaylistNameAZ),
    FOLDERS("FOLDERS", R.string.library_tab_folders, SortOption.FolderNameAZ),
    /**
     * Generated views live in the tab strip alongside the manual library.
     *
     * A collection is a saved query, not library *contents*, but it is something a listener browses the same way as songs and albums.
     * The strip already supports ordering and hiding, so a listener who disagrees can move or hide it. (The automatic collections
     * that used to have a "Mixes" tab of their own are entries of the Playlists list since B28; a stored order that still names
     * that tab is reconciled without it.)
     */
    GENRES("GENRES", R.string.library_tab_genres, SortOption.ArtistNameAZ),
    COLLECTIONS("COLLECTIONS", R.string.library_tab_collections, SortOption.PlaylistNameAZ),
    LIKED("LIKED", R.string.library_tab_liked, SortOption.LikedSongDateLiked);

    companion object {
        fun fromStorageKey(key: String): LibraryTabId =
            entries.firstOrNull { it.storageKey == key } ?: SONGS
    }
}

fun String.toLibraryTabIdOrNull(): LibraryTabId? =
    LibraryTabId.entries.firstOrNull { it.storageKey == this }

/**
 * Every Library tab this build ships, in the default order.
 *
 * The single source of truth for both the strip and the tab editor. It is derived from the enum
 * rather than written out again, because a tab that exists in the enum but not in this list is a
 * tab the user can never reach — which is exactly what happened when the generated views were added
 * and the hand-written copy of this list was not updated.
 */
val DEFAULT_LIBRARY_TAB_KEYS: List<String> = LibraryTabId.entries.map { it.storageKey }

/**
 * Reconciles a stored tab order against the tabs this build ships.
 *
 * Two jobs, both about not losing a tab:
 *
 * - **Stored order wins** where it names a tab this build knows, so the user's arrangement survives
 *   upgrades.
 * - **Unknown tabs are appended, never dropped**, so a tab introduced in a later build (or one whose
 *   stored key this build no longer recognises) is reachable instead of silently absent.
 *
 * A `null` or unreadable value means "no arrangement was ever saved", which is the default order.
 * Kept pure and here rather than inside a view model so the rule that decides which tabs exist can
 * be tested without a device.
 */
fun reconcileLibraryTabOrder(storedKeys: List<String>?): List<String> {
    if (storedKeys == null) return DEFAULT_LIBRARY_TAB_KEYS
    val known = storedKeys.filter { key -> key in DEFAULT_LIBRARY_TAB_KEYS }
    val missing = DEFAULT_LIBRARY_TAB_KEYS.filterNot { it in known }
    return known + missing
}