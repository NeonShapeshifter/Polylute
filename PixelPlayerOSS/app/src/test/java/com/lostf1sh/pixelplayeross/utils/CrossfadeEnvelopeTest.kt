package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Curve
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/**
 * AUD-122: with the crossfade on, the owner heard the volume go down and up, not two tracks mixing.
 * The S curve was `(1 − cos πp) / 2 = sin²(πp/2)` — a *power* shape — used as an *amplitude*, with
 * the outgoing track at `1 − envelope`: `volIn + volOut ≡ 1`, a linear crossfade dressed as an S. At
 * 10 % of the fade the incoming track was 32 dB down, at the middle both sat at −6 dB (a −3 dB hole
 * in the power) and the first second of a 2 s fade was the outgoing track alone.
 *
 * Equal power is sin/cos in amplitude: `volIn² + volOut² ≡ 1`, both −3 dB at the middle.
 */
class CrossfadeEnvelopeTest {

    private fun db(amplitude: Float) = 20.0 * log10(amplitude.toDouble())
    private val steps = (1..9).map { it / 10f }

    private fun gains(p: Float, incomingTarget: Float = 1f, outgoingStart: Float = 1f) =
        crossfadeGains(p, Curve.S_CURVE, Curve.S_CURVE, incomingTarget, outgoingStart)

    @Test
    fun `the S curve crossfade keeps the power constant`() {
        steps.forEach { p ->
            val g = gains(p)
            val power = g.incoming * g.incoming + g.outgoing * g.outgoing
            assertThat(power).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `the incoming track is not 32 dB down at 10 percent, and the middle is -3 dB each`() {
        // Measured on the old curve: sin²(0.1π/2) = 0.0245 = −32.2 dB. Equal power at 10 % is
        // sin(0.1π/2) = 0.156 = −16.1 dB: 16 dB more, and −8.3 dB (not −16.7) at 25 %.
        assertThat(db(gains(0.10f).incoming)).isWithin(0.1).of(-16.1)
        assertThat(db(gains(0.10f).incoming)).isGreaterThan(-20.0)
        assertThat(db(gains(0.25f).incoming)).isWithin(0.1).of(-8.3)
        val middle = gains(0.5f)
        assertThat(db(middle.incoming)).isWithin(0.05).of(-3.01)
        assertThat(db(middle.outgoing)).isWithin(0.05).of(-3.01)
        assertThat(middle.incoming).isWithin(1e-6f).of(sqrt(0.5f))
    }

    @Test
    fun `the outgoing track is the mirror of the incoming one`() {
        steps.forEach { p ->
            assertThat(gains(p).outgoing).isWithin(1e-6f).of(gains(1f - p).incoming)
        }
    }

    @Test
    fun `it starts on the outgoing track and ends on the incoming one at their own composed gain`() {
        // user 0.3 × ReplayGain 0.5 × compensation 0.8 for the incoming track; the outgoing one was at 0.45.
        val target = 0.3f * 0.5f * 0.8f
        val start = gains(0f, incomingTarget = target, outgoingStart = 0.45f)
        assertThat(start.incoming).isEqualTo(0f)
        assertThat(start.outgoing).isWithin(1e-6f).of(0.45f)
        val end = gains(1f, incomingTarget = target, outgoingStart = 0.45f)
        assertThat(end.incoming).isWithin(1e-6f).of(target)
        assertThat(end.outgoing).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `a crossfade never raises either track above its own composed gain`() {
        // The invariant of the volume-safety batch: nothing automatic makes the effective level louder.
        val target = 0.12f
        val outgoingStart = 0.3f
        (0..100).map { it / 100f }.forEach { p ->
            val g = gains(p, incomingTarget = target, outgoingStart = outgoingStart)
            assertThat(g.incoming).isAtMost(target + 1e-6f)
            assertThat(g.outgoing).isAtMost(outgoingStart + 1e-6f)
        }
    }

    @Test
    fun `the power of each side follows its own composed gain`() {
        val target = 0.4f
        val outgoingStart = 0.4f
        steps.forEach { p ->
            val g = gains(p, incomingTarget = target, outgoingStart = outgoingStart)
            assertThat(g.incoming * g.incoming + g.outgoing * g.outgoing)
                .isWithin(1e-5f).of(target * target)
        }
    }

    @Test
    fun `the other curves keep their shapes`() {
        // LINEAR, LOG and EXP were not the defect and are left as they were: the incoming track takes
        // the envelope and the outgoing one its complement.
        listOf(Curve.LINEAR, Curve.LOG, Curve.EXP).forEach { curve ->
            steps.forEach { p ->
                val g = crossfadeGains(p, curve, curve, 1f, 1f)
                assertThat(g.incoming).isWithin(1e-6f).of(envelope(p, curve))
                assertThat(g.outgoing).isWithin(1e-6f).of(1f - envelope(p, curve))
            }
        }
        assertThat(envelope(0.5f, Curve.LINEAR)).isEqualTo(0.5f)
        assertThat(envelope(0.25f, Curve.EXP)).isWithin(1e-6f).of(0.0625f)
        assertThat(envelope(0.25f, Curve.LOG)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `progress outside 0 to 1 is clamped`() {
        assertThat(gains(-1f).incoming).isEqualTo(0f)
        assertThat(gains(2f).outgoing).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `the engine's fade loop takes its volumes from the equal-power helper`() {
        val engine = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt")
            .readText().replace("\r\n", "\n")
        val loop = engine.substringAfter("val stepMs = 32L").substringBefore("outgoingPlayer.volume = 0f")
        assertThat(loop).contains("crossfadeGains(")
        // The power curve used as an amplitude, and the complement taken by subtraction.
        assertThat(loop).doesNotContain("1f - envelope(")
        assertThat(loop).doesNotContain("envelope(progress, settings.curveIn)")
        assertThat(loop).contains("incomingTrackEffectiveVolume ?: gainWithoutReplayGain()")
    }
}
