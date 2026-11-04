package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixHeader
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixSection
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B57-fix1 item 1: on Home's Daily Mix card the two arrows opened the same page. The header's control is now **Shuffle** (the mix as shown, shuffled, with the source
 * `HOME_MIX / daily_mix`, as every shuffle button does); the title is text; "See all" is the one control that opens the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B57Fix1DailyMixTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun song(n: Int) = Song(
        id = "$n", title = "Track $n", artist = "Gemini Sound", artistId = 1, album = "Noche", albumId = 1, albumArtist = "Gemini Sound",
        path = "/m/$n.flac", contentUriString = "content://$n", albumArtUriString = null, duration = 200_000, genre = "Rock", trackNumber = n,
        year = 2019, mimeType = null, bitrate = null, sampleRate = null,
    )

    private val mix = (1..8).map(::song)

    private fun viewModel(): PlayerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { it.selectedSongForInfo } returns MutableStateFlow(null)
        every { it.navBarCompactMode } returns MutableStateFlow(false)
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState())
    }

    private fun card(vm: PlayerViewModel, opened: () -> Unit) = compose.setContent {
        DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Box(Modifier.fillMaxSize().background(Dap.background)) { DailyMixSection(mix.toImmutableList(), vm, onClickOpen = opened) }
        }
    }

    @Test fun the_shuffle_button_shuffles_the_whole_mix_as_shown_with_its_own_source() {
        val vm = viewModel()
        var opened = 0
        card(vm) { opened++ }

        compose.onNodeWithContentDescription(context.getString(R.string.cd_shuffle_play)).performClick()

        verify(exactly = 1) {
            vm.playSongsShuffled(
                songsToPlay = mix,
                queueName = context.getString(R.string.presentation_batch_g_daily_mix_queue_name),
                playlistId = any(),
                startAtZero = true,
                source = ListenSource(ListenSourceKind.HOME_MIX, "daily_mix"),
                timeline = any(),
            )
        }
        assertEquals("shuffling does not open the page", 0, opened)
    }

    @Test fun see_all_opens_the_page_and_the_title_does_not() {
        val vm = viewModel()
        var opened = 0
        card(vm) { opened++ }

        compose.onNodeWithText(context.getString(R.string.home_daily_mix_title)).performClick()
        assertEquals("the title is text now: one control, one action", 0, opened)

        compose.onNodeWithText(context.getString(R.string.presentation_batch_g_daily_mix_see_all, context.getString(R.string.collection_name_daily_mix))).performClick()
        assertEquals(1, opened)
    }

    @Test fun the_header_has_exactly_one_control_and_the_title_has_no_click_action() {
        compose.setContent { DapTheme { DailyMixHeader(thumbnails = persistentListOf(), onShuffle = {}) } }
        compose.onAllNodesWithContentDescription(context.getString(R.string.cd_shuffle_play)).assertCountEquals(1)
        val title = compose.onNodeWithText(context.getString(R.string.home_daily_mix_title)).fetchSemanticsNode()
        assertFalse("no click on the title itself", title.config.contains(SemanticsActions.OnClick))
        // and no ancestor of the title is clickable either: the old header was a clickable row with a chevron
        generateSequence(title.parent) { it.parent }.forEach { assertFalse("no clickable ancestor of the title", it.config.contains(SemanticsActions.OnClick)) }
    }

    private fun shot(dark: Boolean) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background)) { DailyMixHeader(thumbnails = persistentListOf(), onShuffle = {}) }
            }
        }
        B57Renders.save(compose, "daily-mix-header-${if (dark) "dark" else "light"}")
    }

    @Config(sdk = [35], qualifiers = "w360dp-h200dp-xhdpi") @Test fun render_light() = shot(false)
    @Config(sdk = [35], qualifiers = "w360dp-h200dp-xhdpi") @Test fun render_dark() = shot(true)
}
