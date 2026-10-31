package com.lostf1sh.pixelplayeross.presentation.components.player

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class B44Fix1MetadataTest {
    @get:Rule val compose = createComposeRule()
    private data class Case(val name: String, val artist: String, val album: String, val scrollArtist: Boolean, val scrollAlbum: Boolean)
    private val cases = listOf(
        Case("fit", "Artist", "Album", false, false),
        Case("artist", "Post Malone, Luke Combs and a very long artist credit", "Gaia", true, false),
        Case("album", "Artist", "F-1 Trillion Long Bed with a very long album edition", false, true),
        Case("both", "Post Malone, Luke Combs and a very long artist credit", "F-1 Trillion Long Bed with a very long album edition", true, true),
    )
    private val selected = mutableStateOf(cases.first())
    private val font = mutableStateOf(1f)
    private val bookmark = mutableStateOf(true)
    private val visible = mutableStateOf(true)
    private val calls = mutableListOf<String>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val actions = object : FullPlayerActions by NoActions() {
        override fun onOpenAlbum(albumId: Long) { calls += "album" }
        override fun onOpenArtist(artistId: Long) { calls += "artist-long" }
    }
    private fun show() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, font.value),
                com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme provides androidx.compose.material3.darkColorScheme(),
            ) {
                DapTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = Dap.background) {
                        Box(Modifier.fillMaxSize().padding(top = 160.dp, start = 24.dp, end = 24.dp)) {
                            val value = selected.value
                            val song = Song.emptySong().copy(title = "Guy For That (Feat. Luke Combs)", artist = value.artist,
                                album = value.album, artistId = 7, albumId = 9)
                            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            Box(Modifier.fillMaxWidth(if (landscape) 0.5f else 1f)) {
                            PlayerTrackInfoSurface(
                                PlayerTrackInfoState(song, persistentListOf(), false, false, { true }, bookmark.value),
                                PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED,
                                    visible.value, { 1f }), actions, landscape, { calls += "artist" }, { calls += "bookmark" },
                            )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun scrolls(text: String): Boolean = compose.onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode().config.any { it.key.name == "PlayerMetadataScrolling" && it.value == true }

    @After fun resetScale() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test fun `title actions and independent metadata halves match the owner design`() {
        show()
        val failures = mutableListOf<String>()
        for (scale in listOf(1f, 1.3f)) for (showBookmark in listOf(true, false)) for (value in cases) {
            compose.runOnIdle {
                selected.value = value
                font.value = scale
                bookmark.value = showBookmark
            }
            compose.waitForIdle()
            val title = compose.onNodeWithText("Guy For That (Feat. Luke Combs)", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val heart = compose.onNodeWithContentDescription("Add to favorites").fetchSemanticsNode().boundsInRoot
            val artist = compose.onNodeWithText(value.artist, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val album = compose.onNodeWithText(value.album, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if (kotlin.math.abs(title.center.y - heart.center.y) > 1f) failures += "title action alignment $value $scale"
            if (kotlin.math.abs(artist.top - album.top) > 1f) failures += "artist and album need one row $value $scale"
            if (artist.top < title.bottom) failures += "metadata below title $value $scale"
            if (scrolls(value.artist) != value.scrollArtist || scrolls(value.album) != value.scrollAlbum) failures += "scroll flags $value $scale"
            // B47-B1: two halves only when BOTH are long; a short side keeps its natural width and the long one takes the rest (B47PlayerLineTest has the widths)
            if (value.scrollArtist && value.scrollAlbum) {
                if (kotlin.math.abs(artist.width - album.width) > 1f) failures += "equal halves $value"
            }
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            if (album.right > screen.right || album.bottom > screen.bottom || artist.left < screen.left) failures += "viewport $value"
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value.artist, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            if (layouts.single().layoutInput.style.fontSize.value != 16f) failures += "lock artist reference size $value"
            save("metadata-${value.name}-font-$scale-bookmark-$showBookmark")
        }
        assertThat(failures).isEmpty()
        selected.value = cases.first()
        compose.waitForIdle()
        compose.onNodeWithText("Artist", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Artist", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("Album", useUnmergedTree = true).performClick()
        assertThat(calls).containsAtLeast("artist", "artist-long", "album")
    }

    @Test fun `metadata marquee stops for reduced motion and a hidden player`() {
        selected.value = cases.last()
        show()
        assertThat(scrolls(cases.last().artist)).isTrue()
        compose.runOnIdle { Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) }
        compose.waitForIdle()
        assertThat(scrolls(cases.last().artist)).isFalse()
        assertThat(scrolls(cases.last().album)).isFalse()
        compose.runOnIdle {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            visible.value = false
        }
        compose.waitForIdle()
        assertThat(scrolls(cases.last().artist)).isFalse()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertThat(scrolls(cases.last().artist)).isTrue()
    }

    private fun save(name: String) {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val phase = if (File(".").canonicalPath.contains("B44-fix1-before")) "before" else "after"
        val folder = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-fix1-evidence/$phase")
        folder.mkdirs()
        File(folder, "$name-${bitmap.width}x${bitmap.height}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class B44Fix1MetadataTallTest : B44Fix1MetadataTest()

@Config(sdk = [35], application = Application::class, qualifiers = "w805dp-h360dp-land-xhdpi")
class B44Fix1MetadataLandscapeTest : B44Fix1MetadataTest()
