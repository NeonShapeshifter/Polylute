package com.lostf1sh.pixelplayeross.data.musicinfo

import org.json.JSONObject

/** Read-only availability of existing files/text, without per-resource I/O or claim writes. */
class SavedMusicInfo internal constructor(
    entries: List<MusicInfoCache.SavedEntry>,
    private val languages: List<String>,
) {
    private val pictures = entries.filter { it.kind != MusicInfoCache.Kind.ABOUT }.map { it.id }.toSet()
    private val descriptions = entries.filter { it.kind == MusicInfoCache.Kind.ABOUT }.mapNotNull { entry ->
        val json = entry.meta?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null || json.optString("text").isBlank() || !json.optString("pageUrl").startsWith("https://")) {
            null
        } else {
            entry.id to json.optString("language")
        }
    }.toMap()

    fun photo(name: String): Boolean = picture(MusicInfoCache.Kind.ARTIST_PHOTO, MusicInfoService.photoKey(name))

    fun cover(album: String, artist: String): Boolean =
        picture(MusicInfoCache.Kind.COVER, MusicInfoService.coverKey(album, artist))

    fun about(kind: AboutKind, name: String, artist: String?): Boolean {
        for (language in listOf("und") + languages) {
            val translated = MusicInfoService.aboutKey(kind, name, artist, language)
            val legacy = MusicInfoService.aboutKey(kind, name, artist)
            if (descriptions[MusicInfoCache.savedEntryId(MusicInfoCache.Kind.ABOUT, translated)] == language ||
                descriptions[MusicInfoCache.savedEntryId(MusicInfoCache.Kind.ABOUT, legacy)] == language) {
                return true
            }
        }
        return false
    }

    fun fingerprint(): String {
        val texts = descriptions.toSortedMap().map { "${it.key}:${it.value}" }
        return (pictures.sorted() + texts + languages).joinToString("\n")
    }

    private fun picture(kind: MusicInfoCache.Kind, key: String): Boolean =
        MusicInfoCache.savedEntryId(kind, key) in pictures
}
