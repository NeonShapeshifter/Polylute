package com.lostf1sh.pixelplayeross.data.lyrics

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

internal fun lyricsContentHash(input: InputStream): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > LyricsTextPolicy.MAX_BYTES) return null
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

/** Mutations run under DeletedDownloads' shared commit lock. User-owned and edited sidecars survive. */
internal class DownloadedLyricsFiles(private val storage: LyricsStorage, private val journal: File) {
    private fun read(): JSONObject = journal.takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    private fun write(value: JSONObject) {
        journal.parentFile?.mkdirs()
        val temporary = File(journal.parentFile, "${journal.name}.tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(journal)) { "Could not save downloaded lyrics provenance" }
    }

    fun forget(song: Song) { val entries = read(); entries.remove(DownloadKeys.lyrics(song)); write(entries) }

    fun identities(): Set<String> {
        val entries = read()
        return entries.keys().asSequence().map { it.removePrefix("lyrics|") }.filterNot { it.startsWith("locator:") }.toSet()
    }

    suspend fun save(song: Song, content: String, saveInApp: suspend () -> Unit): LyricsSaveResult {
        if (song.path.isBlank()) { saveInApp(); return LyricsSaveResult.SavedInApp(LyricsWriteReason.UNAVAILABLE) }
        val file = File(song.path)
        val target = File(file.parentFile, "${file.nameWithoutExtension}.lrc").absolutePath
        val entries = read()
        val key = DownloadKeys.lyrics(song)
        val previous = entries.optJSONObject(key)
        val userFile = try {
            storage.exists(target) && (previous == null || storage.contentHash(target) != previous.optString("hash"))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            saveInApp()
            return LyricsSaveResult.SavedInApp(lyricsWriteReason(error))
        }
        if (userFile) {
            saveInApp()
            return LyricsSaveResult.SavedInApp(LyricsWriteReason.EXISTING_FILE)
        }
        // A crash before writing cannot claim a different user's bytes: deletion rechecks this hash.
        val hash = content.byteInputStream(Charsets.UTF_8).use(::lyricsContentHash) ?: error("Lyrics exceed the storage limit")
        entries.put(key, JSONObject().put("path", target).put("hash", hash))
        write(entries)
        val result = LyricsFileSaver(storage).save(song.path, content, afterPicker = true, afterWriteAccess = true, saveInApp = saveInApp)
        if (result !is LyricsSaveResult.FileSaved) {
            if (previous == null) entries.remove(key) else entries.put(key, previous)
            write(entries)
        }
        return result
    }

    fun delete(song: Song): String? {
        val entries = read()
        val key = DownloadKeys.lyrics(song)
        val entry = entries.optJSONObject(key) ?: return null
        val path = entry.getString("path")
        if (storage.contentHash(path) == entry.getString("hash")) storage.deletePath(path)
        // The caller forgets provenance only after its DB/JSON cleanup succeeds, so Retry retains the hash.
        return entry.getString("hash")
    }
}
