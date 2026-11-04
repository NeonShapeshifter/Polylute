package com.lostf1sh.pixelplayeross.presentation.settings

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.dap.mode.*
import java.io.File
import org.junit.Test

class B43DeviceVisibilityTest {
    @Test fun `every DAP level shows Device using the same source as screen timeout`() {
        for (effective in listOf(EffectiveProductMode.LAUNCHER, EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_ACTIVE)) {
            val product = ProductModeState(ProductModeRequest.APP, effective, effective == EffectiveProductMode.DEDICATED_ACTIVE,
                effective == EffectiveProductMode.DEDICATED_ACTIVE, isPolyluteHome = true)
            assertThat(SettingsSurfacePolicy.visibleCategories(product)).contains(SettingsCategory.DEVICE)
        }
    }
    @Test fun `App mode has no Device category or timeout shortcut`() {
        assertThat(SettingsSurfacePolicy.visibleCategories()).doesNotContain(SettingsCategory.DEVICE)
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsCategoryScreen.kt").readText()
        assertThat(source).contains("!com.lostf1sh.pixelplayeross.presentation.settings.ModeSurfacePolicy.showDeviceCategory(mode.product)")
        assertThat(source.substringBefore("val categoryTitle")).contains("navController.popBackStack()")
    }
}
