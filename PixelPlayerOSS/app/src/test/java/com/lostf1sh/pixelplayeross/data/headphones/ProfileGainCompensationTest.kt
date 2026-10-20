package com.lostf1sh.pixelplayeross.data.headphones

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqFilterBank
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import com.lostf1sh.pixelplayeross.data.media.ReplayGainManager
import com.lostf1sh.pixelplayeross.data.service.ReplayGainProcessor
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.DirectPcmStageImpl
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import io.mockk.every
import io.mockk.mockk
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Test

/**
 * AUD-025 (+ REV-1/2/3), reworked by B14-2 (audit A-01): a headphone profile's own boost — Graphic EQ up to +15 dB
 * a band, LoudnessEnhancer up to +10 dB — must never make the output louder than a flat profile would.
 *
 * The two boosts are kept from raising the level in two places, and this file pins both:
 *
 * - **LoudnessEnhancer** is a platform effect whose gain is not observable, so it is taken at its declared target
 *   and cancelled by [ProfileGainCompensation.factor], a gain contributor of its own composed into the player's
 *   volume beside the user's level and ReplayGain. It is not a change to the user's digital gain: the first
 *   version lowered that on each switch to a louder profile and never gave it back, so alternating profiles
 *   ratcheted the level toward silence (REV-1), a restart with the boosted profile selected played it
 *   uncompensated (REV-2), and two quick switches both read the same stale gain (REV-3).
 * - **Graphic EQ** is cancelled inside the DSP, from the loudest point of the cascade measured on the filters
 *   that run (`GraphicEqFilterBank.headroomGain`). It used to be estimated here from the highest slider, which
 *   the cascade defeats (all bands at +15 dB lift it by +23.6 dB, not 15), and the old version of this test
 *   compared the estimate with itself, so it passed. Nothing below reads an estimate to judge an estimate: the
 *   samples that leave the real chain are measured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGainCompensationTest {

    private val flat = HeadphoneEqState()
    private val eqOnly = HeadphoneEqState(enabled = true, bands10 = listOf(0, 0, 0, 0, 12, 8, 0, 0, 0, 0))
    private val loud = HeadphoneEqState(loudnessEnhancerEnabled = true, loudnessEnhancerStrength = 600)
    private val louder = HeadphoneEqState(loudnessEnhancerEnabled = true, loudnessEnhancerStrength = 1000)

    private fun db(gain: Float) = 20f * log10(gain)

    // ── The volume contributor: the LoudnessEnhancer ───────────────────────────────────────────

    @Test
    fun `the loudness target is taken off at its declared value, a flat profile is unity`() {
        assertThat(db(ProfileGainCompensation.factor(loud))).isWithin(1e-3f).of(-6f)
        assertThat(db(ProfileGainCompensation.factor(louder))).isWithin(1e-3f).of(-10f)
        assertThat(ProfileGainCompensation.factor(flat)).isEqualTo(1f)
        // Off means off, whatever strength is stored.
        assertThat(ProfileGainCompensation.factor(loud.copy(loudnessEnhancerEnabled = false))).isEqualTo(1f)
    }

    @Test
    fun `the Graphic EQ is no longer a volume contributor, the DSP takes its headroom off`() {
        // A highest-slider estimate of 12 dB used to ride on player.volume here; the real cascade of these two
        // bands needs more than that, and it belongs ahead of the filter, where it is measured.
        assertThat(ProfileGainCompensation.factor(eqOnly)).isEqualTo(1f)
        assertThat(ProfileGainCompensation.boostDb(eqOnly)).isEqualTo(0f)

        val bank = GraphicEqFilterBank().also {
            it.prepare(48_000, 1)
            it.apply(eqOnly.toSettings())
        }
        assertThat(bank.headroomDb).isGreaterThan(12f)
    }

    // ── The whole chain: what the listener can actually be given ───────────────────────────────

    private fun HeadphoneEqState.toSettings() = GraphicEqSettings(
        enabled = enabled,
        frequenciesHz = GraphicEqBands.frequenciesFor(activeBands.size),
        gainsDb = FloatArray(activeBands.size) { activeBands[it].toFloat() },
    )

    /** The loudest steady sine, at full scale, that the Direct stage lets through for [state] at [volume]. */
    private fun loudestThroughTheChain(state: HeadphoneEqState, volume: Float): Double {
        val rate = 48_000
        val settings = state.toSettings()
        val frequencies = GraphicEqBands.frequenciesFor(state.activeBands.size)
        val sweep = (frequencies.map { it.toDouble() }.filter { it >= 100.0 } + listOf(150.0, 700.0, 1_500.0, 3_000.0, 7_000.0, 12_000.0))
        var loudest = 0.0
        for (hz in sweep) {
            val stage = DirectPcmStageImpl(
                requirements = { AudioProcessingRequirements(graphicEq = true) },
                graphicEq = { settings },
                masterVolume = { volume },
            )
            stage.configure(rate, 1)
            val samples = FloatArray(rate * 2 + rate / 4) { sin(2.0 * PI * hz * it / rate).toFloat() }
            for (start in samples.indices step 4_800) {
                val frames = minOf(4_800, samples.size - start)
                val block = samples.copyOfRange(start, start + frames)
                stage.process(block, frames)
                block.copyInto(samples, start)
            }
            loudest = maxOf(loudest, (rate * 2 until samples.size).maxOf { abs(samples[it].toDouble()) })
        }
        return loudest
    }

    @Test
    fun `a profile with every band at +15 dB and the loudness target is no louder than flat once both contributors act`() {
        val everything = HeadphoneEqState(
            enabled = true,
            bands10 = List(10) { 15 },
            loudnessEnhancerEnabled = true,
            loudnessEnhancerStrength = 1000,
        )
        // What player.volume holds for this profile: the loudness contributor only (the EQ's is in the DSP).
        val volume = ReplayGainProcessor.effectiveGainOf(1f, null, ProfileGainCompensation.factor(everything))

        val loudest = loudestThroughTheChain(everything, volume)

        // The real samples, through the real stage: the EQ's loudest point leaves at the flat level, and the
        // loudness target is taken off the volume. With the highest-slider estimate this was 1.34 or more.
        assertThat(loudest).isAtMost(volume.toDouble() * 1.001)
        assertThat(loudest).isAtLeast(volume.toDouble() * 0.94)
        assertThat(loudest).isLessThan(1.0)
    }

    @Test
    fun `a boosted EQ alone leaves the volume alone and the chain at the flat level`() {
        val volume = ReplayGainProcessor.effectiveGainOf(1f, null, ProfileGainCompensation.factor(eqOnly))
        assertThat(volume).isEqualTo(1f)

        val loudest = loudestThroughTheChain(eqOnly, volume)

        assertThat(loudest).isLessThan(1.0)
        assertThat(loudest).isAtLeast(0.94)
    }

    @Test
    fun `the headroom does not depend on which profile was selected before`() {
        // A to B to A: the headroom is a function of the curve being rendered, so there is nothing to ratchet.
        val bank = GraphicEqFilterBank().also { it.prepare(48_000, 2) }
        val a = eqOnly.toSettings()
        val b = HeadphoneEqState(enabled = true, bands10 = List(10) { 15 }).toSettings()

        bank.apply(a)
        val first = bank.headroomGain
        repeat(4) {
            bank.apply(b)
            bank.apply(a)
            assertThat(bank.headroomGain).isEqualTo(first)
        }
    }

    // ── Through the real ReplayGainProcessor: the loudness contributor and REV-1/2/3 ───────────

    private var volume = 1f
    private lateinit var processor: ReplayGainProcessor

    private val player = mockk<Player>(relaxed = true).also { player ->
        every { player.volume } answers { volume }
        every { player.setVolume(any()) } answers {
            volume = firstArg()
            processor.onPlayerVolumeChanged(volume)
        }
    }

    private fun newProcessor(): ReplayGainProcessor {
        val engine = mockk<DualPlayerEngine>(relaxed = true).also {
            every { it.masterPlayer } returns player
            every { it.isTransitionRunning() } returns false
        }
        return ReplayGainProcessor(
            engine = engine,
            replayGainManager = mockk<ReplayGainManager>(relaxed = true),
            scope = TestScope(StandardTestDispatcher()),
            currentSessionMediaItem = { null },
        ).also { processor = it }
    }

    private fun ReplayGainProcessor.selectProfile(state: HeadphoneEqState) =
        setProfileCompensation(ProfileGainCompensation.factor(state))

    @Test
    fun `A to B to A to B to A ends at the starting gain, with the user's level untouched`() {
        val processor = newProcessor()
        processor.captureUserVolume(1f)
        processor.selectProfile(flat)

        for (profile in listOf(louder, flat, louder, flat)) {
            processor.selectProfile(profile)
            if (profile == louder) assertThat(db(volume)).isWithin(1e-3f).of(-10f)
        }

        assertThat(volume).isWithin(1e-6f).of(1f)
        assertThat(processor.userGain).isWithin(1e-6f).of(1f)
        // The user's own level is unity again, so the digital-volume requirement is off and a flat
        // profile can return to Pristine.
        assertThat(ReplayGainProcessor.requiresDigitalVolume(processor.userGain)).isFalse()
    }

    @Test
    fun `the compensation composes with the user's level instead of replacing it`() {
        val processor = newProcessor()
        processor.setDiagnosticUserGain(0.5f)
        processor.selectProfile(louder)

        assertThat(processor.userGain).isWithin(1e-6f).of(0.5f)
        assertThat(db(volume)).isWithin(1e-3f).of(db(0.5f) - 10f)
    }

    @Test
    fun `a restart with the boosted profile already selected is compensated`() {
        // No profile *change* happens after a restart; the contributor comes from the active profile.
        val processor = newProcessor()
        processor.captureUserVolume(1f)
        processor.selectProfile(louder)

        assertThat(db(processor.combinedGain)).isWithin(1e-3f).of(-10f)
    }

    @Test
    fun `quick consecutive switches land on the last profile's compensation`() {
        val processor = newProcessor()
        processor.captureUserVolume(1f)
        processor.selectProfile(louder)
        processor.selectProfile(loud)
        processor.selectProfile(louder)

        assertThat(db(volume)).isWithin(1e-3f).of(-10f)
    }

    @Test
    fun `selecting an EQ-only boosted profile does not touch the player's volume`() {
        val processor = newProcessor()
        processor.captureUserVolume(0.8f)
        processor.selectProfile(eqOnly)

        assertThat(volume).isWithin(1e-6f).of(0.8f)
        assertThat(processor.combinedGain).isWithin(1e-6f).of(0.8f)
    }
}
