package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55AppAppearanceTest {
    @get:Rule val compose=createComposeRule()
    private fun show(dap:Boolean) {
        val base=AppearanceSettingsState.from(SettingsUiState())
        runCatching { base.javaClass.getDeclaredField("showDapLockScreen") }.getOrNull()?.let {
            it.isAccessible=true;it.setBoolean(base,dap)
        }
        val state=base
        val actions=mockk<AppearanceSettingsActions>(relaxed=true)
        compose.setContent { DapTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AppearanceSettingsScreen(state,actions) } } }
    }
    @Test fun `Appearance in app mode has no DAP lock screen switch`() {
        show(false)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onAllNodesWithText(context.getString(R.string.setcat_dap_lock_screen_title)).assertCountEquals(0)
    }
    @Test fun `Appearance in DAP keeps the lock screen switch`() {
        show(true)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.setcat_dap_lock_screen_title)).assertExists()
    }
}
