package com.lostf1sh.pixelplayeross.data.database

/** SQL computes lengths and a bounded preview; startup never materialises a whole song or a huge value. */
data class LyricsRepairRow(val songId: Long, val text: String?, val characters: Int, val bytes: Int, val hasNul: Boolean)
