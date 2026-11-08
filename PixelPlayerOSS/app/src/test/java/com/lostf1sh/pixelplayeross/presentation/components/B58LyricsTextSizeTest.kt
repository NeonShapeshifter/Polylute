package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.center
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.After
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.dataStore
import com.lostf1sh.pixelplayeross.presentation.B58Renders
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B58 4 (#78): the lyrics text size. The real lyrics sheet at the HONOR's size over short synced lines (so that nothing wraps at the biggest size and a line's height is its text
 * size): each of the four sizes reaches the text, immersive mode shows the chosen size plus a quarter, and every one of them is a PNG, normal and immersive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B58LyricsTextSizeTest {
    @get:Rule
    val compose = createComposeRule()

    private val song = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Artist One", artistId = 7L, album = "Album One", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 240_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )
    private val lines = (0 until 40).map { SyncedLine(time = it * 5_000, line = "Line $it of the song") }
    private val position = MutableStateFlow(102_000L)
    private val playerState = MutableStateFlow(StablePlayerState())
    private val chosen = ArrayList<String>()

    @After fun forgetTheChoice() { store(null) }

    private fun store(key: String?) = runBlocking {
        ApplicationProvider.getApplicationContext<android.content.Context>().dataStore.edit {
            val k = stringPreferencesKey("lyrics_text_size")
            if (key == null) it.remove(k) else it[k] = key
        }
    }

    private fun show(size: String?) {
        store(size)
        playerState.value = StablePlayerState(currentSong = song, isPlaying = true, totalDuration = 240_000L, lyrics = Lyrics(synced = lines), lyricsResolveCompleted = true)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) {
                    LyricsSheet(
                        stablePlayerStateFlow = playerState,
                        playbackPositionFlow = position,
                        lyricsSearchUiState = LyricsSearchUiState.Idle,
                        resetLyricsForCurrentSong = {},
                        onSearchLyrics = {},
                        onPickResult = {},
                        onManualSearch = { _, _ -> },
                        onImportLyrics = {},
                        onDismissLyricsSearch = {},
                        lyricsSyncOffset = 0,
                        onLyricsSyncOffsetChange = {},
                        lyricsTextStyle = MaterialTheme.typography.titleLarge,
                        colorScheme = darkColorScheme(),
                        onLyricsAlignmentChange = {},
                        onLyricsTextSizeChange = { chosen += it },
                        onShowLyricsTranslationChange = {},
                        onShowLyricsRomanizationChange = {},
                        onSeekTo = {},
                        onPlayPause = {},
                        immersiveLyricsEnabled = true,
                        immersiveLyricsTimeout = 4_000L,
                        isImmersiveTemporarilyDisabled = false,
                        onSaveLyricsToFile = { _, _, _ -> },
                        externalLyricsEnabled = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun settle(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun lineHeight() = compose.onNodeWithTag("synced_line_100000").fetchSemanticsNode().boundsInRoot.height

    /** The height of the current line at [size] on the page and then in immersive mode (the controls fold after four quiet seconds). One composition: the size is a preference the sheet reads live. */
    private fun measure(size: String, name: String): Pair<Float, Float> {
        store(size)
        settle(300)
        // a tap anywhere brings the controls back and starts the countdown again
        compose.onRoot().performTouchInput { click(center) }
        settle(500)
        val page = lineHeight()
        B58Renders.save(compose, "lyrics-size-$name-page")
        settle(5_000)
        settle(2_000)
        val immersive = lineHeight()
        B58Renders.save(compose, "lyrics-size-$name-immersive")
        return page to immersive
    }

    @Test
    fun `each size reaches the text and immersive is the chosen size plus a quarter`() {
        show(null)
        val results = LyricsTextSize.entries.associate { size -> size to measure(size.key, size.key) }
        // A line's box is its text (which scales with the size) plus a fixed padding (which does not): height = pad + text * factor. Fit it on the two ends, check the two between.
        val small = results.getValue(LyricsTextSize.SMALL).first
        val xl = results.getValue(LyricsTextSize.EXTRA_LARGE).first
        val perUnit = (xl - small) / (LyricsTextSize.EXTRA_LARGE.factor - LyricsTextSize.SMALL.factor)
        val pad = small - perUnit * LyricsTextSize.SMALL.factor
        // the text at the page's own size is a line of the title style (2 lines' worth is far more): a sane number, so the fit is about the text and not noise
        assertThat(perUnit).isGreaterThan(40f)
        assertThat(perUnit).isLessThan(90f)
        LyricsTextSize.entries.forEach { size ->
            val (page, immersive) = results.getValue(size)
            assertThat(page).isWithin(3f).of(pad + perUnit * size.factor)
            // immersive: the chosen size grown by a quarter - not a fixed 1.4 of one style
            assertThat(immersive).isWithin(8f).of(pad + perUnit * size.factor * LyricsTextSize.IMMERSIVE_GROWTH)
        }
        // four distinct sizes, in order, and immersive is bigger than the page at every size
        val pages = LyricsTextSize.entries.map { results.getValue(it).first }
        assertThat(pages).isInOrder()
        assertThat(pages.toSet()).hasSize(4)
        LyricsTextSize.entries.forEach { assertThat(results.getValue(it).second).isGreaterThan(results.getValue(it).first) }
    }

    @Test
    fun `no choice yet is the size the page always had`() {
        show(null)
        settle(1_000)
        val unset = lineHeight()
        store("medium")
        settle(500)
        assertThat(lineHeight()).isWithin(0.5f).of(unset)
    }

    @Test
    fun `the choice is the app's and is read from the preference every install has`() {
        // survives a restart: the value is in the DataStore under one key, read again by a new composition
        show("large")
        settle(1_000)
        val first = lineHeight()
        assertThat(first).isGreaterThan(0f)
        val repoSource = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/LyricsSheet.kt").readText()
        assertThat(repoSource).contains("stringPreferencesKey(\"lyrics_text_size\")")
        assertThat(repoSource).doesNotContain("1.4f")
    }

    @Test
    fun `the factors are four, grow with the name and the immersive growth is one constant`() {
        assertThat(LyricsTextSize.entries.map { it.key }).containsExactly("small", "medium", "large", "xlarge").inOrder()
        assertThat(LyricsTextSize.entries.map { it.factor }).isInOrder()
        assertThat(LyricsTextSize.MEDIUM.factor).isEqualTo(1f)
        assertThat(LyricsTextSize.IMMERSIVE_GROWTH).isEqualTo(1.25f)
        LyricsTextSize.entries.forEach { assertThat(it.scale(immersive = true)).isWithin(1e-6f).of(it.factor * 1.25f) }
        assertThat(LyricsTextSize.fromKey("nonsense")).isEqualTo(LyricsTextSize.MEDIUM)
        assertThat(LyricsTextSize.fromKey(null)).isEqualTo(LyricsTextSize.MEDIUM)
    }
}
