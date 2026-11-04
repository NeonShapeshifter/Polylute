package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo

/** An item's stored matching evidence, as read by the metadata editor before and after a write (B57 1.1). */
data class EvidenceRow(
    @ColumnInfo(name = "library_item_id") val libraryItemId: String,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "file_signature") val fileSignature: String,
    /** The projection's recorded size: the scan derives an empty file signature from it, so parking has to blank it too. */
    @ColumnInfo(name = "file_size") val fileSize: Long,
)
