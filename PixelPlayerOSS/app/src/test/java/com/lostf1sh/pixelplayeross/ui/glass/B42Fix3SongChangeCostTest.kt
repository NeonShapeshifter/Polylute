package com.lostf1sh.pixelplayeross.ui.glass

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.presentation.components.CompositionCounter
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.theme.AppPainting
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import com.lostf1sh.pixelplayeross.ui.theme.rememberAppPainting
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B42-fix3, item 5 (host): **what a song's colours cost Home and the full player**, as the number of composable functions that run when the song changes (its
 * cover, its accent and, in the cover modes, its backdrop). The same test runs in the tree before the batch (through the shim that carries the old root's logic, in
 * `before/`) and after it; the numbers go to the result.
 *
 * Nothing about the song itself changes here (the same Home, the same player), so every run counted is the colours': a Home with six tiles, a stats card, the
 * mini player and the bottom bar; the full player with its cover, lyrics, progress and controls. The Library's count (with 200 rows) is in
 * [B42Fix3SongChangeRecompositionTest]. After the batch only what wears the accent runs (the player's dots and marks, the playing tile); before it, everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix3SongChangeCostTest {
    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    @Before
    fun install() = counter.install()

    @After
    fun uninstall() = counter.uninstall()

    private fun scheme(primary: Color) = ColorSchemePair(light = lightColorScheme(primary = primary), dark = darkColorScheme(primary = primary))

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val coverScheme = MutableStateFlow<ColorSchemePair?>(scheme(Color(0xFFD08020)))
    private val coverUri = MutableStateFlow<String?>("content://art/1")

    private val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f ->
        f.decoder = { uri -> Triple(96, 96, IntArray(96 * 96) { i -> rgb((i % 96 * 2 + uri.hashCode() * 40) and 255, (i / 96 * 2 + uri.hashCode() * 70) and 255, (uri.hashCode() * 90) and 255) }) }
    }

    private var painting: AppPainting? = null

    private fun run(source: String, label: String, page: @androidx.compose.runtime.Composable (B41Renders.Cover) -> Unit): Int {
        val cover = B42Fix3Covers.saturated()
        compose.setContent {
            val p = rememberAppPainting(
                appColorSource = source, appThemeMode = "dark", storedBaseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage, systemDark = true,
                albumArtColorScheme = coverScheme, themedCoverUri = coverUri, fields = fields,
            )
            painting = p
            val painted = p.painted
            PixelPlayerTheme(
                darkTheme = painted.useDark, accent = DapAccent.Sage, baseTheme = painted.baseTheme,
                palette = painted.look?.palette ?: painted.baseTheme.palette, accentColorOverride = painted.look?.accent,
            ) {
                CompositionLocalProvider(LocalCoverGlass provides p.scene.takeIf { painted.glassShown }) {
                    Surface(Modifier, color = Color.Transparent) { page(cover) }
                }
            }
        }
        compose.waitForIdle()
        fun settle(previous: Any?) {
            if (AppColorSource.takesColoursFromCover(source) && painting!!.scene.crossfade.current != null || source == AppColorSource.COVER_GLASS || source == AppColorSource.ALBUM_ART) {
                runCatching {
                    compose.waitUntil(15_000) {
                        val x = painting!!.scene.crossfade
                        x.current != null && x.current !== previous && x.previous == null && x.blend == 1f
                    }
                }
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
        }
        settle(null)
        var total = 0
        repeat(4) { n ->
            val before = counter.counts()
            val fieldBefore = painting!!.scene.crossfade.current
            coverUri.value = "content://art/${n + 2}"
            coverScheme.value = scheme(listOf(Color(0xFF2090D0), Color(0xFF40B060), Color(0xFFD04070), Color(0xFFB0A020))[n])
            settle(fieldBefore)
            // the app's own composables (the platform's and the library's helpers, `LaunchedEffect` and the like, are not what a screen is made of)
            val now = counter.counts()
            val ran = now.mapNotNull { (name, count) ->
                val delta = count - (before[name] ?: 0)
                if (delta > 0 && name.startsWith("com.lostf1sh.pixelplayeross")) name.substringAfterLast('.') to delta else null
            }.toMap()
            total += ran.values.sum()
            if (n == 0) println("B42-fix3 detail | $label: ${ran.entries.sortedByDescending { it.value }.take(30).joinToString { "${it.key}=${it.value}" }}")
        }
        val perChange = total / 4
        println("B42-fix3 item 5 | $label: $perChange of the app's composables run per song change (mean of 4)")
        return perChange
    }

    @Test
    fun home_in_cover_glass() = check("Home, Cover glass", AppColorSource.COVER_GLASS) { B42Fix3Pages.Home(it) }

    @Test
    fun home_in_from_album_art() = check("Home, From album art", AppColorSource.ALBUM_ART) { B42Fix3Pages.Home(it) }

    @Test
    fun player_in_cover_glass() = check("Player, Cover glass", AppColorSource.COVER_GLASS) { B42Fix3Pages.Player(it) }

    @Test
    fun player_in_from_album_art() = check("Player, From album art", AppColorSource.ALBUM_ART) { B42Fix3Pages.Player(it) }

    private fun check(label: String, source: String, page: @androidx.compose.runtime.Composable (B41Renders.Cover) -> Unit) {
        val perChange = run(source, label, page)
        // what the colours cost is the composables that wear the accent (a playing tile, the player's marks), never the page: a budget the old root was far over
        assertThat(perChange).isAtMost(if (label.startsWith("Home")) HOME_BUDGET else PLAYER_BUDGET)
    }

    private companion object {
        const val HOME_BUDGET = 30
        const val PLAYER_BUDGET = 90
    }
}
