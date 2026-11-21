package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ReplayGainState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import java.io.File

/** Before recreates the old stale runtime value with the same unchanged DS row and synthetic data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w360dp-h805dp-night-xhdpi")
open class B62TrackDetailsGainRenderTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val ui = MutableStateFlow(TrackDetailsUiState(
        song = Song.emptySong().copy(id = "synthetic", title = "Synthetic signal", artist = "Test artist", duration = 180_000),
        output = OutputDetails(route = OutputRoute.BLUETOOTH, deviceName = "Test headphones"),
    ))

    @Test fun gainStatesUseTheExistingDetailsRow() {
        compose.setContent {
            val dark = context.resources.configuration.uiMode and 0x30 == 0x20
            DapTheme(baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        val state by ui.collectAsState()
                        TrackDetailsSheet(state, { 0L }, 180_000, {}, {})
                    }
                }
            }
        }
        val cases = listOf(
            Triple(ReplayGainState.REQUESTED_NOT_APPLIED, R.string.track_details_rg_requested_not_applied, "stale-before"),
            Triple(ReplayGainState.TRACK_APPLIED, R.string.track_details_rg_track_applied, "track-after"),
            Triple(ReplayGainState.ALBUM_APPLIED, R.string.track_details_rg_album_applied, "album-after"),
            Triple(ReplayGainState.UNITY_APPLIED, R.string.track_details_rg_unity, "unity-after"),
            Triple(ReplayGainState.PENDING, R.string.track_details_rg_pending, "pending-after"),
            Triple(ReplayGainState.WAITING_FOR_TRACK, R.string.track_details_rg_waiting, "other-track-after"),
        )
        for ((state, resource, name) in cases) {
            compose.runOnIdle { ui.value = ui.value.copy(replayGain = state) }
            compose.onNodeWithText(context.getString(resource), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val locale = context.resources.configuration.locales[0].language
            val dark = context.resources.configuration.uiMode and 0x30 == 0x20
            val out = File(System.getenv("B62_RENDERS") ?: "build/test-artifacts/B62/renders").apply { mkdirs() }
            File(out, "details-rg-$locale-${if (dark) "dark" else "light"}-$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}

@Config(qualifiers = "en-w360dp-h805dp-notnight-xhdpi")
class B62TrackDetailsGainRenderEnLightTest : B62TrackDetailsGainRenderTest()
@Config(qualifiers = "es-w360dp-h805dp-night-xhdpi")
class B62TrackDetailsGainRenderEsDarkTest : B62TrackDetailsGainRenderTest()
@Config(qualifiers = "es-w360dp-h805dp-notnight-xhdpi")
class B62TrackDetailsGainRenderEsLightTest : B62TrackDetailsGainRenderTest()
@Config(qualifiers = "sv-w360dp-h805dp-night-xhdpi")
class B62TrackDetailsGainRenderSvDarkTest : B62TrackDetailsGainRenderTest()
@Config(qualifiers = "sv-w360dp-h805dp-notnight-xhdpi")
class B62TrackDetailsGainRenderSvLightTest : B62TrackDetailsGainRenderTest()
