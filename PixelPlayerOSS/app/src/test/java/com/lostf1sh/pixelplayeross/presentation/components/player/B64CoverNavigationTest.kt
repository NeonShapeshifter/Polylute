package com.lostf1sh.pixelplayeross.presentation.components.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.*
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B64CoverNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun coverStillOpensTheSongsAlbumWhileTheQueueNamesACollection() {
        val song = Song.emptySong().copy(id = "42", title = "Synthetic track", album = "Synthetic album", albumId = 9)
        val calls = mutableListOf<Long>()
        val actions = object : FullPlayerActions by NoActions() { override fun onOpenAlbum(albumId: Long) { calls += albumId } }
        compose.setContent {
            CompositionLocalProvider(LocalMaterialTheme provides androidx.compose.material3.darkColorScheme()) {
                DapTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize()) {
                        PlayerCoverSurface(PlayerCoverState(song, persistentListOf(song), "Forgotten favorites", 0, "Flat", AlbumArtQuality.MEDIUM, null, { false }, { false }),
                            PlayerSheetEnv(FullPlayerLoadingTweaks(), false, PlayerSheetState.EXPANDED, true, { 1f }), actions)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        assertThat(calls).containsExactly(9L)
    }
}
