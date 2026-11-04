package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * B56 phase 6 (Sherlock #36): the composer of a song, as the file's COMPOSER tag says it, for search. One row per song that has been read: [composer] is the tag value as stored
 * ("Ludwig van Beethoven; Wolfgang Amadeus Mozart") and "" when the file has none. A song with no row has not been read yet (the backfill reads it).
 * Composers are not artists: nothing here touches the artists, the albums or the Artists tab.
 */
@Entity(
    tableName = "song_composers",
    foreignKeys = [
        ForeignKey(entity = SongEntity::class, parentColumns = ["id"], childColumns = ["song_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SongComposerEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "composer") val composer: String,
    @ColumnInfo(name = "read_at") val readAt: Long,
)

/** A composer tag value and how many songs carry it. */
data class ComposerCountRow(val composer: String, val songCount: Int)

/** A song whose composer has not been read, with the file to read it from. */
data class ComposerBackfillRow(val songId: Long, val path: String)
