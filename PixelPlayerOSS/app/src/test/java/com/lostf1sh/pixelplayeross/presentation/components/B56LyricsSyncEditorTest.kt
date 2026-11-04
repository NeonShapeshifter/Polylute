package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.lyrics.LrcSyncState
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B56 phase 3.2 (#31): the sync editor's screen: its controls call the rules of [LrcSyncState], leaving with changes asks, and it is drawn from `ui/ds`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B56LyricsSyncEditorTest {
    @get:Rule val compose = createComposeRule()

    private val texts = listOf("Hola", "Adiós", "Hasta luego", "Nos vemos pronto", "Cuídate mucho")
    private var state by mutableStateOf(LrcSyncState.forPlain(texts))
    private var position = 0L
    private var playing = true
    private val calls = mutableListOf<String>()
    private val out: File
        get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private fun setEditor(dark: Boolean = true) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                // the lyrics page is drawn over the app's background (or the cover glass): the render gives the editor the theme's own
                Box(Modifier.fillMaxSize().background(com.lostf1sh.pixelplayeross.ui.ds.Dap.background)) {
                    LyricsSyncEditor(
                        state = state,
                        onStateChange = { state = it },
                        positionProvider = { position },
                        isPlaying = playing,
                        onPlayPause = { calls += "playPause"; playing = !playing },
                        onSeekTo = { calls += "seek:$it" },
                        onSave = { calls += "save" },
                        onClose = { calls += "close" },
                        previewContent = { Text("the normal synced view") },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun stamp(at: Long) {
        position = at
        compose.onNodeWithTag(LyricsSyncTags.Stamp).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the big button stamps the playing position on the next line and moves on`() {
        setEditor()
        stamp(1_240)
        stamp(4_800)

        assertThat(state.times).containsExactly(1_240, 4_800, null, null, null).inOrder()
        assertThat(state.cursor).isEqualTo(2)
    }

    @Test
    fun `undo last takes the last stamp back`() {
        setEditor()
        stamp(1_000)
        stamp(2_000)

        compose.onNodeWithTag(LyricsSyncTags.Undo).performClick()
        compose.waitForIdle()

        assertThat(state.times).containsExactly(1_000, null, null, null, null).inOrder()
    }

    @Test
    fun `a selected stamped line shows the two nudges and each moves it by a tenth of a second`() {
        setEditor()
        stamp(1_000)
        compose.onAllNodesWithTag(LyricsSyncTags.NudgeLater).assertCountEquals(1)      // the stamped line is selected after the stamp

        compose.onNodeWithTag(LyricsSyncTags.NudgeLater).performClick()
        compose.waitForIdle()
        assertThat(state.times[0]).isEqualTo(1_100)
        compose.onNodeWithTag(LyricsSyncTags.NudgeEarlier).performClick()
        compose.onNodeWithTag(LyricsSyncTags.NudgeEarlier).performClick()
        compose.waitForIdle()
        assertThat(state.times[0]).isEqualTo(900)
    }

    @Test
    fun `save waits for every line and for something to save`() {
        setEditor()
        compose.onNodeWithTag(LyricsSyncTags.Save).assertIsNotEnabled()
        repeat(texts.size) { stamp(1_000L * (it + 1)) }
        compose.onNodeWithTag(LyricsSyncTags.Save).assertIsEnabled().performClick()

        assertThat(calls).contains("save")
    }

    @Test
    fun `leaving a clean editor closes it at once`() {
        setEditor()

        compose.onNodeWithTag(LyricsSyncTags.Close).performClick()

        assertThat(calls).containsExactly("close")
    }

    @Test
    fun `leaving an editor with changes asks first, keep editing goes on and discard leaves`() {
        setEditor()
        stamp(1_000)

        compose.onNodeWithTag(LyricsSyncTags.Close).performClick()
        compose.waitForIdle()
        assertThat(calls).isEmpty()
        compose.onAllNodesWithTag(LyricsSyncTags.Discard).assertCountEquals(1)

        compose.onNodeWithTag(LyricsSyncTags.KeepEditing).performClick()
        compose.waitForIdle()
        assertThat(calls).isEmpty()
        compose.onAllNodesWithTag(LyricsSyncTags.Discard).assertCountEquals(0)

        compose.onNodeWithTag(LyricsSyncTags.Close).performClick()
        compose.onNodeWithTag(LyricsSyncTags.Discard).performClick()
        assertThat(calls).containsExactly("close")
    }

    @Test
    fun `the preview shows the normal synced view once every line is stamped`() {
        setEditor()
        compose.onNodeWithTag(LyricsSyncTags.Preview).assertIsNotEnabled()
        repeat(texts.size) { stamp(1_000L * (it + 1)) }

        compose.onNodeWithTag(LyricsSyncTags.Preview).assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("the normal synced view").assertExists()
    }

    private fun render(name: String, dark: Boolean, stamped: Int, asking: Boolean = false) {
        setEditor(dark)
        repeat(stamped) { stamp(1_000L * (it + 1) + 240) }
        if (asking) {
            compose.onNodeWithTag(LyricsSyncTags.Close).performClick()
            compose.waitForIdle()
        }
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `render the editor in the dark theme`() = render("lyrics-sync-editor-dark", dark = true, stamped = 3)

    @Test fun `render the editor in the light theme`() = render("lyrics-sync-editor-light", dark = false, stamped = 3)

    @Test fun `render the leave without saving question`() = render("lyrics-sync-editor-leave-dark", dark = true, stamped = 2, asking = true)
}
