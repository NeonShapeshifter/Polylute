package com.lostf1sh.pixelplayeross.data.historyimport

import android.content.Context
import android.net.Uri
import com.google.gson.stream.JsonWriter
import com.lostf1sh.pixelplayeross.data.model.isSmartPlaylist
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UniversalExportKind(
    val mimeType: String,
    val defaultFileName: String,
) {
    HISTORY_CSV("text/csv", "polylute-history.csv"),
    HISTORY_JSON("application/json", "polylute-history.json"),
    LIKES_CSV("text/csv", "polylute-likes.csv"),
    PLAYLISTS_M3U8("application/zip", "polylute-playlists.zip"),
    PLAYLISTS_CSV("text/csv", "polylute-playlists.csv"),
}

data class UniversalExportPlay(
    val playedAtMs: Long,
    val artist: String,
    val title: String,
    val album: String,
    val msPlayed: Long?,
    val durationMs: Long,
    val path: String,
)

data class UniversalExportTrack(
    val artist: String,
    val title: String,
    val album: String,
    val durationMs: Long,
    val path: String,
)

data class UniversalExportPlaylist(
    val name: String,
    val tracks: List<UniversalExportTrack>,
)

/**
 * Exports listening history, likes, and playlists in universal open schemas matching Part B's readers.
 */
@Singleton
class UniversalExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogSource: CatalogSource,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val importedHistoryStore: ImportedHistoryStore,
    private val musicRepository: MusicRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
) {

    suspend fun getPlaylistCountForExport(): Int = withContext(Dispatchers.IO) {
        playlistPreferencesRepository.getPlaylistsOnce()
            .count { !it.isSmartPlaylist && it.songIds.isNotEmpty() }
    }

    suspend fun suggestFileName(kind: UniversalExportKind): Pair<String, String> {
        if (kind != UniversalExportKind.PLAYLISTS_M3U8) return kind.mimeType to kind.defaultFileName
        val playlists = withContext(Dispatchers.IO) {
            playlistPreferencesRepository.getPlaylistsOnce()
                .filter { !it.isSmartPlaylist && it.songIds.isNotEmpty() }
        }
        return if (playlists.size == 1) {
            "audio/x-mpegurl" to "${sanitizeFileName(playlists.first().name)}.m3u8"
        } else {
            kind.mimeType to kind.defaultFileName
        }
    }

    suspend fun exportToUri(uri: Uri, kind: UniversalExportKind, zone: ZoneId = ZoneId.systemDefault()): Int =
        withContext(Dispatchers.IO) {
            val catalog = catalogSource.load()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                when (kind) {
                    UniversalExportKind.HISTORY_CSV -> {
                        val plays = collectHistoryPlays(catalog)
                        writeHistoryCsv(out, plays, zone)
                        plays.size
                    }
                    UniversalExportKind.HISTORY_JSON -> {
                        val plays = collectHistoryPlays(catalog)
                        writeHistoryJson(out, plays, zone)
                        plays.size
                    }
                    UniversalExportKind.LIKES_CSV -> {
                        val likes = collectLikes(catalog)
                        writeLikesCsv(out, likes)
                        likes.size
                    }
                    UniversalExportKind.PLAYLISTS_M3U8 -> {
                        val playlists = collectPlaylists(catalog)
                        writePlaylistsM3u8(out, playlists)
                        playlists.size
                    }
                    UniversalExportKind.PLAYLISTS_CSV -> {
                        val playlists = collectPlaylists(catalog)
                        writePlaylistsCsv(out, playlists)
                        playlists.size
                    }
                }
            } ?: throw IllegalStateException("Cannot open output stream for $uri")
        }

    internal suspend fun collectHistoryPlays(catalog: LibraryCatalog): List<UniversalExportPlay> {
        val plays = ArrayList<UniversalExportPlay>()

        val nativeEvents = playbackStatsRepository.exportEventsForBackup()
        if (nativeEvents.isNotEmpty()) {
            val ledger = ListeningLedger.build(nativeEvents, catalog)
            for (occ in ledger.occurrences) {
                if (!occ.isPlay) continue
                val item = occ.item
                val recorded = occ.recordedTrackMetadata
                val title = item?.title ?: recorded?.title ?: continue
                plays += UniversalExportPlay(
                    playedAtMs = occ.startMs,
                    artist = item?.artistLine ?: recorded?.artist.orEmpty(),
                    title = title,
                    album = item?.album ?: recorded?.album.orEmpty(),
                    msPlayed = occ.listenedMs,
                    durationMs = item?.lengthMs ?: occ.lengthMs,
                    path = item?.song?.path.orEmpty(),
                )
            }
        }

        val imported = importedHistoryStore.statsInput(catalog)
        if (!imported.isEmpty) {
            for (i in imported.timestampsMs.indices) {
                val track = imported.tracks[imported.trackIndices[i]]
                val item = track.matchedItem
                val rawMs = imported.listenedMs[i]
                val msPlayed = if (imported.estimated[i] && rawMs == 0L) null else rawMs
                plays += UniversalExportPlay(
                    playedAtMs = imported.timestampsMs[i],
                    artist = item?.artistLine ?: track.artistRaw,
                    title = item?.title ?: track.title,
                    album = item?.album ?: track.album,
                    msPlayed = msPlayed,
                    durationMs = item?.lengthMs ?: 0L,
                    path = item?.song?.path.orEmpty(),
                )
            }
        }

        plays.sortBy { it.playedAtMs }
        return plays
    }

    internal suspend fun collectLikes(catalog: LibraryCatalog): List<UniversalExportTrack> {
        val favIds = musicRepository.getFavoriteSongIdsOnce()
        if (favIds.isEmpty()) return emptyList()
        return favIds.mapNotNull { id ->
            val item = catalog.itemAtLocator(id) ?: return@mapNotNull null
            UniversalExportTrack(
                artist = item.artistLine,
                title = item.title,
                album = item.album,
                durationMs = item.lengthMs,
                path = item.song.path,
            )
        }.sortedWith(compareBy({ it.artist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }, { it.path }))
    }

    internal suspend fun collectPlaylists(catalog: LibraryCatalog): List<UniversalExportPlaylist> {
        val playlists = playlistPreferencesRepository.getPlaylistsOnce()
            .filter { !it.isSmartPlaylist && it.songIds.isNotEmpty() }
        return playlists.mapNotNull { pl ->
            val tracks = pl.songIds.mapNotNull { songId ->
                val item = catalog.itemAtLocator(songId) ?: return@mapNotNull null
                UniversalExportTrack(
                    artist = item.artistLine,
                    title = item.title,
                    album = item.album,
                    durationMs = item.lengthMs,
                    path = item.song.path,
                )
            }
            if (tracks.isEmpty()) null else UniversalExportPlaylist(pl.name, tracks)
        }
    }

    companion object {
        fun formatPlayedAtIso(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).toString()

        fun writeHistoryCsv(
            output: OutputStream,
            plays: List<UniversalExportPlay>,
            zone: ZoneId = ZoneId.systemDefault(),
        ) {
            OutputStreamWriter(BufferedOutputStream(output, 64 * 1024), Charsets.UTF_8).use { w ->
                w.write("played_at,artist,title,album,ms_played,duration_ms,path\n")
                for (p in plays) {
                    w.write(csvCell(formatPlayedAtIso(p.playedAtMs, zone)))
                    w.write(",")
                    w.write(csvCell(p.artist))
                    w.write(",")
                    w.write(csvCell(p.title))
                    w.write(",")
                    w.write(csvCell(p.album))
                    w.write(",")
                    w.write(p.msPlayed?.toString().orEmpty())
                    w.write(",")
                    w.write(if (p.durationMs > 0L) p.durationMs.toString() else "")
                    w.write(",")
                    w.write(csvCell(p.path))
                    w.write("\n")
                }
                w.flush()
            }
        }

        fun writeHistoryJson(
            output: OutputStream,
            plays: List<UniversalExportPlay>,
            zone: ZoneId = ZoneId.systemDefault(),
        ) {
            JsonWriter(OutputStreamWriter(BufferedOutputStream(output, 64 * 1024), Charsets.UTF_8)).use { w ->
                w.setIndent("  ")
                w.beginArray()
                for (p in plays) {
                    w.beginObject()
                    w.name("played_at").value(formatPlayedAtIso(p.playedAtMs, zone))
                    w.name("artist").value(p.artist)
                    w.name("title").value(p.title)
                    w.name("album").value(p.album)
                    w.name("ms_played")
                    if (p.msPlayed != null) w.value(p.msPlayed) else w.nullValue()
                    w.name("duration_ms")
                    if (p.durationMs > 0L) w.value(p.durationMs) else w.nullValue()
                    w.name("path").value(p.path)
                    w.endObject()
                }
                w.endArray()
                w.flush()
            }
        }

        fun writeLikesCsv(output: OutputStream, tracks: List<UniversalExportTrack>) {
            OutputStreamWriter(BufferedOutputStream(output, 64 * 1024), Charsets.UTF_8).use { w ->
                w.write("artist,title,album,duration_ms,path\n")
                for (t in tracks) {
                    w.write(csvCell(t.artist))
                    w.write(",")
                    w.write(csvCell(t.title))
                    w.write(",")
                    w.write(csvCell(t.album))
                    w.write(",")
                    w.write(if (t.durationMs > 0L) t.durationMs.toString() else "")
                    w.write(",")
                    w.write(csvCell(t.path))
                    w.write("\n")
                }
                w.flush()
            }
        }

        fun writePlaylistsCsv(output: OutputStream, playlists: List<UniversalExportPlaylist>) {
            OutputStreamWriter(BufferedOutputStream(output, 64 * 1024), Charsets.UTF_8).use { w ->
                w.write("playlist,position,artist,title,album,duration_ms,path\n")
                for (pl in playlists) {
                    pl.tracks.forEachIndexed { pos, t ->
                        w.write(csvCell(pl.name))
                        w.write(",")
                        w.write(pos.toString())
                        w.write(",")
                        w.write(csvCell(t.artist))
                        w.write(",")
                        w.write(csvCell(t.title))
                        w.write(",")
                        w.write(csvCell(t.album))
                        w.write(",")
                        w.write(if (t.durationMs > 0L) t.durationMs.toString() else "")
                        w.write(",")
                        w.write(csvCell(t.path))
                        w.write("\n")
                    }
                }
                w.flush()
            }
        }

        fun writePlaylistsM3u8(output: OutputStream, playlists: List<UniversalExportPlaylist>) {
            if (playlists.size == 1) {
                val text = formatM3u8(playlists.first())
                OutputStreamWriter(BufferedOutputStream(output, 64 * 1024), Charsets.UTF_8).use { w ->
                    w.write(text)
                    w.flush()
                }
                return
            }

            ZipOutputStream(BufferedOutputStream(output, 64 * 1024)).use { zip ->
                val usedNames = HashSet<String>()
                for (pl in playlists) {
                    val base = sanitizeFileName(pl.name)
                    var candidate = "$base.m3u8"
                    var suffix = 2
                    while (!usedNames.add(candidate)) {
                        candidate = "$base ($suffix).m3u8"
                        suffix++
                    }
                    zip.putNextEntry(ZipEntry(candidate))
                    zip.write(formatM3u8(pl).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }

        fun formatM3u8(playlist: UniversalExportPlaylist): String = buildString {
            append("#EXTM3U\n")
            append("#PLAYLIST:").append(playlist.name.replace('\n', ' ').replace('\r', ' ')).append('\n')
            for (t in playlist.tracks) {
                val sec = (t.durationMs / 1_000L).coerceAtLeast(0L)
                append("#EXTINF:").append(sec).append(',').append(t.artist).append(" - ").append(t.title).append('\n')
                val linePath = t.path.ifBlank { "${t.artist} - ${t.title}.mp3" }
                append(linePath).append('\n')
            }
        }

        private fun sanitizeFileName(raw: String): String =
            raw.trim()
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .ifBlank { "playlist" }

        private fun csvCell(raw: String): String {
            val needsQuote = raw.indexOfAny(charArrayOf(',', '"', '\n', '\r')) >= 0
            if (!needsQuote) return raw
            return buildString(raw.length + 4) {
                append('"')
                for (ch in raw) {
                    if (ch == '"') append('"')
                    append(ch)
                }
                append('"')
            }
        }
    }
}
