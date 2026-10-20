package com.lostf1sh.pixelplayeross.data.audio.dsp

import kotlin.math.pow

/**
 * Immutable snapshot of the EQ the DSP engine should render.
 *
 * Instances are published as a whole through an `AtomicReference`, so the audio thread never
 * observes a half-updated band table. Reference identity (`===`) is the change signal: a publisher
 * that re-states the same values creates a new instance and therefore a redundant — but harmless
 * and cheap — coefficient rebuild.
 */
class GraphicEqSettings(
    val enabled: Boolean,
    val frequenciesHz: IntArray,
    val gainsDb: FloatArray,
) {
    val bandCount: Int get() = frequenciesHz.size

    /**
     * True when the processor must not touch PCM at all: either the EQ is switched off, or every
     * band is exactly 0 dB so the filter bank would be a mathematical identity anyway.
     */
    val isBypass: Boolean = !enabled || gainsDb.all { it == 0f }

    companion object {
        val BYPASS = GraphicEqSettings(
            enabled = false,
            frequenciesHz = GraphicEqBands.TEN_BAND_HZ,
            gainsDb = FloatArray(GraphicEqBands.TEN_BAND_COUNT),
        )
    }
}

/**
 * The cascaded peaking-EQ sections of one Graphic EQ configuration, with independent filter memory
 * per channel.
 *
 * Every band gets its own [PeakingEqBiquad] per channel, so:
 *
 * - a 10-band configuration runs ten filters and a 15-band configuration runs fifteen;
 * - no band's gain can influence another band's coefficients;
 * - no channel's filter memory is shared with, read by, or written by another channel, which is
 *   what makes channel separation exact rather than approximate.
 *
 * Bands whose gain is exactly 0 dB are designed as identities and dropped from the processing list,
 * so `N` sliders at zero cost the same as `N` fewer filters.
 *
 * Not thread-safe. It is owned by the audio thread inside the processor; the only cross-thread
 * input is an immutable [GraphicEqSettings] snapshot.
 */
internal class GraphicEqFilterBank {

    private var channelCount = 0
    private var bandCount = 0
    private var sampleRateHz = 0

    /** `sections[channel][band]`. Empty until the first [apply] after a [prepare]. */
    private var sections: Array<Array<PeakingEqBiquad>> = emptyArray()

    /** Indices of the bands whose gain is non-zero, in ascending frequency order. */
    private var activeBands: IntArray = IntArray(0)

    private var qualityFactor = GraphicEqBands.qualityFactor(GraphicEqBands.TEN_BAND_HZ)

    /** The snapshot whose coefficients are currently loaded. Identity comparison only. */
    private var appliedSettings: GraphicEqSettings? = null

    /** Band table currently realised by [sections], used to detect a reshape. */
    private var appliedFrequencies: IntArray = IntArray(0)

    /** The cascade's peak gain at this stream's rate; null until the stream has a rate. */
    private var headroomEstimator: GraphicEqHeadroom? = null

    /**
     * How far the applied cascade lifts its loudest frequency, in dB (0 when it only cuts). Measured on the
     * coefficients actually running, at the stream's sample rate (B14-2, audit A-01).
     */
    var headroomDb: Float = 0f
        private set

    /**
     * The linear gain a caller applies to every sample *before* [processSample], so that the cascade's loudest
     * frequency leaves at the level it entered with. 1 when the curve only cuts.
     *
     * Kept out of [processSample] on purpose: the bank stays the plain cascade it is measured as, and the two
     * paths that own a saturation point (the Android processor, the Direct stage) take the headroom off ahead of it.
     * Both read it from the same applied snapshot as the coefficients, so a preview and the curve it previews
     * cannot disagree.
     */
    var headroomGain: Float = 1f
        private set

    /**
     * Declares the stream shape. Called on every `configure()`, i.e. on format changes, and clears
     * filter memory because delay lines from a previous shape are meaningless.
     *
     * A repeat call with the same shape is a no-op so that a re-configure of an unchanged stream
     * does not needlessly discard filter state.
     */
    fun prepare(sampleRateHz: Int, channelCount: Int) {
        if (this.sampleRateHz == sampleRateHz && this.channelCount == channelCount &&
            sections.isNotEmpty()
        ) {
            return
        }
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.bandCount = 0
        this.sections = emptyArray()
        this.activeBands = IntArray(0)
        this.appliedSettings = null
        this.appliedFrequencies = IntArray(0)
        this.headroomEstimator = if (sampleRateHz > 0) GraphicEqHeadroom(sampleRateHz) else null
        this.headroomDb = 0f
        this.headroomGain = 1f
    }

    /** Clears filter history without touching coefficients. Used by flush and by a reshape. */
    fun resetState() {
        for (channel in sections) {
            for (section in channel) {
                section.clearState()
            }
        }
    }

    /**
     * Loads [settings] if it is not already the applied snapshot.
     *
     * A change of gains alone keeps filter memory, so dragging a slider does not click. A change of
     * band count or band table (a 10 ↔ 15 mode switch) rebuilds the bank and resets memory, because
     * the old delay lines no longer correspond to the new cascade.
     */
    fun apply(settings: GraphicEqSettings) {
        if (settings === appliedSettings && sections.isNotEmpty()) return

        val sameShape = sections.isNotEmpty() &&
            sections.size == channelCount &&
            bandCount == settings.bandCount &&
            settings.frequenciesHz.contentEquals(appliedFrequencies)

        if (!sameShape) {
            bandCount = settings.bandCount
            qualityFactor = GraphicEqBands.qualityFactor(settings.frequenciesHz)
            appliedFrequencies = settings.frequenciesHz.copyOf()
            sections = Array(channelCount) { Array(bandCount) { PeakingEqBiquad() } }
            resetState()
        }

        designInto(sampleRateHz, settings.frequenciesHz, settings.gainsDb)
        updateHeadroom()
        appliedSettings = settings
    }

    private fun designInto(sampleRateHz: Int, frequenciesHz: IntArray, gainsDb: FloatArray) {
        val active = ArrayList<Int>(frequenciesHz.size)
        for (band in frequenciesHz.indices) {
            val gain = gainsDb.getOrElse(band) { 0f }
            for (channel in 0 until channelCount) {
                sections[channel][band].design(
                    gainDb = gain,
                    frequencyHz = frequenciesHz[band],
                    sampleRateHz = sampleRateHz,
                    q = qualityFactor,
                )
            }
            if (gain != 0f) active.add(band)
        }
        activeBands = active.toIntArray()
    }

    /** Every channel runs identical coefficients, so channel 0's sections describe the cascade. */
    private fun updateHeadroom() {
        val estimator = headroomEstimator
        if (estimator == null || sections.isEmpty()) {
            headroomDb = 0f
            headroomGain = 1f
            return
        }
        headroomDb = estimator.peakGainDb(sections[0], activeBands)
        headroomGain = if (headroomDb <= 0f) 1f else 10.0.pow(-headroomDb / 20.0).toFloat()
    }

    /**
     * Runs one sample of [channelIndex] through that channel's cascade.
     *
     * Channels are addressed independently and never read each other's memory, so left/right
     * separation is exact.
     *
     * A non-finite intermediate would otherwise be self-sustaining inside a recursive filter
     * forever. If one is ever produced, the whole channel's memory is cleared and the sample is
     * replaced by digital silence, returning the filter to a known state within one sample instead
     * of letting NaN or infinity escape into the output stream.
     */
    fun processSample(channelIndex: Int, sample: Float): Float {
        if (activeBands.isEmpty()) return sample
        val channelSections = sections[channelIndex]
        var value = sample
        for (index in activeBands.indices) {
            value = channelSections[activeBands[index]].process(value)
        }
        if (value.isFinite()) return value
        for (section in channelSections) {
            section.clearState()
        }
        return 0f
    }

    // --- Introspection used by tests -------------------------------------------------------

    internal val bandCountForTest: Int get() = bandCount
    internal val channelCountForTest: Int get() = channelCount
    internal val activeBandCountForTest: Int get() = activeBands.size
    internal val qualityFactorForTest: Float get() = qualityFactor
    internal val sampleRateForTest: Int get() = sampleRateHz

    internal fun coefficientsForTest(channel: Int, band: Int): FloatArray =
        sections[channel][band].coefficientsForTest()

    /**
     * Identity of the section array itself, so a test can prove a gains-only change reuses the
     * existing section objects instead of rebuilding the bank on every slider move.
     */
    internal val sectionsIdentityForTest: Any get() = sections
}
