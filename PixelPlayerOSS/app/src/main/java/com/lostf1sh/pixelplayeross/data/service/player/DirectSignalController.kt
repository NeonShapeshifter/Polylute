package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectPcmStage
import com.decent.usbaudio.media3.DirectProcessing
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.DirectSignalSource
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqFilterBank
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqGainRamp
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings

/**
 * Answers the two questions the Direct transport asks about the signal: which mode to run in, and
 * what to do to the samples.
 *
 * ## Why this is a live source rather than a construction-time flag
 *
 * The mode can change while a track plays, and the change is arithmetic — a filter bank being
 * engaged, a gain leaving unity. Rebuilding the player to deliver that would release and re-claim
 * the exclusive USB session, renegotiate the DAC's clock and interrupt audio, all to change a
 * multiply. So the sink reads this object on every buffer instead, and a mode change costs one
 * pipeline realignment inside the sink rather than a transport teardown.
 *
 * ## Why pristine is expressed as "no requirement" rather than as a flag to clear
 *
 * [mode] is derived from [requirements], the same value the routing policy decides from. There is
 * therefore exactly one authority for "processing is wanted", and the route the policy picked and
 * the path the sink runs cannot disagree — which is the failure that would otherwise show as a
 * diagnostic claiming Equalizer: Active while the DAC receives untouched source.
 *
 * @param requirements the live listening requirements, read on each call.
 * @param graphicEq the live EQ snapshot, read on each call.
 * @param masterVolume the player's current volume, which is where both the user's digital volume
 *        and ReplayGain end up. Reading the player rather than a private copy is what keeps this
 *        path and the Android path agreeing about how loud the track should be.
 */
class DirectSignalController(
    private val requirements: () -> AudioProcessingRequirements,
    private val graphicEq: () -> GraphicEqSettings,
    private val masterVolume: () -> Float,
    /** The PROCESSED stage's duck target while another app asks for "may duck" focus; 1 = none (B14-3). */
    private val duckGain: () -> Float = { 1f },
    private val userDigitalGainProvider: () -> Float = { 1f },
    private val replayGainFactorProvider: () -> Float? = { null },
    /**
     * Diagnostics only: a one-line description of the player's own state, mirrored on its thread.
     *
     * Recorded into the transition event, never read by a decision. A renderer-domain anchor means
     * one thing at EOF after a restart and another mid-track during ordinary playback, and only the
     * surrounding player state distinguishes them.
     */
    private val handoffContextProvider: () -> String? = { null },
    /**
     * DEBUG ONLY: plan B (see [NativeEngineDebugSwitch]). Off unless given, which is the default (B12a): the
     * ExoPlayer pipeline carries PRISTINE and no engine exists. It changes which producer carries a pristine
     * FLAC, never the mode: [mode] does not read it.
     */
    private val debugForceNativeEngineProvider: () -> Boolean = { false },
    /**
     * DEBUG ONLY (B12a-4, see [RateSwitchPrerollSetting]): ms of silence ahead of an item's first frame after a rate
     * change. 0 unless given, which is the default; read where a stream is rebuilt, never on the audio path.
     */
    private val rateSwitchPrerollMsProvider: () -> Int = { 0 },
) : DirectSignalSource {

    private val stage = DirectPcmStageImpl(
        requirements = requirements,
        graphicEq = graphicEq,
        masterVolume = masterVolume,
        duckGain = duckGain,
        userDigitalGain = userDigitalGainProvider,
        replayGainFactor = replayGainFactorProvider,
    )

    override fun handoffContext(): String? = handoffContextProvider()

    override fun debugForceNativeEngine(): Boolean = debugForceNativeEngineProvider()

    override fun rateSwitchPrerollMs(): Int = rateSwitchPrerollMsProvider()

    override fun userDigitalGain(): Float = userDigitalGainProvider()

    override fun replayGainFactor(): Float? = replayGainFactorProvider()

    override fun mode(): DirectSignalMode =
        if (requirements().requiresPcmProcessing) {
            DirectSignalMode.PROCESSED
        } else {
            DirectSignalMode.PRISTINE
        }

    override fun stage(): DirectPcmStage = stage
}

/**
 * The Direct path's PCM processing: graphic EQ, then one combined gain, then one saturation.
 *
 * ## Order, and why
 *
 * ```
 * source samples → EQ headroom → graphic EQ → user volume × ReplayGain × duck → saturate → USB
 * ```
 *
 * - **Headroom first (B14-2, audit A-01).** The bands run in cascade and overlapping boosts add up, so
 *   the curve's loudest frequency is well above its highest slider (all bands at +15 dB: +23.7 dB with
 *   ten, +25.2 dB with fifteen). The bank measures that peak on the coefficients it runs, at the
 *   stream's rate, and the stage takes it off ahead of the filter. What the user's volume does
 *   afterwards is the listener's level and never part of keeping the EQ from clipping.
 * - **EQ before gain.** Both are linear, so the two orders are mathematically identical in float;
 *   the choice is about where the single saturation lands. With the headroom taken off first the
 *   cascade's output stays within the level it was given, and the stage clips only when the
 *   *listener's* chosen level actually exceeds full scale, not when a band boost does.
 * - **One multiplication, not several.** User volume and ReplayGain arrive as a single value
 *   because that is how the player already carries them, and multiplying twice by two halves is
 *   not the same as multiplying once by the product in a format with a finite significand.
 * - **One saturation, at the end.** Intermediate clamping would make the stage's output depend on
 *   the order of its own internal steps; a single clamp at the boundary is deterministic and is
 *   the same clamp the native layer applies on its way to the DAC, so nothing downstream can
 *   reintroduce a different one.
 *
 * ## Why float, and why that is not a downgrade
 *
 * The stage works in float32 in place. A float32 significand is 24 bits, so every 16- and 24-bit
 * integer sample converts to float and back *exactly* — the power-of-two scales involved cannot
 * round. A 24-bit/192 kHz source therefore passes through this stage with all 24 bits intact; the
 * quantisation that does occur is the single, final one against the bit depth the DAC negotiated.
 *
 * ## Cost when idle
 *
 * With the EQ bypassed and unity gain the loop returns before touching a sample. That is not an
 * optimisation of an approximation — it is the same guarantee the Android-path processor makes,
 * and it is what lets [DirectSignalMode.PRISTINE] mean something the code can be held to.
 */
internal class DirectPcmStageImpl(
    private val requirements: () -> AudioProcessingRequirements,
    private val graphicEq: () -> GraphicEqSettings,
    private val masterVolume: () -> Float,
    /**
     * A duck's target gain (B14-3, audit A-05): 1 unless another app has asked for "may duck" focus while this
     * signal is PROCESSED. Ramped here, per sample, so the music is not cut: the target is only where it ends.
     */
    private val duckGain: () -> Float = { 1f },
    /** The user's own gain, for attribution only — never for the arithmetic, which uses the product. */
    private val userDigitalGain: () -> Float = { 1f },
    /** The ReplayGain factor, for attribution only. */
    private val replayGainFactor: () -> Float? = { null },
) : DirectPcmStage {

    private val filterBank = GraphicEqFilterBank()

    /** Glides gain-only changes over ~15 ms; the stage now runs at write time (AUD-111). */
    private val gainRamp = GraphicEqGainRamp()

    private var channelCount = 0
    private var sampleRate = 0

    // The duck: a linear glide of one gain from where it is to the target. Falls fast (the prompt must be
    // heard), rises slowly (the music should not pump back).
    private var duckCurrent = 1f
    private var duckFrom = 1f
    private var duckTo = 1f
    private var duckElapsed = 0
    private var duckTotal = 0

    /** The settings the filter bank was last designed for; identity is the change signal. */
    private var appliedSettings: GraphicEqSettings = GraphicEqSettings.BYPASS

    override fun configure(sampleRate: Int, channelCount: Int): Boolean {
        if (sampleRate <= 0 || channelCount !in 1..MAX_CHANNELS) return false
        this.channelCount = channelCount
        this.sampleRate = sampleRate
        filterBank.prepare(sampleRate, channelCount)
        appliedSettings = GraphicEqSettings.BYPASS
        // A new stream starts at the duck the listener has now, not gliding to it.
        duckTo = duckGain().coerceIn(0f, 1f)
        duckCurrent = duckTo
        duckFrom = duckTo
        duckElapsed = 0
        duckTotal = 0
        return true
    }

    override fun process(samples: FloatArray, frameCount: Int) {
        val channels = channelCount
        if (channels <= 0 || frameCount <= 0) return

        val settings = graphicEq()
        val eqActive = !settings.isBypass
        if (eqActive && settings !== appliedSettings) {
            // Same band table, both active: glide the gains (a slider move). Anything else — a new
            // table, or the first curve after bypass — is a new curve and is loaded at once.
            val previous = appliedSettings
            val glide = previous != null && !previous.isBypass &&
                previous.frequenciesHz.contentEquals(settings.frequenciesHz)
            if (glide) {
                gainRamp.retarget(settings.gainsDb)
            } else {
                filterBank.apply(settings)
                gainRamp.snap(settings.gainsDb)
            }
            appliedSettings = settings
        }

        val gain = masterVolume().coerceIn(0f, 1f)
        retargetDuck(duckGain().coerceIn(0f, 1f))

        // Provably neutral: no sample is read, so none can be altered.
        if (!eqActive && gain == 1f && duckCurrent == 1f && duckTo == 1f) return

        var index = 0
        for (frame in 0 until frameCount) {
            if (eqActive && frame % RAMP_BLOCK_FRAMES == 0 && gainRamp.isRamping && sampleRate > 0) {
                val gains = gainRamp.advance(RAMP_BLOCK_FRAMES, sampleRate)
                filterBank.apply(GraphicEqSettings(true, settings.frequenciesHz, gains))
            }
            // Re-read after every apply: during a glide the headroom follows the gains of the block (B14-2).
            val headroom = if (eqActive) filterBank.headroomGain else 1f
            if (duckElapsed < duckTotal) {
                duckElapsed++
                // Lands on the target exactly: a float glide that ends an ulp short of unity would leave the
                // stage multiplying by 0.99999994 for ever instead of being neutral again.
                duckCurrent = if (duckElapsed >= duckTotal) duckTo else duckFrom + (duckTo - duckFrom) * duckElapsed / duckTotal
            }
            val frameGain = gain * duckCurrent
            for (channel in 0 until channels) {
                var sample = samples[index]
                if (eqActive) sample = filterBank.processSample(channel, sample * headroom)
                if (frameGain != 1f) sample *= frameGain
                samples[index] = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
                index++
            }
        }
    }

    /** A new duck target starts a glide from wherever the last one got to. */
    private fun retargetDuck(target: Float) {
        if (target == duckTo) return
        duckFrom = duckCurrent
        duckTo = target
        duckElapsed = 0
        val ms = if (target < duckCurrent) DUCK_FALL_MS else DUCK_RISE_MS
        duckTotal = (sampleRate.toLong() * ms / 1000).toInt().coerceAtLeast(1)
    }

    override fun activeProcessing(): Set<DirectProcessing> = buildSet {
        if (!graphicEq().isBypass) add(DirectProcessing.EQUALIZER)
        // Each contributor is named from its own value. Both can be active together, and a set
        // derived from their product could only ever report one — the user's own volume would go
        // unmentioned whenever ReplayGain was also engaged.
        if (isNonUnity(userDigitalGain())) add(DirectProcessing.DIGITAL_VOLUME)
        if (replayGainFactor()?.let(::isNonUnity) == true) add(DirectProcessing.REPLAY_GAIN)
    }

    override fun reset() {
        filterBank.resetState()
        appliedSettings = GraphicEqSettings.BYPASS
        // The glide's position is not stream state: a seek or a rebuild must not jump the level of a duck.
    }

    private companion object {
        /** Guard against a header claiming an implausible channel count. */
        const val MAX_CHANNELS = 8

        /** Coefficient update granularity during a gain glide: ~1.5 ms at 44.1 kHz. */
        const val RAMP_BLOCK_FRAMES = 64

        /** How long a duck takes to go down, and to come back (B14-3). */
        const val DUCK_FALL_MS = 150
        const val DUCK_RISE_MS = 400

        /**
         * Whether a contributor is doing anything audible.
         *
         * Non-unity, not merely present: a contributor sitting at 1.0 is a mathematical identity
         * and must not appear in the active set. Defined here rather than reused from the ReplayGain
         * processor so the DSP does not take a dependency on the service.
         */
        fun isNonUnity(value: Float): Boolean = kotlin.math.abs(value - 1f) > 0.0001f
    }
}
