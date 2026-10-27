package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.log10
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * B40-P3 (replacing B39-S1's notice-only rule): USB cannot tell a DAC with its own volume knob from a dongle with none, so the listener
 * is asked once per device, before it plays. Yes, it has a knob: Resonance (recommended, the default button) with Polylute's own volume
 * lowered to a safe level, or Purity at the device's full level. No: Resonance only, and Purity is not offered. The answer is kept per
 * device. Nothing here adds gain to Purity: the question can only hold a play, and the digital volume exists only in Resonance.
 */
class FixedOutputNoticeTest {
    private val store = mutableMapOf<String, String>()
    private fun ledger() = DeviceVolumeLedger(read = { store[it] }, write = { k, v -> if (v == null) store.remove(k) else store[k] = v })
    private val ledger = ledger()

    private val dac = FixedOutputDac("dac-a", "Dongle A")
    private fun first() = FixedOutputNotice(dac, holdsPlayback = true)

    // ── when the question is due ─────────────────────────────────────────────────────────────────

    @Test
    fun `a device known to have no volume control is asked before its first play`() {
        assertThat(ledger.due("dac-a", hasVolumeControl = false)).isTrue()
        assertThat(first().stage).isEqualTo(VolumeQuestionStage.ASK_CONTROL)
    }

    @Test
    fun `a device with a UAC volume control never sees the question, nor one nobody could read yet`() {
        assertThat(ledger.due("dac-a", hasVolumeControl = true)).isFalse()
        // Unknown is not an answer either way: the descriptors could not be read (no permission), so nothing is held on a guess.
        assertThat(ledger.due("dac-a", hasVolumeControl = null)).isFalse()
    }

    @Test
    fun `closing the question with Not now remembers nothing, so it is due at the next play`() {
        assertThat(ledger.due("dac-a", hasVolumeControl = false)).isTrue()
        assertThat(ledger.due("dac-a", hasVolumeControl = false)).isTrue()
    }

    // ── "yes, it has its own knob" ───────────────────────────────────────────────────────────────

    @Test
    fun `yes leads to the choice, and Resonance is the answer that holds Purity off with a safe digital start`() {
        val next = VolumeQuestion.afterControlAnswer(first(), hasOwnControl = true) as QuestionStep.Show
        assertThat(next.notice.stage).isEqualTo(VolumeQuestionStage.CHOOSE_MODE)

        val done = VolumeQuestion.afterModeChoice(next.notice, DeviceVolumeMode.RESONANCE) { error("Resonance never turns Purity on") }
            as QuestionStep.Done
        assertThat(done.answer).isEqualTo(DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.RESONANCE))
        assertThat(done.answer.holdsPurityOff).isTrue()
        assertThat(done.answer.purityOffered).isTrue()
        // Resonance's digital level starts at the safe value (-30 dB), from unity
        val start = DeviceVolumePolicy.startGain(1f)
        assertThat(20 * log10(start.toDouble())).isWithin(0.01).of(DeviceVolumePolicy.SAFE_START_DB.toDouble())
    }

    @Test
    fun `yes then Purity goes through the lock's own activation and keeps the device at its full level`() {
        val choose = FixedOutputNotice(dac, holdsPlayback = true, stage = VolumeQuestionStage.CHOOSE_MODE)
        val done = VolumeQuestion.afterModeChoice(choose, DeviceVolumeMode.PURITY) { PurityOutcome.Enabled } as QuestionStep.Done
        assertThat(done.answer).isEqualTo(DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.PURITY))
        assertThat(done.answer.holdsPurityOff).isFalse()
    }

    @Test
    fun `Purity that would raise the level asks to confirm it first, and records nothing until it is on`() {
        val choose = FixedOutputNotice(dac, holdsPlayback = true, stage = VolumeQuestionStage.CHOOSE_MODE)
        val step = VolumeQuestion.afterModeChoice(choose, DeviceVolumeMode.PURITY) { PurityOutcome.NeedsConfirmation(30f) }
            as QuestionStep.Show
        assertThat(step.notice.stage).isEqualTo(VolumeQuestionStage.CONFIRM_RISE)
        assertThat(step.notice.riseDb).isEqualTo(30f)
        assertThat(store).isEmpty()
        // Purity that cannot be turned on ends the question without an answer: Resonance stays
        assertThat(VolumeQuestion.afterModeChoice(choose, DeviceVolumeMode.PURITY) { PurityOutcome.Failed })
            .isEqualTo(QuestionStep.PurityFailed)
    }

    // ── "no, it has none" ────────────────────────────────────────────────────────────────────────

    @Test
    fun `no means Resonance only - Purity is not offered and is held off for that device`() {
        val done = VolumeQuestion.afterControlAnswer(first(), hasOwnControl = false) as QuestionStep.Done
        assertThat(done.answer.hasOwnControl).isFalse()
        assertThat(done.answer.mode).isEqualTo(DeviceVolumeMode.RESONANCE)
        assertThat(done.answer.purityOffered).isFalse()
        assertThat(done.answer.holdsPurityOff).isTrue()
        // a "no control" device cannot be put in Purity by construction
        assertThrows<IllegalArgumentException> { DeviceVolumeAnswer(hasOwnControl = false, mode = DeviceVolumeMode.PURITY) }
    }

    // ── what an answer does to the signal ────────────────────────────────────────────────────────

    @Test
    fun `a device that holds Purity off lets the digital volume work, and the others keep the lock as the listener set it`() {
        // the listener's lock is on (the owner's daily mode)
        assertThat(DeviceVolumePolicy.effectiveLock(persistedLock = true, heldOffByDevice = true)).isFalse()
        assertThat(DeviceVolumePolicy.effectiveLock(persistedLock = true, heldOffByDevice = false)).isTrue()
        // it only ever turns Purity off: a lock that is off stays off, a held-off flag never turns it on
        assertThat(DeviceVolumePolicy.effectiveLock(persistedLock = false, heldOffByDevice = true)).isFalse()
        assertThat(DeviceVolumePolicy.effectiveLock(persistedLock = false, heldOffByDevice = false)).isFalse()
    }

    @Test
    fun `the safe start only ever lowers Polylute's volume`() {
        assertThat(DeviceVolumePolicy.startGain(1f)).isEqualTo(DeviceVolumePolicy.safeStartGain)
        assertThat(DeviceVolumePolicy.startGain(0.01f)).isEqualTo(0.01f)
        assertThat(DeviceVolumePolicy.safeStartGain).isGreaterThan(0f)
        assertThat(DeviceVolumePolicy.safeStartGain).isLessThan(0.05f)
    }

    // ── the answer is kept per device ────────────────────────────────────────────────────────────

    @Test
    fun `the answer persists, belongs to its device, and can be changed`() {
        ledger.record("dac-a", DeviceVolumeAnswer.noControl)
        // a new ledger over the same storage (the next launch) reads it back
        val relaunched = ledger()
        assertThat(relaunched.answerFor("dac-a")).isEqualTo(DeviceVolumeAnswer.noControl)
        assertThat(relaunched.due("dac-a", hasVolumeControl = false)).isFalse()
        // another device has not been asked
        assertThat(relaunched.due("dac-b", hasVolumeControl = false)).isTrue()
        // the Audio page changes it
        relaunched.record("dac-a", DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.PURITY))
        assertThat(ledger().answerFor("dac-a")?.mode).isEqualTo(DeviceVolumeMode.PURITY)
        relaunched.forget("dac-a")
        assertThat(ledger().due("dac-a", hasVolumeControl = false)).isTrue()
    }

    @Test
    fun `an answer survives the round trip through its stored form, and an unknown form is no answer`() {
        for (answer in listOf(
            DeviceVolumeAnswer.noControl,
            DeviceVolumeAnswer(true, DeviceVolumeMode.RESONANCE),
            DeviceVolumeAnswer(true, DeviceVolumeMode.PURITY),
        )) {
            assertThat(DeviceVolumeAnswer.decode(answer.encode())).isEqualTo(answer)
        }
        assertThat(DeviceVolumeAnswer.decode(null)).isNull()
        assertThat(DeviceVolumeAnswer.decode("true")).isNull()   // B39's plain "told" flag was not an answer
    }

    // ── wiring ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `wiring - the session player asks the gate before any play, and a pause is never held`() {
        val mapping = source("data/service/player/MappingPlayer.kt")
        assertThat(mapping.substringAfter("override fun play()").substringBefore("override fun pause()"))
            .contains("playGate?.invoke() == false")
        val setPlayWhenReady = mapping.substringAfter("override fun setPlayWhenReady(").substringBefore("\n    }\n")
        // B40-fix2: a pause also cancels a play that is waiting for its protection; it is still never held
        assertThat(setPlayWhenReady).contains("if (!playWhenReady) {\n            onPlaybackCancelled?.invoke()\n            onPause?.invoke()\n        }")
        assertThat(setPlayWhenReady).contains("else if (playGate?.invoke() == false) return")
        assertThat(source("data/service/MusicService.kt")).contains("playGate = { fixedOutputNotice.get().allowPlay() && engine.allowPlayForDacShare() }")
    }

    @Test
    fun `wiring - the gate only holds a play on the Direct path, the digital start only lowers, and the lock is read with the device's answer`() {
        val notice = source("data/volume/FixedOutputNotice.kt")
        // B40-fix2: the gate is two steps, the route and the DAC (allowPlay), then what is due for that DAC (allowPlayFor)
        val gate = notice.substringAfter("fun allowPlay(): Boolean {").substringBefore("\n    }\n")
        assertThat(gate).contains("route.desiredBackend != AudioBackend.USB_DIRECT")
        assertThat(gate).contains("return allowPlayFor(dac)")
        assertThat(notice.substringAfter("internal fun allowPlayFor(dac: FixedOutputDac): Boolean {").substringBefore("\n    }\n"))
            .contains("ledger.due(dac.key, hasVolumeControl = false)")
        // the only way the app's own volume is touched is the lowering start; nothing sets a level or raises it
        assertThat(notice).contains("engine.lowerUserDigitalGainTo(DeviceVolumePolicy.safeStartGain)")
        for (forbidden in listOf("setUserDigitalGain", "setDigitalLevel", "volume =", "setVolume")) {
            assertThat(notice).doesNotContain(forbidden)
        }
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine.substringAfter("fun lowerUserDigitalGainTo(gain: Float) {").substringBefore("\n    }\n"))
            .contains("if (gainModel.value.userDigitalGain > gain)")
        val preferences = source("data/preferences/UserPreferencesRepository.kt")
        assertThat(preferences).contains("combine(persistedPristineLockFlow(), purityHeldOffForDevice, DeviceVolumePolicy::effectiveLock)")
        // the lock's own activation still reads the lock as the listener set it
        assertThat(preferences.substringAfter("suspend fun pristineLockState()").substringBefore("suspend fun setPristineLock"))
            .contains("PreferencesKeys.PRISTINE_LOCK")
    }

    private fun source(path: String): String =
        File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText().replace("\r\n", "\n")
}
