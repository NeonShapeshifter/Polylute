package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.mode.*
import com.lostf1sh.pixelplayeross.presentation.navigation.*
import com.lostf1sh.pixelplayeross.presentation.settings.HomeModeState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h805dp-xhdpi")
class B55SettingsRootRenderTest {
    @get:Rule val compose=createComposeRule()
    private fun show(dap:Boolean) {
        val device=mockk<DeviceControlViewModel>(relaxed=true)
        every { device.uiState } returns MutableStateFlow(DeviceControlUiState(brightnessPercent=80,batteryPercent=75,batteryCharging=true))
        val mode=mockk<ProductModeViewModel>(relaxed=true)
        val product=ProductModeState(ProductModeRequest.APP,if(dap) EffectiveProductMode.LAUNCHER else EffectiveProductMode.APP,false,false,isPolyluteHome=dap)
        every { mode.state } returns MutableStateFlow(HomeModeState(product=product))
        val nav=mockk<NavController>(relaxed=true)
        compose.setContent { DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                SettingsScreen(nav,{},device,mode)
            }
        } }
        compose.waitForIdle()
        val bitmap=B42Renders.screen(compose)
        assertThat(bitmap.width).isEqualTo(720);assertThat(bitmap.height).isEqualTo(1610)
        val out=File(System.getenv("B55_SETTINGS_RENDERS") ?: "/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B55-gates/png-after/settings").apply { mkdirs() }
        File(out,if(dap) "dap.png" else "app.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @After fun cleanup() { unmockkAll() }
    @Test fun `app Settings root contains neither Battery nor Brightness`() {
        show(false)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onAllNodesWithText(context.getString(R.string.dap_brightness_title),useUnmergedTree=true).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.dap_battery_title),useUnmergedTree=true).assertCountEquals(0)
    }
    @Test fun `DAP Settings root keeps Battery and Brightness`() {
        show(true)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onAllNodesWithText(context.getString(R.string.dap_brightness_title),useUnmergedTree=true)[0].assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.dap_battery_title),useUnmergedTree=true)[0].assertIsDisplayed()
    }
}
