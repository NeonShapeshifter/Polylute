package com.decent.usbaudio

/**
 * B46-A3: asks the native transport plan (`makeUsbTransportPlan`) whether, and how, a format goes over an endpoint.
 *
 * The same function plans the writer's ring and its thresholds when the stream is created, so a format this says yes to is the
 * format the writer will not refuse, and the numbers Kotlin reads (queue limits, pre-roll) are the ones the writer runs with. There
 * is no second implementation here and no fallback: if the native library is missing the first call fails loudly (the JVM tests
 * load a host build of it, see `buildHostTransportPlanLib`).
 */
object UsbTransportPlanner {
    /** `USB_TRANSPORT_SPEED_UNKNOWN`: a preflight that works from descriptors cannot read the bus speed. */
    const val BUS_SPEED_UNKNOWN = 0

    /** The rate a queue limit defaults to when there is no stream: the reference of the duration limits (44.1 kHz). */
    const val DEFAULT_QUEUE_RATE_HZ = 44_100

    init {
        System.loadLibrary("decent_usb_audio")
        check(nativeFieldCount() == UsbTransportPlan.Field.COUNT) {
            "the native plan has ${nativeFieldCount()} fields, UsbTransportPlan.Field lists ${UsbTransportPlan.Field.COUNT}"
        }
    }

    /**
     * The plan of [alt] for [sampleRateHz] x [channels]. [sourceBits] is the source's significant bits, or null for a float
     * (processed) source that any container takes. [prerollMs] is the requested rate-change pre-roll, 0 for none.
     */
    fun plan(
        alt: UacStreamingAlt,
        sampleRateHz: Int,
        channels: Int,
        sourceBits: Int?,
        prerollMs: Int = 0,
        busSpeed: Int = BUS_SPEED_UNKNOWN,
    ): UsbTransportPlan = plan(
        sampleRateHz = sampleRateHz,
        channels = channels,
        containerBytes = alt.subslotSize,
        sourceBits = sourceBits,
        maxPacketBytes = alt.maxPacketBytes,
        bInterval = alt.bInterval,
        hasFeedback = alt.feedbackEndpointAddress > 0,
        feedbackBytes = alt.feedbackMaxPacketBytes,
        prerollMs = prerollMs,
        busSpeed = busSpeed,
    )

    fun plan(
        sampleRateHz: Int,
        channels: Int,
        containerBytes: Int,
        sourceBits: Int?,
        maxPacketBytes: Int,
        bInterval: Int,
        hasFeedback: Boolean,
        feedbackBytes: Int,
        prerollMs: Int = 0,
        busSpeed: Int = BUS_SPEED_UNKNOWN,
    ): UsbTransportPlan = UsbTransportPlan(
        nativePlan(
            sampleRateHz, channels, containerBytes, sourceBits ?: 0, busSpeed, maxPacketBytes, bInterval, hasFeedback, feedbackBytes,
            prerollMs,
        ),
    )

    /**
     * `[soft, hard]` producer queue limits, in frames, of the default policy at [sampleRateHz]: for a thread made before any stream's
     * plan exists (a unit test; the sink hands its stream's plan over instead).
     */
    fun defaultQueueFrames(sampleRateHz: Int = DEFAULT_QUEUE_RATE_HZ): Pair<Long, Long> =
        nativeDefaultQueueFrames(sampleRateHz).let { it[0] to it[1] }

    fun reasonName(reason: Int): String = nativeReasonName(reason)

    @JvmStatic
    private external fun nativePlan(
        sampleRate: Int, channels: Int, containerBytes: Int, sourceBits: Int, busSpeed: Int, maxPacketBytes: Int, bInterval: Int,
        hasFeedback: Boolean, feedbackBytes: Int, prerollMs: Int,
    ): LongArray

    @JvmStatic
    private external fun nativeFieldCount(): Int

    @JvmStatic
    private external fun nativeReasonName(reason: Int): String

    @JvmStatic
    private external fun nativeDefaultQueueFrames(sampleRate: Int): LongArray
}
