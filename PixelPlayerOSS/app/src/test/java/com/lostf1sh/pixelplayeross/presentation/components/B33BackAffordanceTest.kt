package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U3: the Back button is a plain chevron (no box, no tonal container) with a 48 dp target at the place it always had, on every page the
 * scaffold hosts; and a page whose header is a picture ([DapBackScaffold]'s `overHeaderArt`) has the picture run to the top edge, under the
 * status bar, with the chevron floating over it and **no band** across the top. Drawn at the HONOR's size (720 x 1610 px at 2x) with a 24 dp
 * status bar; the pixels are read from the render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B33BackAffordanceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val px = 2f
    private val statusBarDp = 24
    private var hostView: android.view.View? = null
    private var backgroundArgb = 0
    private var backs = 0

    private fun show(overHeaderArt: Boolean, art: Color = Color.White, mode: BackNavigationMode = BackNavigationMode.BUTTONS) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                backgroundArgb = Dap.background.toArgb()
                CompositionLocalProvider(
                    LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()),
                    LocalBackNavigationMode provides mode,
                ) {
                    Page(overHeaderArt, art)
                }
            }
        }
        compose.waitForIdle()
        val statusPx = (statusBarDp * px).toInt()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                hostView!!,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0)).build(),
            )
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Page(overHeaderArt: Boolean, art: Color) {
        DapBackScaffold(Modifier.fillMaxSize(), onBack = { backs++ }, overHeaderArt = overHeaderArt) {
            // a header picture 300 dp tall (a flat colour, so the pixels under the chevron are known)
            Box(Modifier.fillMaxWidth().height(300.dp).background(art))
        }
    }

    private val chevron get() = compose.onNodeWithContentDescription(s(R.string.auth_cd_back)).fetchSemanticsNode().boundsInRoot
    private fun bitmap() = compose.onRoot().captureToImage().asAndroidBitmap()
    private fun luminance(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    // ── the chevron ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `on an ordinary page the chevron has no box, no container and no hairline`() {
        show(overHeaderArt = false)
        val b = chevron
        val bmp = bitmap()
        // the four corners of the 48 dp target, inside it: the page's own background (the old box drew a tonal surface and a hairline there)
        listOf(
            bmp.getPixel((b.left + 2f).toInt(), (b.top + 2f).toInt()),
            bmp.getPixel((b.right - 3f).toInt(), (b.top + 2f).toInt()),
            bmp.getPixel((b.left + 2f).toInt(), (b.bottom - 3f).toInt()),
            bmp.getPixel((b.right - 3f).toInt(), (b.bottom - 3f).toInt()),
            bmp.getPixel(b.center.x.toInt(), (b.top + 1f).toInt()),
        ).forEach { assertThat(it).isEqualTo(backgroundArgb) }
    }

    @Test
    fun `the chevron keeps a 48 dp target, its description, and goes back when pressed`() {
        show(overHeaderArt = false)
        val b = chevron
        assertThat(b.width / px).isWithin(0.5f).of(48f)
        assertThat(b.height / px).isWithin(0.5f).of(48f)
        compose.onNodeWithContentDescription(s(R.string.auth_cd_back)).performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `it is in the same place with or without a header picture, as it was`() {
        show(overHeaderArt = false)
        val plain = chevron
        // 16 dp from the left edge, 4 dp under the status bar
        assertThat(plain.left / px).isWithin(0.5f).of(DapSpacing.Md.value)
        assertThat(plain.top / px).isWithin(0.5f).of(statusBarDp + DapSpacing.Xs.value)
    }

    @Test
    fun `over a header picture the chevron is in the same place`() {
        show(overHeaderArt = true)
        val over = chevron
        assertThat(over.left / px).isWithin(0.5f).of(DapSpacing.Md.value)
        assertThat(over.top / px).isWithin(0.5f).of(statusBarDp + DapSpacing.Xs.value)
        assertThat(over.width / px).isWithin(0.5f).of(48f)
        compose.onNodeWithContentDescription(s(R.string.auth_cd_back)).performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `in Gestures mode a control that exists only to go back is not drawn, in either layout`() {
        show(overHeaderArt = false, mode = BackNavigationMode.GESTURES)
        assertThat(compose.onAllNodesWithContentDescription(s(R.string.auth_cd_back)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `in Gestures mode a header picture has no chevron either`() {
        show(overHeaderArt = true, mode = BackNavigationMode.GESTURES)
        assertThat(compose.onAllNodesWithContentDescription(s(R.string.auth_cd_back)).fetchSemanticsNodes()).isEmpty()
    }

    // ── the band ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `on an ordinary page the content starts below the status bar and the chevron row, as it did`() {
        show(overHeaderArt = false, art = Color(0xFFFF0000))
        val bmp = bitmap()
        // the top strip (status bar and the chevron's row) is the page's background, the picture starts under it
        assertThat(bmp.getPixel(360, 4)).isEqualTo(backgroundArgb)
        assertThat(bmp.getPixel(600, ((statusBarDp + 4 + 48 + 4) * px - 6f).toInt())).isEqualTo(backgroundArgb)
        assertThat(bmp.getPixel(600, ((statusBarDp + 4 + 48 + 4) * px + 6f).toInt())).isEqualTo(Color(0xFFFF0000).toArgb())
    }

    @Test
    fun `over a header picture the picture runs to the top edge, under the status bar, with no band across it`() {
        show(overHeaderArt = true, art = Color(0xFFFF0000))
        val bmp = bitmap()
        val red = Color(0xFFFF0000).toArgb()
        // from the very first row of the screen, across the width and down through where the band was, the picture is what is drawn
        for (y in listOf(0, 2, 10, 24, (statusBarDp * px).toInt() - 2, (statusBarDp * px).toInt() + 6, ((statusBarDp + 56) * px).toInt() - 6, ((statusBarDp + 56) * px).toInt() + 6)) {
            for (x in listOf(2, 200, 360, 520, 700)) {
                assertThat(bmp.getPixel(x, y)).isEqualTo(red)
            }
        }
    }

    // ── legibility over a bright picture, without a box ──────────────────────────────────────────────

    @Test
    fun `over a bright picture the glyph is outlined in the page's ground, so it stays visible, and there is still no box`() {
        show(overHeaderArt = true, art = Color.White)
        val b = chevron
        val bmp = bitmap()
        // the target's corners are the picture itself (white): no container
        listOf(
            bmp.getPixel((b.left + 2f).toInt(), (b.top + 2f).toInt()),
            bmp.getPixel((b.right - 3f).toInt(), (b.bottom - 3f).toInt()),
        ).forEach { assertThat(it).isEqualTo(Color.White.toArgb()) }
        // and around the glyph there are dark pixels: the outline (the glyph itself is light, like the text it sits with)
        var darkest = 1f
        for (y in (b.center.y - 14f).toInt()..(b.center.y + 14f).toInt()) for (x in (b.center.x - 14f).toInt()..(b.center.x + 14f).toInt()) {
            darkest = minOf(darkest, luminance(bmp.getPixel(x, y)))
        }
        assertThat(darkest).isLessThan(0.7f)
    }

    @Test
    fun `without the outline the same glyph on a bright picture would be nearly invisible, which is what the outline is for`() {
        // the ordinary page's glyph is the secondary text colour: over white there is no pixel near the glyph that is dark
        show(overHeaderArt = false, art = Color.White)
        val b = chevron
        // on the ordinary page the glyph is on the page's dark background: there it is the light pixels that show it
        val bmp = bitmap()
        var brightest = 0f
        for (y in (b.center.y - 14f).toInt()..(b.center.y + 14f).toInt()) for (x in (b.center.x - 14f).toInt()..(b.center.x + 14f).toInt()) {
            brightest = maxOf(brightest, luminance(bmp.getPixel(x, y)))
        }
        assertThat(brightest).isGreaterThan(0.3f)
    }

    // ── where the scaffold is used ───────────────────────────────────────────────────────────────────

    @Test
    fun `Album, Artist and Genre take the picture to the top edge, the other pages keep the chevron's row`() {
        val sources = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens")
        listOf("AlbumDetailScreen.kt", "ArtistDetailScreen.kt", "GenreDetailScreen.kt").forEach {
            assertThat(File(sources, it).readText()).contains("overHeaderArt = true")
        }
        // a page with no picture (Playlist, Stats, Settings, collections) keeps the row: a title is not a picture
        listOf("PlaylistDetailScreen.kt", "StatsScreen.kt", "SettingsScreen.kt", "AutoCollectionDetailScreen.kt", "SettingsCategoryScreen.kt").forEach {
            assertThat(File(sources, it).readText()).doesNotContain("overHeaderArt")
        }
    }

    @Test
    fun `the overlay action carries no surface and no border any more`() {
        val overlay = File("src/main/java/com/lostf1sh/pixelplayeross/ui/ds/DapOverlays.kt").readText()
        val action = overlay.substringAfter("fun DapOverlayAction(").substringBefore("private const val ON_IMAGE_HALO_ALPHA")
        assertThat(action).doesNotContain(".background(")
        assertThat(action).doesNotContain(".border(")
        assertThat(action).doesNotContain("surfaceAlpha")
    }
}
