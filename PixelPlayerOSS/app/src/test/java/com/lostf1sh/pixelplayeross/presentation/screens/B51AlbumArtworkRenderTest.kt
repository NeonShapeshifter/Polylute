package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Same host fixture and assertion run against both versions; the old path has no folder refresh API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B51AlbumArtworkRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `album without embedded art uses its folder cover throughout the existing artwork cache`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("b51-album-render").toFile()
        val album = root.resolve("Luz de otoño").apply { mkdirs() }
        val song = album.resolve("01.flac").apply { writeText("no embedded picture") }
        val id = 510051L
        val cover = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        Canvas(cover).apply {
            drawColor(Color.rgb(27, 55, 63))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (index in 0..18) {
                paint.color = Color.rgb(91 + index * 5, 150 - index * 3, 130 - index * 2)
                drawCircle(90f + index * 17, 240f, 240f - index * 11, paint)
            }
        }
        album.resolve("cover.jpg").outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        try {
            AlbumArtUtils.clearCacheForSong(context, id)
            val method = AlbumArtUtils::class.java.methods.firstOrNull { it.name.startsWith("refreshFolderArtwork") && it.parameterCount == 8 }
            val uri = if (method != null) {
                val noEmbedded: (String) -> ByteArray? = { null }
                val result = method.invoke(AlbumArtUtils, context, id, song.path, null, true, true, false, noEmbedded)
                result.javaClass.getMethod("getUri").invoke(result) as String?
            } else AlbumArtUtils.getAlbumArtUri(context, song.path, id, false)
            val image = if (uri != null) BitmapFactory.decodeFile(AlbumArtUtils.getCachedAlbumArtFile(context, id).path) else null
            compose.setContent {
                DapTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = Dap.background) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("Álbum", style = DapType.Title, color = Dap.primaryText)
                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                if (image != null) Image(image.asImageBitmap(), "Carátula del álbum", modifier = Modifier.fillMaxSize())
                                else DapArtworkPlaceholder(Modifier.fillMaxSize())
                            }
                            Text("Luz de otoño", style = DapType.Title, color = Dap.primaryText)
                            Text("Artista de prueba · 2026", style = DapType.Note, color = Dap.secondaryText)
                            DapAlbumRow("Primera canción", "3:42", artwork = {
                                if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize()) else DapArtworkPlaceholder(Modifier.fillMaxSize())
                            })
                        }
                    }
                }
            }
            val bitmap = B42Renders.screen(compose)
            assertThat(bitmap.width).isEqualTo(720)
            assertThat(bitmap.height).isEqualTo(1610)
            val dir = File(System.getenv("B51_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B51-gates/png-after").apply { mkdirs() }
            File(dir, "album-cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertThat(uri).isNotNull()
            assertThat(image).isNotNull()
        } finally {
            AlbumArtUtils.clearCacheForSong(context, id)
            context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
