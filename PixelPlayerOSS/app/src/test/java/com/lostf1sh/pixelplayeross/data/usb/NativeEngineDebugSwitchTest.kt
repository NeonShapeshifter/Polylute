package com.lostf1sh.pixelplayeross.data.usb

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore
import com.lostf1sh.pixelplayeross.data.service.player.NativeEngineDebugSwitch
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The persisted debug switch behind `SET_FORCE_NATIVE_ENGINE` (B12a plan B): off by default, persisted when
 * turned on, inert outside a debug build — and independent of the pre-B12a switch it replaces.
 */
class NativeEngineDebugSwitchTest {

    private class FakeStore(private var value: Boolean? = null) : DebugFlagStore {
        var reads = 0
        var writes = 0
        override fun read(): Boolean {
            reads++
            return value ?: false
        }

        override fun write(value: Boolean) {
            writes++
            this.value = value
        }
    }

    /** A preference file as a Map: what a phone that ran the pre-B12a build holds in it. */
    private class PrefsFile(initial: Map<String, Boolean> = emptyMap()) {
        val values = initial.toMutableMap()
        val readKeys = mutableListOf<String>()
        val writtenKeys = mutableListOf<String>()

        val prefs: SharedPreferences = mockk(relaxed = true) {
            every { getBoolean(any(), any()) } answers {
                readKeys += firstArg<String>()
                values[firstArg()] ?: secondArg()
            }
            every { edit() } answers {
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putBoolean(any(), any()) } answers {
                    writtenKeys += firstArg<String>()
                    values[firstArg()] = secondArg()
                    editor
                }
                every { editor.commit() } returns true
                editor
            }
        }
    }

    // ── The switch itself ───────────────────────────────────────────────

    @Test
    fun `off by default with nothing stored`() {
        assertThat(NativeEngineDebugSwitch(FakeStore(), debugBuild = true).isForced()).isFalse()
    }

    @Test
    fun `loading and reading while off never writes`() {
        val store = FakeStore()
        val switch = NativeEngineDebugSwitch(store, debugBuild = true)
        repeat(3) { switch.isForced() }
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `turning it on is persisted and a new process reads it back`() {
        val store = FakeStore()
        NativeEngineDebugSwitch(store, debugBuild = true).set(true)
        assertThat(NativeEngineDebugSwitch(store, debugBuild = true).isForced()).isTrue()
    }

    @Test
    fun `turning it off again is persisted too`() {
        val store = FakeStore(value = true)
        val switch = NativeEngineDebugSwitch(store, debugBuild = true)
        assertThat(switch.isForced()).isTrue()
        switch.set(false)
        assertThat(switch.isForced()).isFalse()
        assertThat(NativeEngineDebugSwitch(store, debugBuild = true).isForced()).isFalse()
    }

    @Test
    fun `the audio thread reads a cached value, not the store`() {
        val store = FakeStore(value = true)
        val switch = NativeEngineDebugSwitch(store, debugBuild = true)
        val readsAfterLoad = store.reads
        repeat(100) { switch.isForced() }
        assertThat(store.reads).isEqualTo(readsAfterLoad)
    }

    @Test
    fun `release - a stored true is ignored, set does nothing and the store is never even read`() {
        val store = FakeStore(value = true)
        val switch = NativeEngineDebugSwitch(store, debugBuild = false)
        assertThat(switch.isForced()).isFalse()
        switch.set(true)
        assertThat(switch.isForced()).isFalse()
        assertThat(store.writes).isEqualTo(0)
        assertThat(store.reads).isEqualTo(0)
    }

    // ── The retired switch: its stored value is inert, whatever it is ───

    @Test
    fun `the new switch lives in its own file under its own key`() {
        assertThat(NativeEngineDebugSwitch.KEY).isNotEqualTo(NativeEngineDebugSwitch.RETIRED_KEY)
        assertThat(NativeEngineDebugSwitch.PREFERENCES).isNotEqualTo(NativeEngineDebugSwitch.RETIRED_PREFERENCES)
        assertThat(NativeEngineDebugSwitch.RETIRED_KEY).isEqualTo("force_exoplayer_pipeline")
        assertThat(NativeEngineDebugSwitch.RETIRED_PREFERENCES).isEqualTo("debug_pristine_pipeline")
    }

    @Test
    fun `a phone with the old switch ON (the owner's daily driver) is on the default - plan B stays off`() {
        val file = PrefsFile(mapOf(NativeEngineDebugSwitch.RETIRED_KEY to true))
        val switch = NativeEngineDebugSwitch(NativeEngineDebugSwitch.PreferencesFlagStore(file.prefs), debugBuild = true)
        assertThat(switch.isForced()).isFalse()
        assertThat(file.readKeys).containsExactly(NativeEngineDebugSwitch.KEY)
    }

    @Test
    fun `a phone with the old switch OFF does not read as force-the-engine`() {
        val file = PrefsFile(mapOf(NativeEngineDebugSwitch.RETIRED_KEY to false))
        val switch = NativeEngineDebugSwitch(NativeEngineDebugSwitch.PreferencesFlagStore(file.prefs), debugBuild = true)
        assertThat(switch.isForced()).isFalse()
        assertThat(file.readKeys).doesNotContain(NativeEngineDebugSwitch.RETIRED_KEY)
    }

    @Test
    fun `turning plan B on or off leaves the old key exactly where it was`() {
        for (old in listOf(true, false)) {
            val file = PrefsFile(mapOf(NativeEngineDebugSwitch.RETIRED_KEY to old))
            val switch = NativeEngineDebugSwitch(NativeEngineDebugSwitch.PreferencesFlagStore(file.prefs), debugBuild = true)
            switch.set(true)
            assertThat(NativeEngineDebugSwitch(NativeEngineDebugSwitch.PreferencesFlagStore(file.prefs), debugBuild = true).isForced())
                .isTrue()
            switch.set(false)
            assertThat(file.writtenKeys.toSet()).containsExactly(NativeEngineDebugSwitch.KEY)
            assertThat(file.values[NativeEngineDebugSwitch.RETIRED_KEY]).isEqualTo(old)
        }
    }

    @Test
    fun `the persisted flag round-trips through the preference store`() {
        val file = PrefsFile()
        NativeEngineDebugSwitch(NativeEngineDebugSwitch.PreferencesFlagStore(file.prefs), debugBuild = true).set(true)
        assertThat(file.values).containsExactly(NativeEngineDebugSwitch.KEY, true)
    }
}
