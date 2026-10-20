package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DisplayedItemReconcile
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B29-L2: an automatic transition while the UI is capped or stopped, and the screen coming back.
 *
 * The mini player draws what the view model's state says, through `collectAsStateWithLifecycle` under
 * [FreezeWhileCovered]. Two things can leave it on the old song: the state was never written (the controller's listener
 * did not hear of the transition), or the screen did not read it when it came back. The second is proved harmless here
 * (the cap lifts and the collector reads the latest value); the first is what the reconcile on resume closes, with the
 * engine as the authority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class StaleMiniPlayerAfterAutoTransitionTest {

    @get:Rule
    val compose = createComposeRule()

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** What the view model shows (`stablePlayerState.currentSong.id` stands for the whole state). */
    private val shown = MutableStateFlow("STORM I")
    private var engine = "STORM I"
    private var controller = "STORM I"

    @Composable
    private fun MiniPlayer() {
        val title by shown.collectAsStateWithLifecycle()
        Text(title)
    }

    /** The reconcile of `PlayerViewModel.onMainActivityResume`, with the decision the real one makes. */
    private fun onResume() {
        when (DisplayedItemReconcile.followFrom(shown.value, controller, engine)) {
            DisplayedItemReconcile.Source.NONE -> Unit
            DisplayedItemReconcile.Source.CONTROLLER -> shown.value = controller
            DisplayedItemReconcile.Source.ENGINE -> shown.value = engine
        }
    }

    private fun setScreenUp(screen: Host, frozen: Boolean) = compose.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides screen) {
            Box(Modifier.fillMaxSize()) {
                FreezeWhileCovered(frozen = frozen, hidden = false, modifier = Modifier.fillMaxSize()) { MiniPlayer() }
            }
        }
    }

    @Test
    fun `the listener heard it while the app was stopped, and the screen reads the latest song when it comes back`() {
        val screen = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        setScreenUp(screen, frozen = false)
        compose.onNodeWithText("STORM I").assertExists()

        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.CREATED } // the screen went off
        compose.waitForIdle()
        // the automatic transition: the engine, the controller and the state all move
        engine = "STORM II"; controller = "STORM II"; shown.value = "STORM II"

        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.RESUMED } // and came back
        compose.runOnUiThread { onResume() }
        compose.waitForIdle()
        compose.onNodeWithText("STORM II").assertExists()
    }

    @Test
    fun `the listener never heard it, and the engine's song is what the screen shows when it comes back`() {
        val screen = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        setScreenUp(screen, frozen = false)

        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        // the engine moved on; neither the controller nor the state were told (the 20:40:31 shape)
        engine = "STORM II"

        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        // without the reconcile the old song stays: this is the symptom
        compose.onNodeWithText("STORM I").assertExists()

        compose.runOnUiThread { onResume() }
        compose.waitForIdle()
        compose.onNodeWithText("STORM II").assertExists()
        assertThat(shown.value).isEqualTo("STORM II")
    }

    @Test
    fun `the song changes under the lock surface, and the app shows the new one the moment the surface lets it hear`() {
        val screen = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val cover = CoverSignal()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                FreezeWhileCovered(frozen = false, hidden = false, cover = cover, modifier = Modifier.fillMaxSize()) { MiniPlayer() }
            }
        }
        compose.runOnUiThread { cover.covered = true }
        compose.waitForIdle()
        engine = "STORM II"; controller = "STORM II"; shown.value = "STORM II"
        compose.runOnUiThread { cover.covered = false }
        compose.waitForIdle()
        compose.onNodeWithText("STORM II").assertExists()
    }
}
