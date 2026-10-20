package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A logical library item, which exists whether or not it currently has a MediaStore projection.
 *
 * This is the invariant the previous model could not express. `songs` is a *projection* — a row for
 * a file Android can currently see. A user's relationship to a track is not the same thing: it
 * survives the file moving, the card being ejected, and the scanner rebuilding its numbering, and it
 * must not be destroyed by any of them.
 *
 * So the durable item lives here, keyed by [libraryItemId], and `songs` becomes a projection that
 * points at it. Removing a projection is then an ordinary event rather than a loss.
 */
@Entity(
    tableName = "library_items",
    indices = [
        Index(value = ["state"], unique = false),
        Index(value = ["stable_key"], unique = false),
        Index(value = ["current_locator"], unique = true),
    ],
)
data class LibraryItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "library_item_id")
    val libraryItemId: String,

    /** One of [LibraryItemState]. Stored as text so an unknown future value reads as-is. */
    @ColumnInfo(name = "state", defaultValue = "'ACTIVE'")
    val state: String = LibraryItemState.ACTIVE,

    /** Last known matching evidence, retained even while dormant so a return can be recognised. */
    @ColumnInfo(name = "stable_key", defaultValue = "''")
    val stableKey: String = "",

    @ColumnInfo(name = "file_signature", defaultValue = "''")
    val fileSignature: String = "",

    /**
     * The locator of the current projection, or **null** when there is none.
     *
     * Null rather than a `-1` sentinel, and that is not cosmetic: a unique index treats every NULL
     * as distinct but every `-1` as equal, so a sentinel would permit exactly **one** dormant item
     * in the whole library. Real Room tests caught this the moment fifty items went dormant at
     * once — which is the ordinary case for an ejected card.
     */
    @ColumnInfo(name = "current_locator")
    val currentLocator: Long? = null,

    @ColumnInfo(name = "first_seen_at", defaultValue = "0")
    val firstSeenAt: Long = 0L,

    @ColumnInfo(name = "last_seen_at", defaultValue = "0")
    val lastSeenAt: Long = 0L,
) {
    /** Normalised lifecycle value; an unrecognised stored value reads as ACTIVE. */
    val lifecycle: String get() = LibraryItemState.fromStorage(state)

    companion object {
        /** No projection. Named so call sites read clearly, but stored as SQL NULL. */
        val NO_LOCATOR: Long? = null
    }
}

/**
 * The lifecycle of a logical library item.
 *
 * Note what is **absent**: there is no `SAFE_TO_DELETE`. Absence of a projection is not evidence of
 * the user wanting a track gone — an ejected card, a revoked permission and a scanner rebuild are
 * indistinguishable at this layer — so nothing here destroys user state on its own. Permanent
 * deletion would be a separate product decision with an explicit retention rule, and inventing one
 * now would be guessing at intent the app has not been given.
 */
object LibraryItemState {
    /** Has a current MediaStore projection. */
    const val ACTIVE = "ACTIVE"

    /** Known, with user state intact, and currently no projection. */
    const val DORMANT = "DORMANT"

    /** Candidate matches exist but automatic reattachment would be unsafe. */
    const val AMBIGUOUS = "AMBIGUOUS"

    fun fromStorage(value: String): String = when (value) {
        DORMANT -> DORMANT
        AMBIGUOUS -> AMBIGUOUS
        else -> ACTIVE
    }
}
