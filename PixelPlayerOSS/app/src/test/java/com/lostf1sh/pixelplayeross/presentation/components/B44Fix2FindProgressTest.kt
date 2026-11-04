package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B44Fix2FindProgressTest {
    @get:Rule val compose = createComposeRule()
    private val pages = flowOf(PagingData.from((0 until 660).map { InfoArtist("artist-$it", "Artist $it") }))
    private val selection = mutableStateOf(InfoSelection(0, 0, null, 0))
    private var findCalls = 0
    private val progress = mutableStateOf(InfoProgress(11640, 0, 0))

    private fun show() {
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    // Same 720x1610 pixel viewport and system-bar reservation as the HONOR fixture.
                    Box(Modifier.fillMaxSize().padding(top = 37.dp, bottom = 22.dp)) {
                        val artists = pages.collectAsLazyPagingItems()
                        MusicInfoDownloadsContent(artists, "", {}, InfoQueue(), progress.value,
                            InfoProgress(0, 0, 0), selection.value, false, false, { flowOf(emptyList()) },
                            { findCalls++ }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `never searched catalog has no progress bar or zero of total label`() {
        show()
        save("find-idle")
        compose.onNodeWithText("Items: 0 / 11640").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test fun `Find shows a bar only while pending and then a checked result`() {
        progress.value = InfoProgress(100, 20, 0, pending = 80)
        show()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        compose.runOnIdle { progress.value = InfoProgress(100, 100, 0, pending = 0) }
        compose.onNodeWithText("Checked today", substring = true).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    private fun save(name: String) {
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val phase = if (File(".").canonicalPath.contains("B44-fix2-before")) "before" else "after"
        val folder = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-fix2-evidence/$phase")
        folder.mkdirs()
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
