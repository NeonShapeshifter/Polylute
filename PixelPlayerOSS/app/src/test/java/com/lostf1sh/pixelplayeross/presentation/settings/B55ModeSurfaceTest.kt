package com.lostf1sh.pixelplayeross.presentation.settings

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.*
import org.junit.jupiter.api.Test

class B55ModeSurfaceTest {
    @Test fun `Battery Brightness and DAP lock screen entries are DAP only even for an owner outside the home role`():Unit {
        for(name in listOf("ROOT_BATTERY","ROOT_BRIGHTNESS","APPEARANCE_DAP_LOCK_SCREEN")) {
            val entry=ModeSurfacePolicy.Entry.valueOf(name)
            assertThat(entry.scope).isEqualTo(ModeSurfacePolicy.Scope.DAP_ONLY)
            for(mode in EffectiveProductMode.entries) for(owner in listOf(false,true)) {
                val state=ProductModeState(ProductModeRequest.APP,mode,owner,owner)
                assertThat(ModeSurfacePolicy.isVisible(entry,state)).isEqualTo(state.level != null)
            }
        }
    }
    @Test fun `the real Settings hosts derive all three new entries from ModeSurfacePolicy`():Unit {
        val root=java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens")
        val settings=java.io.File(root,"SettingsScreen.kt").readText()
        assertThat(settings).contains("ModeSurfacePolicy.Entry.ROOT_BATTERY")
        assertThat(settings).contains("ModeSurfacePolicy.Entry.ROOT_BRIGHTNESS")
        assertThat(java.io.File(root,"SettingsCategoryScreen.kt").readText()).contains("ModeSurfacePolicy.Entry.APPEARANCE_DAP_LOCK_SCREEN")
    }

}
