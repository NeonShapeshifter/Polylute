package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceLocateRequests
import com.lostf1sh.pixelplayeross.ui.ds.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B64SourceLocateEffectTest {
    @get:Rule val compose = createComposeRule()
    @Test fun waitsForRowsScrollsAndRaisesTheRequestedSongAndMissingSongGoesToTop() {
        var ready by mutableStateOf(false)
        lateinit var list: LazyListState
        compose.setContent {
            DapTheme(darkTheme = true) {
                list = rememberLazyListState()
                val located = rememberSourceLocate("source/page", list, ready) { id -> id.toIntOrNull()?.takeIf { it in 0..99 } ?: -1 }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(if (ready) 100 else 0) { id ->
                        Text("Track $id", modifier = Modifier.fillMaxWidth().height(64.dp).background(if(located == id.toString()) Dap.panelRaised else Dap.background)
                            .testTag(if(located == id.toString()) "located-$id" else "normal-$id"))
                    }
                }
            }
        }
        compose.runOnIdle { SourceLocateRequests.request("source/page", "80", null) }
        compose.waitForIdle()
        assertThat(SourceLocateRequests.request.value?.songId).isEqualTo("80")
        compose.runOnIdle { ready = true }
        compose.waitForIdle()
        compose.onNodeWithTag("located-80").assertIsDisplayed()
        assertThat(list.firstVisibleItemIndex).isEqualTo(80)
        compose.runOnIdle { SourceLocateRequests.request("source/page", "missing", null) }
        compose.waitForIdle()
        assertThat(list.firstVisibleItemIndex).isEqualTo(0)
        compose.onAllNodes(hasTestTag("located-80")).assertCountEquals(0)
        compose.runOnIdle { SourceLocateRequests.request("source/page", "80", null) }
        compose.waitForIdle()
        compose.onNodeWithTag("located-80").assertIsDisplayed()
    }
}
