package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.core.net.toUri
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri

/** Call on IO: a scan's lazy URI is a locator, never proof of an embedded image. */
internal object OfflineArtwork {
    fun hasLocalCover(applicationContext: Context, song: Song): Boolean {
        val uri = song.albumArtUriString?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            if (LocalArtworkUri.isLocalArtworkUri(uri)) {
                // A lazy locator exists even when embedded-art extraction has never been attempted.
                val songId = LocalArtworkUri.parseSongId(uri) ?: return false
                AlbumArtUtils.ensureAlbumArtCachedFile(applicationContext, songId, song.path)
                    ?.let { it.isFile && it.length() > 0L } == true
            } else {
                AlbumArtUtils.openArtworkInputStream(applicationContext, uri.toUri())?.use { it.read() >= 0 } == true
            }
        }.getOrDefault(false)
    }

}
