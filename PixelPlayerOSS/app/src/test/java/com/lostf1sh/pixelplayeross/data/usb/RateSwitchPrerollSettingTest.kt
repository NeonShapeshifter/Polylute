package com.lostf1sh.pixelplayeross.data.usb

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.DebugIntStore
import com.lostf1sh.pixelplayeross.data.service.player.RateSwitchPrerollSetting
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** The persisted debug setting behind `SET_RATE_SWITCH_PREROLL` (B12a-4): 0 by default, four values, inert in release. */
class RateSwitchPrerollSettingTest {

    private class FakeStore(private var value: Int? = null) : DebugIntStore {
        var reads = 0
        var writes = 0
        override fun read(): Int { reads++; return value ?: 0 }
        override fun write(value: Int) { writes++; this.value = value }
    }

    @Test
    fun `off by default with nothing stored, and reading never writes`() {
        val store = FakeStore()
        val setting = RateSwitchPrerollSetting(store, debugBuild = true)
        repeat(3) { assertThat(setting.ms()).isEqualTo(0) }
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `each allowed value is stored and a new process reads it back`() {
        for (ms in listOf(250, 500, 1000, 0)) {
            val store = FakeStore()
            assertThat(RateSwitchPrerollSetting(store, debugBuild = true).set(ms)).isTrue()
            assertThat(RateSwitchPrerollSetting(store, debugBuild = true).ms()).isEqualTo(ms)
        }
    }

    @Test
    fun `any other value is refused and changes nothing`() {
        val store = FakeStore(value = 500)
        val setting = RateSwitchPrerollSetting(store, debugBuild = true)
        for (bad in listOf(-1, 1, 100, 249, 750, 2000, 60_000, Int.MAX_VALUE)) {
            assertThat(setting.set(bad)).isFalse()
            assertThat(setting.ms()).isEqualTo(500)
        }
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `a stored value outside the list reads as 0, so a corrupt file is never a pre-roll`() {
        for (junk in listOf(-5, 7, 333, 999, 1001, 100_000)) {
            assertThat(RateSwitchPrerollSetting(FakeStore(junk), debugBuild = true).ms()).isEqualTo(0)
        }
    }

    @Test
    fun `the audio side reads a cached value, not the store`() {
        val store = FakeStore(value = 250)
        val setting = RateSwitchPrerollSetting(store, debugBuild = true)
        val readsAfterLoad = store.reads
        repeat(100) { setting.ms() }
        assertThat(store.reads).isEqualTo(readsAfterLoad)
    }

    @Test
    fun `release - a stored value is ignored, set does nothing and the store is never read`() {
        val store = FakeStore(value = 1000)
        val setting = RateSwitchPrerollSetting(store, debugBuild = false)
        assertThat(setting.ms()).isEqualTo(0)
        assertThat(setting.set(500)).isFalse()
        assertThat(setting.ms()).isEqualTo(0)
        assertThat(store.writes).isEqualTo(0)
        assertThat(store.reads).isEqualTo(0)
    }

    @Test
    fun `the preference store keeps one number under its own key`() {
        val values = mutableMapOf<String, Int>()
        val prefs: SharedPreferences = mockk(relaxed = true) {
            every { getInt(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { edit() } answers {
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
                every { editor.commit() } returns true
                editor
            }
        }
        RateSwitchPrerollSetting(RateSwitchPrerollSetting.PreferencesIntStore(prefs), debugBuild = true).set(500)
        assertThat(values).containsExactly(RateSwitchPrerollSetting.KEY, 500)
        assertThat(RateSwitchPrerollSetting(RateSwitchPrerollSetting.PreferencesIntStore(prefs), debugBuild = true).ms()).isEqualTo(500)
    }
}
