package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B9-4: why `SET_REPLAY_GAIN_FACTOR` stopped producing PROCESSED, and the process-only way to measure it again.
 *
 * It did not break. On 2026-09-29 the owner's Pure Direct lock (F-02) was off (`pristine_lock_enabled` false in the
 * backup taken before B8a); by 2026-10-06 it was on (true in the one before B8b), and the lock drops every DSP
 * contributor's requirement — a synthetic ReplayGain factor included — by design. The B8b capture shows both halves:
 * `GAIN|START|replayGainFactor=0.5` (the factor reached the processor) and `DIRECT_PRISTINE` (the requirement did not).
 */
class PristineLockDiagnosticTest {

    @AfterEach
    fun off() {
        PristineLockDiagnostic.setIgnoring(false)
    }

    private val withReplayGain = AudioProcessingRequirements(replayGain = true)

    @Test
    fun `with the owner's lock on, a ReplayGain requirement is frozen - the state the 10-06 capture was in`() {
        val lockOn = PristineLockDiagnostic.effectiveLock(persistedLock = true, ignoringOverride = false)
        assertThat(lockOn).isTrue()
        assertThat(PristineModePolicy.requirements(withReplayGain, lockOn).replayGain).isFalse()
    }

    @Test
    fun `the override lets a measurement through without touching the persisted lock`() {
        val read = PristineLockDiagnostic.effectiveLock(persistedLock = true, ignoringOverride = true)
        assertThat(read).isFalse()
        assertThat(PristineModePolicy.requirements(withReplayGain, read).replayGain).isTrue()
        // It is a reading of the lock, not a write: the lock's own value is what the function was given.
        assertThat(PristineLockDiagnostic.effectiveLock(persistedLock = false, ignoringOverride = true)).isFalse()
        assertThat(PristineLockDiagnostic.effectiveLock(persistedLock = false, ignoringOverride = false)).isFalse()
    }

    @Test
    fun `the requirements' lock follows both the preference and the override, and remembers the preference`() = runTest {
        val preference = MutableStateFlow(true)
        val seen = PristineLockDiagnostic.requirementsLock(preference)
        assertThat(seen.first()).isTrue()
        assertThat(PristineLockDiagnostic.lastPersistedLock()).isTrue()

        PristineLockDiagnostic.setIgnoring(true)
        assertThat(seen.first()).isFalse()
        assertThat(PristineLockDiagnostic.lastPersistedLock()).isTrue()   // still what the preference says

        PristineLockDiagnostic.setIgnoring(false)
        preference.value = false
        assertThat(seen.first()).isFalse()
        assertThat(PristineLockDiagnostic.lastPersistedLock()).isFalse()
    }

    @Test
    fun `a factor switches the mode only when it is not unity and nothing freezes it`() {
        // The lock on: nothing switches, whatever the factor.
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(0.5f, persistedLock = true, ignoringOverride = false)).isFalse()
        // The override on, or the lock off: 0.5 does.
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(0.5f, persistedLock = true, ignoringOverride = true)).isTrue()
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(0.5f, persistedLock = false, ignoringOverride = false)).isTrue()
        // A lock never seen is read as off, which is what an unset preference is.
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(0.5f, persistedLock = null, ignoringOverride = false)).isTrue()
        // Unity and "no contribution" are not processing in either case (the same tolerance the requirements use).
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(1.0f, persistedLock = false, ignoringOverride = true)).isFalse()
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(1.00005f, persistedLock = false, ignoringOverride = true)).isFalse()
        assertThat(PristineLockDiagnostic.replayGainSwitchesToProcessed(null, persistedLock = false, ignoringOverride = true)).isFalse()
    }

    @Test
    fun `the override is a process-only variable that no preference or release code can reach`() {
        val main = File("src/main/java/com/lostf1sh/pixelplayeross")
        val debug = File("src/debug/java/com/lostf1sh/pixelplayeross")
        val diagnostic = File(main, "data/volume/PristineLockDiagnostic.kt").readText()
        // Not persisted: no preferences, no DataStore, no file.
        for (forbidden in listOf("dataStore", "DataStore", "SharedPreferences", "PreferencesKeys", "File(", "setPristineLock")) {
            assertThat(diagnostic).doesNotContain(forbidden)
        }
        // Set from the debug source set only.
        val setters = main.walkTopDown().filter { it.extension == "kt" && it.name != "PristineLockDiagnostic.kt" }
            .filter { it.readText().contains("PristineLockDiagnostic.setIgnoring(") }.toList()
        assertThat(setters).isEmpty()
        assertThat(File(debug, "dap/admin/DapDebugPolicyReceiver.kt").readText()).contains("PristineLockDiagnostic.setIgnoring(")
        // The release manifest does not declare the action.
        assertThat(File("src/main/AndroidManifest.xml").readText()).doesNotContain("SET_PRISTINE_LOCK_OVERRIDE")
        assertThat(File("src/debug/AndroidManifest.xml").readText()).contains("SET_PRISTINE_LOCK_OVERRIDE")
    }

    @Test
    fun `the bridge says why a factor did or did not switch the mode, and what it received`() {
        val receiver = File("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt").readText().replace("\r\n", "\n")
        for (field in listOf("extraType=", "delivered=", "persistedPristineLock=", "ignoringLock=", "switchesToProcessed=", "why=")) {
            assertThat(receiver).contains(field)
        }
        assertThat(receiver).contains("pure-direct-lock-freezes-requirements")
        // An extra that is not a float is read by getFloatExtra as its default: the report must be able to see it.
        assertThat(receiver).contains("intent.extras?.get(EXTRA_RG_FACTOR)")
        val capture = File("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DirectPathCapture.kt").readText().replace("\r\n", "\n")
        assertThat(capture).contains("GAIN|\$label|persistedPristineLock=")
        assertThat(capture).contains("GAIN|\$label|pristineLockIgnoredByDiagnostic=")
    }
}
