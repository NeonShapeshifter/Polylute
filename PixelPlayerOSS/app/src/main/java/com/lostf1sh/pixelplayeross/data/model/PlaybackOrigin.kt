package com.lostf1sh.pixelplayeross.data.model

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import kotlinx.serialization.Serializable

/** Source belongs to an occurrence in the queue, including repeated songs in different blocks. */
@Serializable
data class PlaybackOrigin(val kind: String, val ref: String? = null, val name: String? = null) {
    fun source(): ListenSource? = ListenSourceKind.entries.firstOrNull { it.name == kind }?.let { ListenSource(it, ref) }
    fun put(extras: Bundle) {
        extras.putString(KIND, kind)
        extras.putString(REF, ref)
        extras.putString(NAME, name)
    }
    companion object {
        private const val KIND = "app.polylute.queue.source.kind"
        private const val REF = "app.polylute.queue.source.ref"
        private const val NAME = "app.polylute.queue.source.name"
        fun of(source: ListenSource?, name: String?): PlaybackOrigin? = source?.let { PlaybackOrigin(it.kind.name, it.ref, name) }
        fun from(item: MediaItem?): PlaybackOrigin? {
            val extras = item?.mediaMetadata?.extras ?: return null
            val kind = extras.getString(KIND) ?: return null
            return PlaybackOrigin(kind, extras.getString(REF), extras.getString(NAME))
        }
        fun attach(item: MediaItem, origin: PlaybackOrigin?): MediaItem {
            if (origin == null) return item
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle())
            origin.put(extras)
            return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        }
    }
}
