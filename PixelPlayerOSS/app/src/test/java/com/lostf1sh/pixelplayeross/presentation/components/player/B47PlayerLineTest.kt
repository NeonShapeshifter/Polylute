package com.lostf1sh.pixelplayeross.presentation.components.player

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B47-B: the player's title row and its artist · album line on the owner's HONOR (360 x 805 dp), the cases of his screenshots of 2026-10-29.
 *
 * `s1`: "No Te Pido Flores" / "Fanny Lu · Lágrimas Cálidas" fits on one line and stays exactly as it was.
 * `s2`: "WHERE SHE GOES" / "Bad Bunny" with the album "nadie sabe lo que va a pasar mañana": the artist keeps its natural width, the separator is there, and the album takes all the
 * rest of the line and scrolls (it took half of it, with half the screen empty, and the separator disappeared).
 * And a long title scrolls instead of ending in "…", with the same marquee and the same gate as the line below it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B47PlayerLineTest {
    @get:Rule val compose = createComposeRule()

    private val title = mutableStateOf("WHERE SHE GOES")
    private val artist = mutableStateOf("Bad Bunny")
    private val album = mutableStateOf("nadie sabe lo que va a pasar mañana")
    private val bookmark = mutableStateOf(true)
    private val font = mutableStateOf(1f)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sidePadding = 24.dp

    private fun show(titleText: String, artistText: String, albumText: String, showBookmark: Boolean = true, scale: Float = 1f) {
        title.value = titleText
        artist.value = artistText
        album.value = albumText
        bookmark.value = showBookmark
        font.value = scale
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, font.value),
                com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme provides androidx.compose.material3.darkColorScheme(),
            ) {
                DapTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = Dap.background) {
                        Box(Modifier.fillMaxSize().padding(top = 160.dp, start = sidePadding, end = sidePadding)) {
                            val song = Song.emptySong().copy(title = title.value, artist = artist.value, album = album.value, artistId = 7, albumId = 9)
                            Box(Modifier.fillMaxWidth()) {
                                PlayerTrackInfoSurface(
                                    PlayerTrackInfoState(song, persistentListOf(), false, false, { true }, bookmark.value),
                                    PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, true, { 1f }),
                                    object : FullPlayerActions by NoActions() {},
                                    false, {}, {},
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun resetScale() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun bounds(text: String): Rect = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun scrolls(text: String): Boolean = compose.onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode().config.any { it.key.name == "PlayerMetadataScrolling" && it.value == true }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    /** The line's right edge: the screen's less the padding on its right. */
    private fun lineRight(): Float = compose.onRoot().fetchSemanticsNode().boundsInRoot.right - sidePadding.value * context.resources.displayMetrics.density
    private fun lineLeft(): Float = sidePadding.value * context.resources.displayMetrics.density

    private fun near(a: Float, b: Float, tolerance: Float = 1.5f) = kotlin.math.abs(a - b) <= tolerance

    private val separator = " · "
    private val longArtist = "Post Malone, Luke Combs and a very long artist credit"
    private val longAlbum = "nadie sabe lo que va a pasar mañana"

    @Test fun `s1 Fanny Lu fits on one line and nothing moves`() {
        show("No Te Pido Flores", "Fanny Lu", "Lágrimas Cálidas")
        save("s1-fanny-lu")
        val a = bounds("Fanny Lu")
        val s = bounds(separator)
        val b = bounds("Lágrimas Cálidas")
        assertThat(scrolls("Fanny Lu")).isFalse()
        assertThat(scrolls("Lágrimas Cálidas")).isFalse()
        assertThat(near(a.left, lineLeft())).isTrue()
        assertThat(near(s.left, a.right)).isTrue()      // natural widths, contiguous: "Fanny Lu · Lágrimas Cálidas"
        assertThat(near(b.left, s.right)).isTrue()
        assertThat(kotlin.math.abs(a.top - b.top)).isLessThan(1f)
        assertThat(b.right).isLessThan(lineRight())      // the line is not filled: nothing was stretched
        val naturalAlbum = layoutOf("Lágrimas Cálidas").let { it.getLineRight(0) - it.getLineLeft(0) }
        assertThat(b.width).isAtLeast(naturalAlbum - 1f)     // not clipped
    }

    @Test fun `s2 Bad Bunny keeps its width and the album takes all the rest of the line and scrolls`() {
        show("WHERE SHE GOES", "Bad Bunny", longAlbum)
        save("s2-bad-bunny")
        val a = bounds("Bad Bunny")
        val s = bounds(separator)
        val b = bounds(longAlbum)
        val natural = layoutOf("Bad Bunny").let { it.getLineRight(0) - it.getLineLeft(0) }
        assertThat(near(a.width, natural)).isTrue()                    // the artist at its natural width, not half the line
        assertThat(near(s.left, a.right)).isTrue()                     // the separator is there, right after it
        assertThat(near(b.left, s.right)).isTrue()                     // the album starts right after the separator ...
        assertThat(near(b.right, lineRight())).isTrue()                // ... and takes all the rest
        assertThat(a.width).isLessThan((lineRight() - lineLeft()) / 2)
        assertThat(scrolls("Bad Bunny")).isFalse()
        assertThat(scrolls(longAlbum)).isTrue()                        // only the album scrolls
        assertThat(kotlin.math.abs(a.top - b.top)).isLessThan(1f)
    }

    @Test fun `a long artist and a short album mirror it`() {
        show("Guy For That", longArtist, "Gaia")
        val a = bounds(longArtist)
        val s = bounds(separator)
        val b = bounds("Gaia")
        val natural = layoutOf("Gaia").let { it.getLineRight(0) - it.getLineLeft(0) }
        assertThat(near(b.width, natural)).isTrue()
        assertThat(near(b.right, s.right + b.width)).isTrue()
        assertThat(near(a.left, lineLeft())).isTrue()
        assertThat(near(a.right, s.left)).isTrue()                     // the artist runs up to the separator
        assertThat(near(b.right, lineRight()) || b.right < lineRight()).isTrue()
        assertThat(near(a.width + s.width + b.width, lineRight() - lineLeft(), 2.5f)).isTrue()
        assertThat(scrolls(longArtist)).isTrue()
        assertThat(scrolls("Gaia")).isFalse()
    }

    @Test fun `both long share the line in halves with the separator between`() {
        show("Guy For That", longArtist, "F-1 Trillion Long Bed with a very long album edition")
        val a = bounds(longArtist)
        val s = bounds(separator)
        val b = bounds("F-1 Trillion Long Bed with a very long album edition")
        assertThat(near(a.width, b.width)).isTrue()
        assertThat(near(a.width, (lineRight() - lineLeft() - s.width) / 2, 2.5f)).isTrue()
        assertThat(near(s.left, a.right) && near(b.left, s.right)).isTrue()
        assertThat(scrolls(longArtist)).isTrue()
        assertThat(scrolls("F-1 Trillion Long Bed with a very long album edition")).isTrue()
    }

    @Test fun `reduced motion stops the marquee and the overflowing side ends in an ellipsis`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show("A title that is certainly far too long to fit beside the two buttons of the player", "Bad Bunny", longAlbum)
        assertThat(scrolls(longAlbum)).isFalse()
        assertThat(scrolls("A title that is certainly far too long to fit beside the two buttons of the player")).isFalse()
        val albumLayout = layoutOf(longAlbum)
        assertThat(albumLayout.layoutInput.overflow).isEqualTo(TextOverflow.Ellipsis)
        assertThat(albumLayout.hasVisualOverflow).isTrue()
        val titleLayout = layoutOf("A title that is certainly far too long to fit beside the two buttons of the player")
        assertThat(titleLayout.layoutInput.overflow).isEqualTo(TextOverflow.Ellipsis)
        assertThat(titleLayout.hasVisualOverflow).isTrue()   // it ends in "…", as it always did
        // the separator and the geometry are the same as with motion
        val a = bounds("Bad Bunny")
        assertThat(near(bounds(longAlbum).right, lineRight())).isTrue()
        assertThat(near(bounds(separator).left, a.right)).isTrue()
    }

    private val longTitle = "A title that is certainly far too long to fit beside the two buttons of the player"

    @Test fun `a short title does not scroll and a long one does, with the buttons where they were`() {
        show("WHERE SHE GOES", "Bad Bunny", "Album")
        val shortHeart = compose.onNodeWithContentDescription("Add to favorites").fetchSemanticsNode().boundsInRoot
        val shortBookmark = compose.onNodeWithContentDescription("Add bookmark").fetchSemanticsNode().boundsInRoot
        assertThat(scrolls("WHERE SHE GOES")).isFalse()
        assertThat(bounds("WHERE SHE GOES").width).isAtLeast(layoutOf("WHERE SHE GOES").let { it.getLineRight(0) - it.getLineLeft(0) } - 1f)
        compose.runOnIdle { title.value = longTitle }
        compose.waitForIdle()
        save("title-long")
        val heart = compose.onNodeWithContentDescription("Add to favorites").fetchSemanticsNode().boundsInRoot
        val bookmarkButton = compose.onNodeWithContentDescription("Add bookmark").fetchSemanticsNode().boundsInRoot
        val titleBounds = bounds(longTitle)
        assertThat(scrolls(longTitle)).isTrue()                           // it scrolls instead of ending in "…"
        assertThat(heart).isEqualTo(shortHeart)                           // the buttons neither move nor shrink
        assertThat(bookmarkButton).isEqualTo(shortBookmark)
        assertThat(titleBounds.right).isAtMost(bookmarkButton.left + 1f)  // the title stays on the space they leave
        assertThat(kotlin.math.abs(titleBounds.center.y - heart.center.y)).isLessThan(1f)
    }

    @Test fun `the title scrolls at font scale 1_3 too and the line keeps its shape`() {
        show(longTitle, "Bad Bunny", longAlbum, scale = 1.3f)
        assertThat(scrolls(longTitle)).isTrue()
        assertThat(scrolls("Bad Bunny")).isFalse()
        assertThat(scrolls(longAlbum)).isTrue()
        assertThat(near(bounds(longAlbum).right, lineRight())).isTrue()
        assertThat(near(bounds(separator).left, bounds("Bad Bunny").right)).isTrue()
        save("scale-1.3")
    }

    @Test fun `the line plan decides the same from widths alone`() {
        // widths in px at 360 dp x 3 = 1080: the line is 936 px, the separator 24 px
        assertThat(planArtistAlbumLine(300, 400, 24, 936)).isEqualTo(ArtistAlbumLinePlan(false, false, false, false))   // fits
        assertThat(planArtistAlbumLine(300, 900, 24, 936)).isEqualTo(ArtistAlbumLinePlan(false, true, false, true))     // short artist, long album
        assertThat(planArtistAlbumLine(900, 300, 24, 936)).isEqualTo(ArtistAlbumLinePlan(true, false, true, false))     // the mirror
        assertThat(planArtistAlbumLine(700, 700, 24, 936)).isEqualTo(ArtistAlbumLinePlan(true, true, true, true))       // both long: halves
        assertThat(planArtistAlbumLine(456, 900, 24, 936)).isEqualTo(ArtistAlbumLinePlan(false, true, false, true))     // exactly a half (456) is still short
        assertThat(planArtistAlbumLine(400, 400, 24, 824)).isEqualTo(ArtistAlbumLinePlan(false, false, false, false))   // exactly full: still together
        assertThat(planArtistAlbumLine(400, 401, 24, 824)).isEqualTo(ArtistAlbumLinePlan(false, true, false, true))     // one px over: the artist (400 <= 400) is short
    }

    private fun save(name: String) {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val phase = if (File(".").canonicalPath.contains("B47-before-ui")) "before" else "after"
        val folder = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B47-evidence/$phase")
        folder.mkdirs()
        File(folder, "$name-${bitmap.width}x${bitmap.height}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
