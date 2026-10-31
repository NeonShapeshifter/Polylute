package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B44Fix1ShuffleSettingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `Behaviour offers the shared song switch off and dispatches its action`() {
        val calls = mutableListOf<String>()
        val actions = Proxy.newProxyInstance(BehaviorSettingsActions::class.java.classLoader,
            arrayOf(BehaviorSettingsActions::class.java)) { _, method, args ->
            calls += method.name + ":" + args?.joinToString()
            null
        } as BehaviorSettingsActions
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BehaviorSettingsScreen(BehaviorSettingsState.from(SettingsUiState()), actions)
            }
        }
        compose.onNodeWithText("Repeat shared songs in shuffle").assertExists()
        compose.onAllNodes(isToggleable())[1].performScrollTo().assertIsOff().performClick()
        assertThat(calls).contains("onRepeatSharedSongsInShuffleChange:true")
    }
}
