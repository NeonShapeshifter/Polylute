package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.*
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B43SquareCoverTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `the cover shrinks to a square when lyrics leave less height than width`() {
        val song = Song.emptySong().copy(id = "1", title = "Cover", albumId = 1, albumArtUriString = "file:///missing-cover.png")
        val cover = PlayerCoverState(song, persistentListOf(song), "Album", 0, CarouselStyle.NO_PEEK, AlbumArtQuality.MEDIUM, null, { true }, { true })
        compose.setContent {
            CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) {
                Box(Modifier.width(320.dp).height(180.dp)) {
                    PlayerCoverSurface(cover, PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, false, { 1f }), NoActions())
                }
            }
        }
        compose.waitForIdle()
        val art = compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().first { it.boundsInRoot.width > 100 && it.boundsInRoot.height > 100 }.boundsInRoot
        assertThat(kotlin.math.abs(art.width - art.height)).isAtMost(1f)
        assertThat(art.height / compose.density.density).isAtMost(180f)
    }
}
