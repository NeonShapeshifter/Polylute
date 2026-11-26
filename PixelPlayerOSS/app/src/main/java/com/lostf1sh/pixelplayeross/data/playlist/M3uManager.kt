package com.lostf1sh.pixelplayeross.data.playlist

import android.content.Context
import android.net.Uri
import com.lostf1sh.pixelplayeross.data.historyimport.CatalogPathIndex
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPathNormalizer
import com.lostf1sh.pixelplayeross.data.historyimport.LibraryTrackMatcher
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class M3uParsedEntry(
    val path: String,
    val durationSec: Long? = null,
    val artist: String? = null,
    val title: String? = null,
    val rawTitle: String? = null,
)

data class M3uMatchedItem(
    val entry: M3uParsedEntry,
    val matched: CatalogItem?,
)

data class M3uMatchedPlaylist(
    val name: String,
    val items: List<M3uMatchedItem>,
)

@Singleton
class M3uManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository
) {

    suspend fun parseM3u(uri: Uri): Pair<String, List<String>> {
        var playlistName = "Imported Playlist"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                playlistName = stripM3uExtension(cursor.getString(nameIndex))
            }
        }

        val allSongs = musicRepository.getAllSongsOnce()
        val catalog = LibraryCatalog.of(allSongs, emptyMap())
        val matcher = LibraryTrackMatcher.build(catalog)
        val matchedIds = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val parsed = parseM3u(inputStream, playlistName, catalog, matcher)
            playlistName = parsed.name
            parsed.items.mapNotNull { it.matched?.locator }
        }.orEmpty()

        return Pair(playlistName, matchedIds)
    }

    fun generateM3u(playlist: Playlist, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        for (song in songs) {
            sb.append("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}\n")
            sb.append("${song.path}\n")
        }
        return sb.toString()
    }

    companion object {
        private val EXTINF_DURATION_REGEX = Regex("""^(-?\d+)""")

        private fun stripM3uExtension(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            return when {
                base.endsWith(".m3u8", ignoreCase = true) -> base.dropLast(5)
                base.endsWith(".m3u", ignoreCase = true) -> base.dropLast(4)
                else -> base
            }.ifBlank { "Imported Playlist" }
        }

        internal fun parseExtInfLine(line: String): Triple<Long?, String?, String?> {
            val body = line.removePrefix("#EXTINF:").trim()
            val commaIdx = body.indexOf(',')
            val durationPart = if (commaIdx >= 0) body.substring(0, commaIdx).trim() else body
            val durationSec = EXTINF_DURATION_REGEX.find(durationPart)?.groupValues?.get(1)?.toLongOrNull()
                ?.takeIf { it >= 0L }
            val display = if (commaIdx >= 0) body.substring(commaIdx + 1).trim() else ""
            if (display.isEmpty()) return Triple(durationSec, null, null)

            for (sep in listOf(" - ", " – ", " — ")) {
                val sepIdx = display.indexOf(sep)
                if (sepIdx > 0 && sepIdx + sep.length < display.length) {
                    val artist = display.substring(0, sepIdx).trim()
                    val title = display.substring(sepIdx + sep.length).trim()
                    if (artist.isNotEmpty() && title.isNotEmpty()) {
                        return Triple(durationSec, artist, title)
                    }
                }
            }
            return Triple(durationSec, null, display)
        }

        fun parseM3uEntries(input: InputStream, fileName: String): Pair<String, List<M3uParsedEntry>> {
            var playlistName = stripM3uExtension(fileName)
            val entries = ArrayList<M3uParsedEntry>()
            var pendingDuration: Long? = null
            var pendingArtist: String? = null
            var pendingTitle: String? = null
            var pendingRawTitle: String? = null

            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                var firstLine = true
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    var trimmed = line?.trim() ?: continue
                    if (firstLine) {
                        firstLine = false
                        trimmed = trimmed.removePrefix("\uFEFF").trim()
                    }
                    if (trimmed.isEmpty()) continue
                    if (trimmed.startsWith("#")) {
                        when {
                            trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
                                val (dur, artist, titleOrRaw) = parseExtInfLine(trimmed)
                                pendingDuration = dur
                                if (artist != null) {
                                    pendingArtist = artist
                                    pendingTitle = titleOrRaw
                                    pendingRawTitle = null
                                } else {
                                    pendingArtist = null
                                    pendingTitle = null
                                    pendingRawTitle = titleOrRaw
                                }
                            }
                            trimmed.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                                val explicit = trimmed.substring(10).trim()
                                if (explicit.isNotEmpty()) playlistName = explicit
                            }
                        }
                        continue
                    }

                    entries += M3uParsedEntry(
                        path = trimmed,
                        durationSec = pendingDuration,
                        artist = pendingArtist,
                        title = pendingTitle,
                        rawTitle = pendingRawTitle,
                    )
                    pendingDuration = null
                    pendingArtist = null
                    pendingTitle = null
                    pendingRawTitle = null
                }
            }
            return playlistName to entries
        }

        fun parseM3u(
            input: InputStream,
            fileName: String,
            catalog: LibraryCatalog,
            matcher: LibraryTrackMatcher = LibraryTrackMatcher.build(catalog),
        ): M3uMatchedPlaylist {
            val (playlistName, entries) = parseM3uEntries(input, fileName)
            val pathIndex = CatalogPathIndex.from(catalog)
            val byFileName = HashMap<String, MutableList<CatalogItem>>(catalog.items.size)
            val byContentUriFileName = HashMap<String, MutableList<CatalogItem>>(catalog.items.size)

            for (item in catalog.items) {
                val p = item.song.path
                if (p.isNotBlank()) {
                    val fn = ImportPathNormalizer.normalize(p).substringAfterLast('/')
                    if (fn.isNotBlank()) byFileName.getOrPut(fn) { ArrayList(1) }.add(item)
                }
                val uriName = item.song.contentUriString.substringAfterLast('/')
                if (uriName.isNotBlank()) byContentUriFileName.getOrPut(uriName) { ArrayList(1) }.add(item)
            }

            val matched = entries.map { entry ->
                val byPath = pathIndex.find(entry.path)
                if (byPath != null) {
                    return@map M3uMatchedItem(entry, byPath)
                }

                // Match by #EXTINF artist and title before filename-only fallback
                val durationHintMs = entry.durationSec?.let { it * 1_000L }
                if (!entry.artist.isNullOrBlank() && !entry.title.isNullOrBlank()) {
                    val (_, item) = matcher.match(entry.artist, entry.title, null, durationHintMs)
                    if (item != null) {
                        return@map M3uMatchedItem(entry, item)
                    }
                }

                val fileNamePart = ImportPathNormalizer.normalize(entry.path).substringAfterLast('/')
                val byName = if (fileNamePart.isNotBlank()) {
                    byFileName[fileNamePart]?.firstOrNull() ?: byContentUriFileName[fileNamePart]?.firstOrNull()
                } else {
                    null
                }
                M3uMatchedItem(entry, byName)
            }
            return M3uMatchedPlaylist(name = playlistName, items = matched)
        }
    }
}
