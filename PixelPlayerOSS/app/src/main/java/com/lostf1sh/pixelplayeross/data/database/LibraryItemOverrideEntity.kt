package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The metadata edits a user made to a logical library item — **the durable owner of that state**.
 *
 * These four flags and the values they protect used to live on `songs`, the MediaStore projection.
 * That placement was wrong in a way that is easy to miss: the projection is disposable by design.
 * Removing it (an ejected card, a revoked permission, a scanner rebuild, a renumber) took the user's
 * edits with it, so a user who renamed a track and then remounted their card would find the edit
 * silently gone — and, worse, the track re-titled from its file tag with no indication anything had
 * been lost.
 *
 * A user's edit is a statement about *the track*, not about the row Android happens to be showing
 * for it. It belongs on the logical item, and it has to survive the item having no projection at
 * all — which is exactly what this table allows, since it is keyed by
 * [libraryItemId][LibraryItemEntity.libraryItemId] and has no relationship to `songs`.
 *
 * [`songs.title`][SongEntity.title] and its three siblings remain the **display values** the rest of
 * the app reads, and indeed hold the edited text. They are a *derived mirror*, recomputed from this
 * table whenever a projection is written — the same relationship `songs.is_favorite` has to
 * [FavoritesEntity]. The difference matters: there is one writable authority (here), and the copy on
 * the projection is a cache that can always be regenerated from it.
 *
 * A null value column means "not edited", and its flag is then 0. Storing both is deliberate: the
 * flag alone cannot restore the edit, and the value alone cannot say whether the user wanted it.
 */
@Entity(tableName = "library_item_overrides")
data class LibraryItemOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "library_item_id")
    val libraryItemId: String,

    /** The user's title, or null when they never edited one. */
    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "artist")
    val artist: String? = null,

    @ColumnInfo(name = "album")
    val album: String? = null,

    @ColumnInfo(name = "genre")
    val genre: String? = null,

    @ColumnInfo(name = "title_user_edited", defaultValue = "0")
    val titleUserEdited: Boolean = false,

    @ColumnInfo(name = "artist_user_edited", defaultValue = "0")
    val artistUserEdited: Boolean = false,

    @ColumnInfo(name = "album_user_edited", defaultValue = "0")
    val albumUserEdited: Boolean = false,

    @ColumnInfo(name = "genre_user_edited", defaultValue = "0")
    val genreUserEdited: Boolean = false,
) {
    /** True when the item carries no user edits at all and the row is pure bookkeeping. */
    val isEmpty: Boolean
        get() = !titleUserEdited && !artistUserEdited && !albumUserEdited && !genreUserEdited
}

/**
 * Recomputes a projection's display values from the durable override.
 *
 * This is the *only* way an edit reaches `songs`, and it always runs over a freshly scanned row —
 * never over a stale one. That ordering is what keeps the two consistent: the file's current tags
 * are the base, and the user's edits are applied on top, so clearing an override lets the file's own
 * title come back instead of leaving the last edit pinned forever.
 */
fun SongEntity.withOverrides(override: LibraryItemOverrideEntity?): SongEntity {
    if (override == null || override.isEmpty) {
        return copy(
            titleUserEdited = false,
            artistUserEdited = false,
            albumUserEdited = false,
            genreUserEdited = false,
        )
    }
    return copy(
        title = if (override.titleUserEdited) override.title ?: title else title,
        artistName = if (override.artistUserEdited) override.artist ?: artistName else artistName,
        albumName = if (override.albumUserEdited) override.album ?: albumName else albumName,
        genre = if (override.genreUserEdited) override.genre ?: genre else genre,
        titleUserEdited = override.titleUserEdited,
        artistUserEdited = override.artistUserEdited,
        albumUserEdited = override.albumUserEdited,
        genreUserEdited = override.genreUserEdited,
    )
}
