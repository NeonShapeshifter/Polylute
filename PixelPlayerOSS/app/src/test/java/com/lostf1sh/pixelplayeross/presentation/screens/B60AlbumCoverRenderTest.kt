package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.toEntity
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.FolderArtworkRefresh
import com.lostf1sh.pixelplayeross.presentation.B60Renders
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B60 item 2: the album page's big header and a grid tile of a SAYONARA-like album (disc 1 and disc 2 have their own pictures, the folder has a `cover.jpg`), **made from what the real
 * folder step cached** (Robolectric over made-up pictures, not the owner's covers). After: the album's own cover (teal) in the header and the tile, the songs with their disc's (red, blue).
 * Before (B59's rule, reconstructed: the album takes the lowest-id song's artwork): the header and the tile show disc 1's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B60AlbumCoverRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun picture(color: Int, bars: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(70, 255, 255, 255) }
            for (i in 0 until bars) drawCircle(240f, 240f, 220f - i * (200f / bars), paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun flac(): ByteArray = ByteArrayOutputStream().also { out ->
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 34)); out.write(ByteArray(34) { 3 })
        out.write(byteArrayOf(0x81.toByte(), 0, 1, 0)); out.write(ByteArray(256) { 3 })
        out.write(ByteArray(500))
    }.toByteArray()

    private fun decode(bytes: ByteArray?): ImageBitmap? = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

    private fun page(album: ImageBitmap?, disc1: ImageBitmap?, disc2: ImageBitmap?, title: String) = compose.setContent {
        DapTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize(), color = Dap.background) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, style = DapType.Note, color = Dap.secondaryText)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) { if (album != null) Image(album, "album header", Modifier.fillMaxSize()) }
                    Text("SAYONARA FINALES ALTERNOS", style = DapType.Title, color = Dap.primaryText)
                    Text("Alvaro Diaz · 2024 · 23 songs · 1 h 12 min", style = DapType.Note, color = Dap.secondaryText)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(120.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) { if (album != null) Image(album, "grid tile", Modifier.fillMaxSize()) }
                            Text("SAYONARA FINALES ALTERNOS", style = DapType.Note, color = Dap.primaryText, maxLines = 1)
                        }
                        Text("grid tile", style = DapType.Note, color = Dap.secondaryText)
                    }
                    DapAlbumRow("1-01  Disc 1 song", "3:42", artwork = { if (disc1 != null) Image(disc1, null, Modifier.fillMaxSize()) })
                    DapAlbumRow("2-01  Disc 2 song", "3:11", artwork = { if (disc2 != null) Image(disc2, null, Modifier.fillMaxSize()) })
                }
            }
        }
    }

    private class Fixture(val album: ImageBitmap?, val first: ImageBitmap?, val last: ImageBitmap?, val albumBytes: ByteArray?, val coverBytes: ByteArray)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = Files.createTempDirectory("b60-render").toFile()
    private val ids = mutableListOf<Long>()

    @org.junit.After fun cleanup() {
        ids.forEach { AlbumArtUtils.clearCacheForSong(context, it) }
        context.getSharedPreferences("folder_art_sources", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("album_cover_sources", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    /** The folder step run for real over a two-disc folder with `disc1.jpg`, `disc2.jpg` and `cover.jpg`. */
    private fun built(): Fixture {
        val folder = root.resolve("SAYONARA").apply { mkdirs() }
        val disc1 = picture(Color.rgb(142, 59, 47), 5); val disc2 = picture(Color.rgb(47, 111, 142), 7); val cover = picture(Color.rgb(27, 90, 80), 9)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        val songs = (0 until 6).map { i ->
            val disc = if (i < 3) 1 else 2
            val file = folder.resolve("%d-%02d.flac".format(disc, i + 1)).apply { writeBytes(flac()) }
            Song.emptySong().copy(id = (800 + i).toString(), album = "SAYONARA FINALES ALTERNOS", albumId = 9L, path = file.path).toEntity(file.path, folder.path).copy(discNumber = disc)
        }
        ids += songs.map { it.id }; ids += LocalArtworkUri.albumCoverKey(9L)
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        every { prefs.folderCoversEnabledFlow } returns flowOf(true)
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } returns songs
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } returns null
        runBlocking { FolderArtworkRefresh(context, dao, prefs, info, mockk<ColorSchemeProcessor>(relaxed = true)).refresh() }
        fun cached(id: Long) = AlbumArtUtils.getCachedAlbumArtFile(context, id).takeIf { it.isFile }?.readBytes()
        val albumBytes = cached(LocalArtworkUri.albumCoverKey(9L))
        return Fixture(decode(albumBytes), decode(cached(songs.first().id)), decode(cached(songs.last().id)), albumBytes, cover)
    }

    @Test fun `before - B59's rule, the album takes the first song's artwork, which is disc 1's`() {
        val f = built()
        page(f.first, f.first, f.last, "BEFORE (B59's rule: the album takes the lowest-id song's artwork)")
        B60Renders.save(compose, "album-header-and-tile", phase = "before")
    }

    @Test fun `after - the album header and the grid tile show the album's own cover and the songs their disc's`() {
        val f = built()
        assertThat(f.album).isNotNull()
        assertThat(f.albumBytes).isEqualTo(f.coverBytes) // cover.jpg, not a disc's picture
        page(f.album, f.first, f.last, "AFTER (B60: the album's own cover; the songs keep their disc's)")
        B60Renders.save(compose, "album-header-and-tile", phase = "after")
    }
}
