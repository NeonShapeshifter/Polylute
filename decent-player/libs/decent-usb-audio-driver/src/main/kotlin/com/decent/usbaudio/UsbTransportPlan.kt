package com.decent.usbaudio

/**
 * B46-A3: the USB transport's numbers for one format on one endpoint, as the native plan computed them
 * (`makeUsbTransportPlan`, usb-pcm-packetizer.h). This class only reads the array the plan came back as: it computes nothing, so
 * the preflight in Kotlin and the writer in C++ cannot disagree about a number.
 *
 * [fields] is indexed by [Field], which mirrors `UsbPlanField` in the header (a test compares the count with the native one and
 * the values with the native tests' own).
 */
class UsbTransportPlan(private val fields: LongArray) {
    /** The index of each number in the plan's array: the order of `UsbPlanField`. */
    object Field {
        const val STATUS = 0
        const val REASON = 1
        const val ASSUMPTIONS = 2
        const val SERVICE_INTERVAL_US = 3
        const val INTERVALS_PER_SECOND = 4
        const val NOMINAL_NUM = 5
        const val NOMINAL_DEN = 6
        const val BYTES_PER_FRAME = 7
        const val MAX_FRAMES_PER_PACKET = 8
        const val MAX_PACKET_BYTES = 9
        const val MAX_FRAMES_AT_CEILING = 10
        const val MAX_PACKET_BYTES_AT_CEILING = 11
        const val PACKETS_PER_URB = 12
        const val URB_MAX_BYTES = 13
        const val URB_SLOT_BYTES = 14
        const val RESIDUAL_CAP_BYTES = 15
        const val RING_URBS = 16
        const val BACKPRESSURE_URBS = 17
        const val LOW_RING_URBS = 18
        const val QUEUE_SOFT_FRAMES = 19
        const val QUEUE_HARD_FRAMES = 20
        const val SILENCE_LEAD_FRAMES = 21
        const val SILENCE_CHUNK_FRAMES = 22
        const val FADE_OUT_FRAMES = 23
        const val FADE_IN_FRAMES = 24
        const val PREROLL_FRAMES = 25
        const val PREROLL_BLOCK_FRAMES = 26
        const val PREROLL_BLOCKS = 27
        const val FEEDBACK_BYTES = 28
        const val RESUME_LEAD_FRAMES = 29   // B47-A3, appended: the indexes before it did not move
        const val FEEDBACK_START_GUARD_MS = 30   // B48-fix1, appended
        const val PAUSE_KEEPALIVE_MS = 31   // B48-fix2, appended
        const val COUNT = 32
    }

    /** Bits of [assumptions]: the timing the plan was made with is not the device's (`USB_PLAN_ASSUME_*`). */
    object Assumption {
        const val SERVICE_INTERVAL = 1
        const val BINTERVAL = 2
        const val FEEDBACK_WIDTH = 4
        const val SPEED_UNKNOWN = 8
    }

    operator fun get(field: Int): Long = fields[field]

    /** The plan as the flat array it came as (the capture reports it). */
    fun toArray(): LongArray = fields.copyOf()

    /** False when the format cannot be carried (or a timing is refused by policy): [reason] says why. */
    val supported: Boolean get() = fields[Field.STATUS] == 0L

    /** A `UsbTransportReason` of the native header; 0 when [supported]. */
    val reason: Int get() = fields[Field.REASON].toInt()

    /** `USB_PLAN_ASSUME_*` bits. */
    val assumptions: Int get() = fields[Field.ASSUMPTIONS].toInt()

    val bytesPerFrame: Int get() = fields[Field.BYTES_PER_FRAME].toInt()
    val maxPacketBytes: Int get() = fields[Field.MAX_PACKET_BYTES].toInt()
    val ringUrbs: Int get() = fields[Field.RING_URBS].toInt()

    /**
     * B48-3: the nominal frames per service interval of this stream (the sample rate over the interval rate, exact as `NOMINAL_NUM / NOMINAL_DEN`): what the feedback's
     * readings are measured against. It is the plan's number, not `rate / 8000`, which is right only for a 125 µs interval. 0 for a plan that is not [supported].
     */
    val nominalFramesPerInterval: Double
        get() = if (supported && fields[Field.NOMINAL_DEN] > 0) fields[Field.NOMINAL_NUM].toDouble() / fields[Field.NOMINAL_DEN] else 0.0

    /** B48-fix2: silence after a pause fade, before the announced idle. */
    val pauseKeepaliveMs: Long get() = fields[Field.PAUSE_KEEPALIVE_MS]

    /** B48-fix1: the optional feedback start guard's length; 0 without a feedback endpoint. */
    val feedbackStartGuardMs: Int get() = fields[Field.FEEDBACK_START_GUARD_MS].toInt()

    /** B47-A3: the silence a resume feeds first when the ring has played out; the plan's, not a constant here. B48-fix1: and before every start of audio. */
    val resumeLeadFrames: Long get() = fields[Field.RESUME_LEAD_FRAMES]

    /**
     * The producer queue's soft limit (the sink tells Media3 to retry) and hard capacity, in frames of this stream's rate: the plan's
     * durations (1440 ms and 11 900 ms, B46-B2), so the headroom is the same at every rate whatever the length of a buffer.
     */
    val queueSoftFrames: Long get() = fields[Field.QUEUE_SOFT_FRAMES]
    val queueHardFrames: Long get() = fields[Field.QUEUE_HARD_FRAMES]

    /** The rate-change pre-roll this plan was asked for: its frames and the blocks they are queued in. */
    val prerollFrames: Long get() = fields[Field.PREROLL_FRAMES]
    val prerollBlockFrames: Int get() = fields[Field.PREROLL_BLOCK_FRAMES].toInt()

    /** The size of each pre-roll block, in order; their sum is [prerollFrames]. */
    fun prerollBlocks(): List<Int> {
        val blocks = ArrayList<Int>()
        var left = prerollFrames
        while (left > 0) {
            val n = minOf(left, prerollBlockFrames.toLong()).toInt()
            blocks += n
            left -= n
        }
        return blocks
    }

    fun reasonName(): String = UsbTransportPlanner.reasonName(reason)

    override fun toString(): String =
        if (supported) "plan(ring=$ringUrbs maxPacket=$maxPacketBytes queue=$queueSoftFrames/$queueHardFrames frames assumptions=0x${assumptions.toString(16)})"
        else "plan(unsupported: ${reasonName()})"
}
