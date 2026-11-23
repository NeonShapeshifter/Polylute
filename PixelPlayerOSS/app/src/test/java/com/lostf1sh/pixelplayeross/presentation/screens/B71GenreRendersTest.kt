package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.Description
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_COLLAGE_TAG
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_COVER_TAG
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_ICON_TAG
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_LETTER_TAG
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_READY_TAG
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_SINGLE_TAG
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.chosenIcon
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.genreCoverRequestSize
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreAlbum
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreArtist
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class B71GenreRendersTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val language get() = context.resources.configuration.locales[0].language
    private val dark = mutableStateOf(true)
    private val mode = mutableStateOf("header-photo")
    private val chosen = mutableStateOf<String?>(null)
    private val icons = MutableStateFlow(emptyMap<String, Int>())
    private val grid = MutableStateFlow(true)
    private val opened = mutableListOf<Long>()
    private val artist = GenreArtist(42, "Cedar Atlas", 28, 6)
    private val photo by lazy { fixture("cedar-atlas", 0xff366b71.toInt(), portrait = true) }
    private val selected by lazy { fixture("custom", 0xffc62f78.toInt()) }
    private val covers by lazy {
        listOf(0xffa55242, 0xff456d9a, 0xff9a8149, 0xff715893, 0xff45866e, 0xffa55b83).mapIndexed { index, color ->
            fixture("cover-$index", color.toInt())
        }.toImmutableList()
    }
    private val description get() = Description(
        when (language) {
            "es" -> "Una descripción sintética con varias líneas para comprobar el recorte que se aplica al principio. El texto continúa con otra oración y termina aquí para que la expansión se pueda comprobar."
            "sv" -> "En syntetisk beskrivning på flera rader som visar hur texten förkortas i början. Texten fortsätter med en till mening och slutar här så att det går att kontrollera den utfällda vyn."
            else -> "A synthetic description spanning several lines to show where the opening summary is clipped. A second sentence continues the text so the expanded state can be verified."
        },
        "Rock music", "https://$language.wikipedia.org/wiki/Rock_music", language,
    )
    private val songs by lazy {
        listOf("Amber Signal", "Birch Walk", "Found Light", "Grey Coast", "Tide Map").mapIndexed { i, title ->
            Song.emptySong().copy(id = "$i", title = title, artist = artist.name, artistId = artist.artistId,
                duration = 10_080_000, albumArtUriString = covers[i])
        }
    }

    private fun fixture(name: String, color: Int, portrait: Boolean = false): String {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xffdbc5a4.toInt() }
        if (portrait) {
            canvas.drawCircle(200f, 143f, 55f, paint)
            paint.color = 0xff202c3b.toInt()
            canvas.drawOval(77f, 220f, 323f, 437f, paint)
        } else {
            paint.color = Color.argb(105, 235, 219, 183)
            canvas.drawCircle(263f, 118f, 80f, paint)
            canvas.drawRect(42f, 205f, 358f, 215f, paint)
        }
        val file = File(context.cacheDir, "b71-$name.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun show() {
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.isGenreGridView } returns grid
        every { player.navBarCompactMode } returns MutableStateFlow(false)
        every { player.customGenreIcons } returns icons
        compose.setContent {
            DapTheme(darkTheme = dark.value, baseTheme = if (dark.value) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    val customIcons by icons.collectAsState()
                    val rock = Genre("rock", "Rock", songCount = 214, imagePath = chosen.value, albumCoverUris = covers)
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        if (mode.value == "genres") {
                            GenreCategoriesGrid(persistentListOf(
                                rock,
                                Genre("pop", "Pop", songCount = 486, albumCoverUris = covers.drop(2).toImmutableList()),
                                Genre("jazz", "Jazz", songCount = 82),
                                Genre("ambient", "Ambient", songCount = 15, albumCoverUris = covers.take(1).toImmutableList()),
                            ), {}, player, bottomContentPadding = 0.dp)
                        } else {
                            val one = mode.value == "header-one"
                            val online = mode.value == "header-photo"
                            val artwork = if (one) covers.take(1).toImmutableList() else covers
                            val albums = if (one) 1 else 38
                            GenrePage(
                                title = "Rock",
                                state = GenreDetailUiState(
                                    genre = rock, songs = songs, artwork = artwork,
                                    albums = (1..albums).map { GenreAlbum(it.toLong(), "Album $it", artist.name, null, null, 1) },
                                ),
                                currentSongId = null, isUnknownGenre = false,
                                onBack = {}, onRetry = {}, onPlay = {}, onShuffle = {}, onQuickFill = {}, onPlaySong = {}, onMoreSong = {},
                                onOpenArtist = { opened += it },
                                startOnAllSongs = true,
                                chosenIcon = rock.chosenIcon(customIcons),
                                featuredArtist = artist.takeUnless { one },
                                featuredPhoto = photo.takeIf { online },
                                description = description.takeIf { online },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun tag(name: String) = compose.onNodeWithTag(name, useUnmergedTree = true)
    private fun tags(name: String) = compose.onAllNodesWithTag(name, useUnmergedTree = true)
    private fun waitForArt(count: Int) = compose.waitUntil(8_000) {
        tags(GENRE_ART_READY_TAG).fetchSemanticsNodes().size >= count
    }

    private fun save(name: String) {
        val folder = File(System.getenv("B71_RENDERS") ?: "build/test-artifacts/B71/renders").apply { mkdirs() }
        val theme = if (dark.value) "dark" else "light"
        val bitmap = B42Renders.screen(compose)
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isEqualTo(1610)
        File(folder, "$language-$theme-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun headersFollowTheMockupAndShowCreditsOnlyWithADescription() {
        show()
        for (theme in listOf(true, false)) {
            compose.runOnIdle { dark.value = theme; mode.value = "header-photo" }
            waitForArt(2)
            tag(GENRE_ART_COLLAGE_TAG).assertDoesNotExist()
            val hero = tag("genre-hero").fetchSemanticsNode().boundsInRoot
            val title = tag("genre-title").fetchSemanticsNode().boundsInRoot
            assertThat(hero.height).isWithin(1f).of(660f)
            assertThat(title.left).isWithin(1f).of(40f)
            assertThat(title.top).isWithin(4f).of(584f)
            val hours = context.resources.getString(R.string.listening_duration_hours, 14L)
            compose.onNodeWithText(hours, substring = true).assertIsDisplayed()
            compose.onNodeWithText("Wikipedia · CC BY-SA 4.0").assertIsDisplayed()
            tag("genre-featured-artist").assertIsDisplayed()
            save("header-photo")
            val readMore = context.getString(R.string.music_info_read_more)
            val collapsed = tag("genre-description").fetchSemanticsNode().boundsInRoot.height
            compose.onNodeWithText(readMore).performClick()
            assertThat(tag("genre-description").fetchSemanticsNode().boundsInRoot.height).isGreaterThan(collapsed)
            compose.onNodeWithText(context.getString(R.string.music_info_read_less)).performClick()
            tag("genre-featured-artist").performClick()

            compose.runOnIdle { mode.value = "header-collage" }
            waitForArt(6)
            tags(GENRE_ART_COVER_TAG).assertCountEquals(6)
            tag("genre-description").assertDoesNotExist()
            assertThat(tag("genre-title").fetchSemanticsNode().boundsInRoot.top).isEqualTo(title.top)
            compose.onNodeWithText("Amber Signal").assertIsDisplayed()
            save("header-collage")

            compose.runOnIdle { mode.value = "header-one" }
            waitForArt(1)
            tag(GENRE_ART_COVER_TAG).assertIsDisplayed()
            assertThat(tag(GENRE_ART_COVER_TAG).fetchSemanticsNode().boundsInRoot).isEqualTo(hero)
            tag("genre-featured-artist").assertDoesNotExist()
            save("header-one")
        }
        assertThat(opened).containsExactly(42L, 42L)
    }

    @Test fun tilesKeepTheGridAndListDimensionsInBothThemes() {
        mode.value = "genres"
        show()
        for (theme in listOf(true, false)) {
            compose.runOnIdle { dark.value = theme; grid.value = true }
            waitForArt(9)
            tags(GENRE_ART_COLLAGE_TAG).assertCountEquals(3)
            val first = tags(GENRE_ART_COVER_TAG)[0].fetchSemanticsNode().boundsInRoot
            val second = tags(GENRE_ART_COVER_TAG)[1].fetchSemanticsNode().boundsInRoot
            assertThat(second.left - first.right).isWithin(1f).of(4f)
            save("grid")

            compose.runOnIdle { grid.value = false }
            waitForArt(3)
            tags(GENRE_ART_SINGLE_TAG).assertCountEquals(3)
            val row = tags(GENRE_ART_SINGLE_TAG)[0].fetchSemanticsNode().boundsInRoot
            val next = tags(GENRE_ART_SINGLE_TAG)[1].fetchSemanticsNode().boundsInRoot
            assertThat(row.width).isWithin(1f).of(136f)
            assertThat(row.height).isWithin(1f).of(136f)
            assertThat(next.top - row.top).isWithin(1f).of(168f)
            save("list")
        }
        assertThat(genreCoverRequestSize(76f, 76f, 2f)).isEqualTo(coil.size.Size(152, 152))
        assertThat(genreCoverRequestSize(120f, 164f, 3f)).isEqualTo(coil.size.Size(256, 256))
    }

    @Test fun chosenImageThenIconThenAutomaticCollageOrCoverThenLetter() {
        mode.value = "genres"
        show()
        for (gridView in listOf(true, false)) {
            val auto = if (gridView) GENRE_ART_COLLAGE_TAG else GENRE_ART_SINGLE_TAG
            compose.runOnIdle {
                grid.value = gridView
                icons.value = mapOf("Rock" to R.drawable.ic_music_placeholder)
                chosen.value = selected
            }
            waitForArt(if (gridView) 6 else 3)
            tags(auto).assertCountEquals(2)
            tag(GENRE_ART_ICON_TAG).assertDoesNotExist()
            tag(GENRE_ART_LETTER_TAG).assertIsDisplayed()

            compose.runOnIdle { chosen.value = null }
            tag(GENRE_ART_ICON_TAG).assertIsDisplayed()
            tags(auto).assertCountEquals(2)

            compose.runOnIdle { icons.value = emptyMap() }
            tags(auto).assertCountEquals(3)
            tag(GENRE_ART_LETTER_TAG).assertIsDisplayed()
        }

        compose.runOnIdle {
            mode.value = "header-photo"
            chosen.value = selected
            icons.value = mapOf("Rock" to R.drawable.ic_music_placeholder)
        }
        waitForArt(2)
        tag(GENRE_ART_ICON_TAG).assertDoesNotExist()
        tag(GENRE_ART_COLLAGE_TAG).assertDoesNotExist()
        val bitmap = B42Renders.screen(compose)
        assertThat(bitmap.getPixel(80, 250)).isEqualTo(0xffc62f78.toInt())

        compose.runOnIdle { chosen.value = null }
        tag(GENRE_ART_ICON_TAG).assertIsDisplayed()
        tag(GENRE_ART_COLLAGE_TAG).assertDoesNotExist()

        compose.runOnIdle { icons.value = emptyMap(); mode.value = "header-collage" }
        waitForArt(6)
        tag(GENRE_ART_COLLAGE_TAG).assertIsDisplayed()
    }
}

@Config(qualifiers = "es-w360dp-h805dp-xhdpi")
class B71GenreRendersSpanishTest : B71GenreRendersTest()

@Config(qualifiers = "sv-w360dp-h805dp-xhdpi")
class B71GenreRendersSwedishTest : B71GenreRendersTest()
