package com.lostf1sh.pixelplayeross.data.media

import com.lostf1sh.pixelplayeross.data.model.MusicBrainzIds
import java.util.Locale

/** TagLib's common property names plus the ID3 TXXX and MP4 freeform spellings taggers use. */
internal object MusicBrainzTags {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun read(properties: Map<String, Array<String>>): MusicBrainzIds {
        val fields = properties.entries.groupBy {
            it.key.substringAfterLast(':').uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        }
        fun id(vararg names: String): String? = names.asSequence().flatMap { fields[it].orEmpty().asSequence() }
            .flatMap { it.value.asSequence() }.mapNotNull { uuid.find(it)?.value?.lowercase(Locale.ROOT) }.firstOrNull()
        return MusicBrainzIds(
            recording = id("MUSICBRAINZRECORDINGID", "MUSICBRAINZTRACKID"),
            release = id("MUSICBRAINZRELEASEID", "MUSICBRAINZALBUMID"),
            releaseGroup = id("MUSICBRAINZRELEASEGROUPID"),
            artist = id("MUSICBRAINZARTISTID"),
            albumArtist = id("MUSICBRAINZALBUMARTISTID"),
        )
    }
}
