package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B51FolderArtworkTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val root = Files.createTempDirectory("b51-cover").toFile()
    private val folder = root.resolve("Album").apply { mkdirs() }
    private val song = folder.resolve("song.flac").apply { writeText("fixture without embedded art") }
    private val cover = folder.resolve("cover.jpg").apply { writeBytes(ByteArray(2048) { 33 }) }
    private val id = 5151L
    @After fun cleanup() {
        AlbumArtUtils.clearCacheForSong(context, id)
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }
    private fun refresh(eligible: Boolean = true, enabled: Boolean = true, downloaded: Boolean = false, embedded: ByteArray? = null, uri: String? = null) =
        AlbumArtUtils.refreshFolderArtwork(context, id, song.path, uri, eligible, enabled, downloaded) { embedded }

    @Test fun `mixed album folders and generic directory names are never eligible`() {
        assertThat(AlbumArtUtils.isSingleAlbumFolder(folder.path, listOf(1L to "A", 2L to "B"))).isFalse()
        assertThat(AlbumArtUtils.isSingleAlbumFolder("/storage/ABCD/Music", listOf(1L to "A"))).isFalse()
        assertThat(AlbumArtUtils.isSingleAlbumFolder(folder.path, listOf(1L to "Unknown Album"))).isFalse()
        assertThat(AlbumArtUtils.isSingleAlbumFolder(folder.path, listOf(1L to "A", 1L to "A"))).isTrue()
        assertThat(refresh(eligible = false).uri).isNull()
    }
    @Test fun `album cover is stored in the ordinary per-song cache and embedded art wins`() {
        assertThat(refresh().uri).startsWith(LocalArtworkUri.buildSongUri(id))
        assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, id).readBytes()).isEqualTo(cover.readBytes())
        cover.writeBytes(ByteArray(2048) { 44 })
        val embedded = ByteArray(2048) { 77 }
        refresh(embedded = embedded)
        assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, id).readBytes()).isEqualTo(embedded)
    }
    @Test fun `switch off prevents installation and clears existing folder covers on refresh`() {
        assertThat(refresh(enabled = false).uri).isNull()
        refresh()
        assertThat(refresh(enabled = false).uri).isNull()
        assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, id).exists()).isFalse()
    }
    @Test fun `changed and deleted image invalidate thumbnails and the artwork generation`() {
        val first = refresh()
        val thumb = AlbumArtUtils.getCachedThumbnailFile(context, id).apply { writeText("old thumbnail") }
        val generation = com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.generation(context, id)
        cover.writeBytes(ByteArray(2048) { 55 })
        val changed = refresh(uri = first.uri)
        assertThat(changed.changed).isTrue()
        assertThat(thumb.exists()).isFalse()
        assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, id).readBytes()).isEqualTo(cover.readBytes())
        assertThat(com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.generation(context, id)).isNotEqualTo(generation)
        cover.delete()
        assertThat(refresh(uri = changed.uri).uri).isNull()
        assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, id).exists()).isFalse()
    }
    @Test fun `a downloaded or chosen cover is preserved and an undersized image is rejected`() {
        assertThat(refresh(downloaded = true).changed).isFalse()
        val chosen = "content://chosen/cover"
        assertThat(refresh(uri = chosen).uri).isEqualTo(chosen)
        cover.writeBytes(ByteArray(1024))
        assertThat(refresh().uri).isNull()
    }
    @Test fun `unchanged folder source keeps the same artwork URI and cache generation`() {
        val first = refresh()
        val generation = com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.generation(context, id)
        val second = refresh(uri = first.uri)
        assertThat(second.changed).isFalse()
        assertThat(second.uri).isEqualTo(first.uri)
        assertThat(com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.generation(context, id)).isEqualTo(generation)
    }
}
