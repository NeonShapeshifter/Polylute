package com.lostf1sh.pixelplayeross.data.service

import android.net.Uri

/** Stable ids contain typed references, never a title or a file path. */
internal data class AutoMediaId(val kind: String, val ref: String? = null, val songId: String? = null, val page: Int? = null) {
    override fun toString(): String = kind + (ref?.let { ":${Uri.encode(it)}" } ?: "") + (page?.let { "/page:$it" } ?: "") + (songId?.let { "/song:${Uri.encode(it)}" } ?: "")
    fun song(id: String) = copy(songId = id)
    fun parent() = copy(songId = null, page = null)
    companion object {
        const val ROOT = "polylute"
        private val roots = setOf(ROOT, "home", "playlists", "albums", "artists", "songs", "liked")
        private val sources = setOf("album", "artist", "playlist", "collection", "mix", "search", "song")
        fun parse(raw: String): AutoMediaId? {
            val parts = raw.split("/song:")
            if (parts.size > 2) return null
            val pageParts = parts[0].split("/page:")
            if (pageParts.size > 2) return null
            val page = if (pageParts.size == 2) pageParts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null else null
            val head = pageParts[0].split(':', limit = 2)
            val kind = head[0]
            val ref = head.getOrNull(1)?.let(Uri::decode)
            val songId = parts.getOrNull(1)?.let(Uri::decode)
            if (kind in roots && ref != null || kind in sources && ref.isNullOrBlank() || kind !in roots && kind !in sources) return null
            if (kind in setOf("album", "artist", "song") && ref?.toLongOrNull() == null) return null
            if (songId != null && songId.toLongOrNull() == null) return null
            if (kind in setOf(ROOT, "home", "playlists", "albums", "artists", "song") && songId != null) return null
            return AutoMediaId(kind, ref, songId, page).takeIf { it.toString() == raw }
        }
    }
}

/** Logical pages have at most 100 rows, even when a legacy browser asks for Int.MAX_VALUE. */
internal data class AutoPage(val limit: Int, val offset: Int) {
    companion object {
        const val MAX_SIZE = 100
        fun of(page: Int, size: Int): AutoPage? {
            if (page < 0 || size <= 0) return null
            val limit = size.coerceAtMost(MAX_SIZE)
            val offset = page.toLong() * limit
            return if (offset > Int.MAX_VALUE) null else AutoPage(limit, offset.toInt())
        }
    }
}
