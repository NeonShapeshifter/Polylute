package com.lostf1sh.pixelplayeross.data.lyrics

/** Storage and decoding policy, independent of language and of the source of the lyrics. Never writes to music files or sidecars. */
object LyricsTextPolicy {
    const val MAX_CHARACTERS = 50_000
    const val MAX_BYTES = 200_000
    private val blobRun = Regex("[A-Za-z0-9+/=_-]{512,}")
    private val dropped = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun rejection(text: String): String? {
        if (text.length > MAX_CHARACTERS) return "too long"
        if (text.isBlank()) return "empty"
        if (text.any { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' }) return "binary controls"
        val replacements = text.count { it == '\uFFFD' }
        if (replacements >= 8 && replacements * 50L > text.length) return "replacement characters"
        val start = text.trimStart()
        if (start.startsWith("data:image/", ignoreCase = true) || text.contains("\u0089PNG") || text.contains("\u00ff\u00d8\u00ff") ||
            start.startsWith("iVBORw0KGgo") || start.startsWith("/9j/")) return "image data"
        if (blobRun.containsMatchIn(text)) return "encoded blob"
        return null
    }
    fun accepts(text: String): Boolean = rejection(text) == null
    fun forStorage(songId: Long, text: String?): String? {
        if (text == null) return null
        val reason = rejection(text) ?: return text
        logDrop(songId, reason)
        return null
    }
    fun logDrop(songId: Long, reason: String) {
        if (dropped.add(songId)) timber.log.Timber.tag("LyricsRepair").w("Dropped invalid lyrics for song %d (%s)", songId, reason)
    }
}
