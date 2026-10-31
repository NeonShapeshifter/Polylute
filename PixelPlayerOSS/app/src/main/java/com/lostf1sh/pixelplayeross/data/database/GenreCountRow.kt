package com.lostf1sh.pixelplayeross.data.database

/** Small grouped projection; no per-song metadata or artwork is loaded for the counts. */
data class GenreCountRow(val genre: String?, val songCount: Int)
