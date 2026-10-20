package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapActionRow
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveAction
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveRow
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapValue
import com.lostf1sh.pixelplayeross.ui.ds.DapValueRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q5: the song options sheet said "Eliminar" twice, in one row: the row's label and the destructive button inside it were both
 * `delete_action`. It is one row now, "Eliminar del dispositivo", with its description; the dialog that asks "¿Eliminar canción?" is
 * the song sheet's own confirmation and is unchanged.
 *
 * The sheet itself needs Hilt (its ViewModel), so what is drawn here is its **File group**, the part that changed: the rows it holds
 * are the real ones ([DapValueRow], [DapNavRow], [DapDestructiveRow]) and the "before" is that group with the row it used to hold,
 * drawn from the code that was there ([DapActionRow] with a [DapDestructiveAction] in it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B32SongSheetRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val language get() = context.resources.configuration.locales[0].language

    private var showingBefore by mutableStateOf(false)
    private var deletes = 0

    @Composable
    private fun FileGroup(deleteRow: @Composable () -> Unit) {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.surface).padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.SectionGap)) {
                DapGroup(title = stringResource(R.string.more_group_file)) {
                    DapValueRow(label = "/storage/emulated/0/Music") { DapValue("Hip Hop/Rolling Papers/01 On My Level.flac") }
                    DapNavRow(label = stringResource(R.string.edit_song_toolbar_title), onClick = {})
                    deleteRow()
                }
            }
        }
    }

    private fun show() = compose.setContent {
        if (showingBefore) {
            FileGroup {
                DapActionRow(
                    label = stringResource(R.string.delete_action),
                    description = stringResource(R.string.more_delete_supporting),
                    divider = false,
                ) { DapDestructiveAction(label = stringResource(R.string.delete_action), onClick = { deletes++ }) }
            }
        } else {
            FileGroup {
                DapDestructiveRow(
                    label = stringResource(R.string.more_delete_from_device),
                    description = stringResource(R.string.more_delete_supporting),
                    divider = false,
                    onClick = { deletes++ },
                )
            }
        }
    }

    @Test
    fun `the before said the one word twice and the after says it once, as one row`() {
        show()
        val word = s(R.string.delete_action)
        compose.runOnUiThread { showingBefore = true }
        compose.waitForIdle()
        compose.onAllNodesWithText(word).assertCountEquals(2)
        B32Renders.saveAs(compose, "q5-song-sheet-file-before-$language")

        compose.runOnUiThread { showingBefore = false }
        compose.waitForIdle()
        compose.onAllNodesWithText(word).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.more_delete_from_device)).assertCountEquals(1)
        B32Renders.saveAs(compose, "q5-song-sheet-file-after-$language")
    }

    @Test
    fun `the row is one tap target and runs the delete once`() {
        show()
        compose.onNodeWithText(s(R.string.more_delete_from_device)).performClick()
        compose.onNodeWithText(s(R.string.more_delete_supporting)).performClick()
        assertEquals("a tap on the label or on the description is the same one action", 2, deletes)
    }

    @Test
    fun `the label and the description say different things in every language`() {
        assertEquals(false, s(R.string.more_delete_from_device) == s(R.string.delete_action))
        assertEquals(false, s(R.string.more_delete_from_device) == s(R.string.more_delete_supporting))
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B32SongSheetRenderSpanishTest : B32SongSheetRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class B32SongSheetRenderSwedishTest : B32SongSheetRenderTest()
