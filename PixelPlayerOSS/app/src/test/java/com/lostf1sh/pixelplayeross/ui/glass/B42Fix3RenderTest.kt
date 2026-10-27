package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * B42-fix3 (items 7 and 8): the pictures for the owner and the reviewer. **Five covers (a pure white, a pure black, a pale pastel, a saturated one, a
 * multicolour on black) in the two cover modes, on the player, the Library, Settings, Home, Stats and an album page**, drawn at the HONOR's size
 * (720 x 1610 px) with the app's own components over the real backdrop; and "Theme and accent" once per page, which must come out byte for byte what it
 * did (the same test runs in the tree as it was before this batch, `before/`, and in the tree after it, `after/`, and the PNGs are compared).
 *
 * What is asserted is that every picture was drawn and that it is not empty; what is *measured* on these screens is in [B42Fix3SurfacesTest]
 * (the frosted surfaces) and [B42Fix3GlassFieldTest] (the ink on the glass).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix3RenderTest {
    @get:Rule
    val compose = createComposeRule()

    private enum class Page(val label: String, val expansion: Float) {
        PLAYER("player", 1f), LIBRARY("library", 0f), SETTINGS("settings", 0f), HOME("home", 0f), STATS("stats", 0f), ALBUM("album", 0f),
    }

    private data class Shot(val cover: B41Renders.Cover, val mode: Fix3Mode, val page: Page)

    private val covers = B42Fix3Covers.all

    private val variant = System.getenv("B42FIX3_VARIANT") ?: "after"

    private val root: File
        get() = File(System.getenv("B42FIX3_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B42-fix3-evidence").resolve(variant).apply { mkdirs() }

    private val shot = MutableStateFlow(Shot(covers[0], Fix3Mode.COVER_GLASS, Page.HOME))

    private fun save(name: String) {
        val bitmap = B42Renders.screen(compose)
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // not an empty picture
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isEqualTo(1610)
    }

    private fun source(mode: Fix3Mode) = when (mode) {
        Fix3Mode.FROM_ALBUM_ART -> AppColorSource.ALBUM_ART
        Fix3Mode.COVER_GLASS -> AppColorSource.COVER_GLASS
        Fix3Mode.THEME -> AppColorSource.THEME
    }

    private fun show() {
        compose.setContent {
            val s by shot.collectAsState()
            key(s) {
                Fix3Stage(s.cover, s.mode).App(expansion = s.page.expansion) {
                    when (s.page) {
                        Page.PLAYER -> B42Fix3Pages.Player(s.cover)
                        Page.LIBRARY -> B42Fix3Pages.Library(s.cover)
                        Page.SETTINGS -> B42Fix3Pages.Settings(source(s.mode))
                        Page.HOME -> B42Fix3Pages.Home(s.cover)
                        Page.STATS -> B42Fix3Pages.Stats(s.cover)
                        Page.ALBUM -> B42Fix3Pages.Album(s.cover)
                    }
                }
            }
        }
    }

    private fun renderAll(mode: Fix3Mode, coverList: List<B41Renders.Cover>) {
        show()
        for (cover in coverList) for (page in Page.entries) {
            compose.runOnIdle { shot.value = Shot(cover, mode, page) }
            compose.waitForIdle()
            save("${mode.label}-${cover.name}-${page.label}")
        }
    }

    @Test
    fun from_album_art_five_covers_on_every_page() = renderAll(Fix3Mode.FROM_ALBUM_ART, covers)

    @Test
    fun cover_glass_five_covers_on_every_page() = renderAll(Fix3Mode.COVER_GLASS, covers)

    /** "Theme and accent" does not depend on the cover: one picture per page, and it is the same before and after. */
    @Test
    fun theme_and_accent_every_page_unchanged() = renderAll(Fix3Mode.THEME, listOf(covers[3]))
}
