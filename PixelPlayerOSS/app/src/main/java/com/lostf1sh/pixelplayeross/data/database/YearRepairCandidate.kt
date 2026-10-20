package com.lostf1sh.pixelplayeross.data.database

/** A song whose year is unknown, as the year repair reads it: where its file is, and which album it is on. */
data class YearRepairCandidate(
    val id: Long,
    val filePath: String,
    val albumId: Long,
    /** MediaStore's `date_modified`: a re-tagged file changes it, which is when "no year" is worth asking again. */
    val dateModified: Long,
)
