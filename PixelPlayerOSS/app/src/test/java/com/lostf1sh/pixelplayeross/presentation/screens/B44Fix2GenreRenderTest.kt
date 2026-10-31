package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B44Fix2GenreRenderTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.filesDir, "b44-fix2-genre-fixture.png")
    @After fun cleanup() { file.delete() }
    private fun genre(): Pair<Genre, Boolean> {
        val image = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        image.eraseColor(0xffe040a0.toInt())
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val genre = Genre("rock", "Rock", songCount = 15)
        val available = runCatching {
            genre.javaClass.getDeclaredField("imagePath").apply { isAccessible = true }.set(genre, file.absolutePath)
        }.isSuccess
        return genre to available
    }
    private fun save(name: String): Bitmap {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val phase = if (File(".").canonicalPath.contains("B44-fix2-before")) "before" else "after"
        val folder = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-fix2-evidence/$phase").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
    private fun pinkPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.blue(pixel) > 90 && android.graphics.Color.green(pixel) < 100) count++
        }
        return count
    }
    private fun expectImage(name: String, available: Boolean) {
        var bitmap = save(name)
        if (available) {
            repeat(15) {
                if (pinkPixels(bitmap) < 300) {
                    Thread.sleep(100)
                    bitmap = save(name)
                }
            }
        }
        assertThat(available).isTrue()
        assertThat(pinkPixels(bitmap)).isGreaterThan(300)
    }
    @Test fun `genre list shows one own image and aligned missing-cover placeholders`() {
        val (rock, available) = genre()
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.isGenreGridView } returns MutableStateFlow(false)
        every { player.navBarCompactMode } returns MutableStateFlow(false)
        every { player.customGenreIcons } returns MutableStateFlow(emptyMap())
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    Box(Modifier.fillMaxSize().padding(top = 37.dp, bottom = 22.dp)) {
                        GenreCategoriesGrid(persistentListOf(rock, Genre("jazz", "Jazz", songCount = 7), Genre("pop", "Pop", songCount = 12)), {}, player)
                    }
                }
            }
        }
        compose.onNodeWithText("Rock").assertIsDisplayed()
        expectImage("genre-list", available)
    }
    @Test fun `genre page header shows the chosen image`() {
        val (rock, available) = genre()
        val song = Song.emptySong().copy(id = "1", title = "Example song", artist = "Example artist")
        compose.setContent {
            DapTheme(darkTheme = true) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation provides
                        com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority(androidx.activity.OnBackPressedDispatcher())
                ) {
                    GenrePage("Rock", GenreDetailUiState(genre = rock, songs = listOf(song)), null, false,
                        {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        expectImage("genre-header", available)
    }
}
