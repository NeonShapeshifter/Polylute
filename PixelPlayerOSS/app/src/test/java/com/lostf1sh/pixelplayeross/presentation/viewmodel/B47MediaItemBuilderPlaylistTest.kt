package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import java.lang.management.ManagementFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B47-A1: a playlist's items used to be built, their 13 extras copied into a second Bundle and the item built again (`buildUpon` twice), for every song. One pass gives the same
 * item. Measured here on the JVM under Robolectric (this host's numbers, not the phone's): bytes allocated and time for 4,938 items, both ways.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B47MediaItemBuilderPlaylistTest {

    private fun song(i: Int) = Song.emptySong().copy(
        id = i.toString(), title = "Song $i", artist = "Artist ${i % 90}", album = "Album ${i % 400}", albumId = (i % 400).toLong(), duration = 200_000L + i,
        contentUriString = "content://media/external/audio/media/$i", path = "/storage/emulated/0/Music/Artist ${i % 90}/Album ${i % 400}/$i.flac",
        mimeType = "audio/flac", albumArtUriString = "content://media/external/audio/albumart/${i % 400}", genre = "Genre ${i % 20}", trackNumber = i % 20 + 1, year = 1990 + i % 35,
    )

    /** What the view model did before B47: build, copy the extras, build again. */
    private fun twoPass(song: Song, playlistId: String): MediaItem {
        val base = MediaItemBuilder.build(song)
        val merged = Bundle(base.mediaMetadata.extras ?: Bundle()).apply { putString("playlistId", playlistId) }
        return base.buildUpon().setMediaMetadata(base.mediaMetadata.buildUpon().setExtras(merged).build()).build()
    }

    private fun allocatedBytes(): Long = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)

    @Test
    fun `the item built in one pass is the one the two passes built`() {
        for (i in listOf(1, 77, 4000)) {
            val s = song(i)
            val one = MediaItemBuilder.build(s, "playlist-9")
            val two = twoPass(s, "playlist-9")
            assertThat(one.mediaId).isEqualTo(two.mediaId)
            assertThat(one.localConfiguration?.uri).isEqualTo(two.localConfiguration?.uri)
            assertThat(one.localConfiguration?.mimeType).isEqualTo(two.localConfiguration?.mimeType)
            assertThat(one.mediaMetadata.title).isEqualTo(two.mediaMetadata.title)
            assertThat(one.mediaMetadata.artworkUri).isEqualTo(two.mediaMetadata.artworkUri)
            val a = one.mediaMetadata.extras!!
            val b = two.mediaMetadata.extras!!
            assertThat(a.keySet()).isEqualTo(b.keySet())
            for (key in a.keySet()) assertThat(a.get(key)).isEqualTo(b.get(key))
            assertThat(a.getString("playlistId")).isEqualTo("playlist-9")
        }
        // no playlist: no extra, as before
        assertThat(MediaItemBuilder.build(song(5)).mediaMetadata.extras!!.containsKey("playlistId")).isFalse()
        assertThat(MediaItemBuilder.build(song(5), null).mediaMetadata.extras!!.containsKey("playlistId")).isFalse()
    }

    @Test
    fun `one pass allocates and costs less than two for a playlist of 4938 songs`() {
        val songs = (0 until 4938).map(::song)
        repeat(2) { songs.forEach { twoPass(it, "p"); MediaItemBuilder.build(it, "p") } }   // warm up
        var twoBytes = 0L; var twoNanos = 0L; var oneBytes = 0L; var oneNanos = 0L
        repeat(3) {
            var bytes = allocatedBytes(); var t = System.nanoTime()
            songs.forEach { twoPass(it, "p") }
            twoNanos += System.nanoTime() - t; twoBytes += allocatedBytes() - bytes
            bytes = allocatedBytes(); t = System.nanoTime()
            songs.forEach { MediaItemBuilder.build(it, "p") }
            oneNanos += System.nanoTime() - t; oneBytes += allocatedBytes() - bytes
        }
        println("B47-A1 playlist items (host JVM under Robolectric, 4,938 songs, mean of 3): before ${twoBytes / 3 / 1_000_000} MB and ${twoNanos / 3 / 1_000_000} ms; " +
            "after ${oneBytes / 3 / 1_000_000} MB and ${oneNanos / 3 / 1_000_000} ms (${(100 - oneBytes * 100 / twoBytes)} % less allocation)")
        assertThat(oneBytes).isLessThan(twoBytes)
    }
}
