package com.decent.usbaudio.media3

/**
 * What the Direct transport is doing to the signal — a question that is deliberately separate
 * from *which transport* is carrying it.
 *
 * Before this existed, `UsbAudioSinkConfig.bitPerfectEnabled` answered both questions at once: it
 * meant "use the USB transport" and was therefore also read as "the signal is bit-perfect". Those
 * are not the same claim. A DAC can be fed processed PCM, and PCM can be bit-perfect over a
 * transport that is not USB. Conflating them made it impossible to describe — let alone build — a
 * Direct path that applies DSP, because "Direct" and "unmodified" were the same flag.
 *
 * [PRISTINE] is the flagship path: the source's own samples reach the DAC without the application
 * touching them. [PROCESSED] is the same transport carrying PCM the application has modified.
 */
enum class DirectSignalMode {
    /** No application PCM modification. Sample values are passed through unchanged. */
    PRISTINE,

    /** The application is actively modifying PCM before it reaches the transport. */
    PROCESSED,
}

/**
 * Which processing stages are currently altering the signal.
 *
 * Carried as a set rather than a boolean so a diagnostic can name *why* a stream is not bit-perfect
 * instead of only asserting that it is not. The distinction matters to a listener deciding whether
 * a perceived change is theirs: "Equalizer active" and "Digital volume active" are different
 * answers to the same question.
 */
enum class DirectProcessing {
    EQUALIZER,
    DIGITAL_VOLUME,
    REPLAY_GAIN,
    SPEED,
}

/**
 * One in-place PCM processing stage feeding the Direct transport.
 *
 * Implemented by the host application and driven by [UsbAudioSink], which owns the decode side of
 * the pipeline but knows nothing about what the DSP does. The wrapper deliberately does not contain
 * a filter implementation: the application already owns its EQ, its gain model and the user's
 * preferences, and duplicating them here would create two authorities for one sound.
 *
 * ## Precision contract
 *
 * Implementations are given interleaved 32-bit float samples normalised to full scale, and must
 * return the same buffer shape. Float is the working format because it is the only one that can
 * represent a 24-bit integer sample exactly without widening (a float32 significand is 24 bits), so
 * a 24-bit source survives a float processing stage with no loss whatsoever. See [PcmUtils] for the
 * conversions on either side of this stage.
 *
 * An implementation must NOT change the sample rate, the channel count or the frame count. Those
 * are properties the USB transport has already negotiated with the DAC; a stage that changed them
 * would make the transport's advertised format a lie.
 */
interface DirectPcmStage {

    /**
     * Prepares the stage for a stream of this shape.
     *
     * Called before the first [process] and again whenever the transport reconfigures. Returns
     * `false` when this stage cannot process the given format; the sink then passes PCM through
     * untouched *and reports itself as not processing*, rather than silently claiming to process a
     * signal it is leaving alone.
     */
    fun configure(sampleRate: Int, channelCount: Int): Boolean

    /**
     * Processes [frameCount] frames of interleaved float PCM in [samples], in place.
     *
     * [samples] is sized `frameCount * channelCount`. The implementation must leave the array the
     * same length and must not reorder channels.
     */
    fun process(samples: FloatArray, frameCount: Int)

    /** The stages currently altering the signal, for the truthful bit-perfect verdict. */
    fun activeProcessing(): Set<DirectProcessing>

    /** Drops filter history. Called on flush/seek so a new stream starts from silence. */
    fun reset()
}

/**
 * The live authority for how the Direct transport should treat the signal.
 *
 * Read by [UsbAudioSink] on every buffer rather than captured at construction, because the answer
 * changes while a track plays: toggling the equalizer must take effect without rebuilding the
 * player, and a rebuild would release and re-claim the exclusive USB session for a change that is
 * purely arithmetic.
 *
 * Implementations must be safe to call from the audio thread and must not allocate per call.
 */
interface DirectSignalSource {

    /** The mode the transport must run in right now. */
    fun mode(): DirectSignalMode

    /**
     * The stage to drive in [DirectSignalMode.PROCESSED], or `null` when the host has no stage.
     *
     * Ignored in [DirectSignalMode.PRISTINE]; the sink never calls it there, so a host cannot
     * accidentally process a stream it has declared pristine.
     */
    fun stage(): DirectPcmStage?

    // AUD-139: there is no host position here on purpose. It was a main-thread cache refreshed only
    // when somebody read it, and a read returned the position of another track from 16 minutes
    // earlier (B7-fix4, `ANCHOR origin=flush-host-position anchor=240020ms`). The sink anchors on what
    // it knows itself — the engine's position, the pipeline's frames, Media3's own stream offset —
    // and the engine moves only on an explicit seek.

    /**
     * The user's own digital gain, as a contributor — **not** the composed value.
     *
     * The stage needs this separately from [replayGainFactor] because both can be active at once,
     * and a set of active processors derived from their product can only ever name one of them.
     * Reading the product and guessing the cause is precisely the attribution error this pair
     * exists to prevent; measured on the HONOR, a stream with user 0.5 × ReplayGain 0.5 reported
     * `REPLAY_GAIN_ACTIVE` alone and never mentioned the user's own volume.
     */
    fun userDigitalGain(): Float = 1f

    /** The ReplayGain factor, or null when ReplayGain is contributing nothing. */
    fun replayGainFactor(): Float? = null

    /**
     * Diagnostics only: the player's own state at the moment of a handoff, or null.
     *
     * Read when a transition is recorded, never when one is decided — nothing in the sink branches
     * on it. It exists because an anchor in the *renderer domain* (near `1e12`) is only
     * distinguishable from a healthy one by what surrounded it: seen at EOF after a process restart
     * it is a stale artefact, seen mid-track during ordinary playback it is a live handoff defect.
     *
     * Implementations must be safe to call from the audio thread: mirror on the player's thread and
     * return the mirror.
     */
    fun handoffContext(): String? = null

    /**
     * DEBUG ONLY — plan B, off by default (B12a). The ExoPlayer pipeline (`enqueueRaw`) is the PRISTINE
     * producer for every format, FLAC included; only when this is true may a PRISTINE local FLAC get
     * the native engine, which is a second player and a second clock and is retired in B12b.
     *
     * It moves the producer and nothing else. [mode] still says PRISTINE, no [stage] is asked for,
     * and the sink reads it only where it decides whether an engine may exist — at engine creation
     * and at a signal-mode realign — so flipping it applies from the next such point (the next
     * track), not to an engine that is already playing. A host that says nothing about it (the
     * default) never has an engine. Implementations must be safe to call from the audio thread and
     * must not do I/O.
     */
    fun debugForceNativeEngine(): Boolean = false

    /**
     * DEBUG ONLY — B12a-4, default 0. Milliseconds of silence queued ahead of an item's first frame after a stream
     * rebuild for a **sample-rate** change (see [RateSwitchPreroll]): one of 0, 250, 500, 1000. Read where the
     * stream is rebuilt, from a value the host cached (no I/O); anything else is treated as 0.
     */
    fun rateSwitchPrerollMs(): Int = 0
}
