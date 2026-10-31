package com.decent.usbaudio

/**
 * What the USB output does with the DAC's async feedback (B10, AUD-124 "shh"): the ids are the native
 * `UsbFeedbackPolicy`. The default is [AUTO] since B12a-2, in debug and in release; the debug bridge can override it
 * for the life of the process (`follow` to diagnose), never persisted. Pure Kotlin, so the bridge, the capture and
 * the unit tests share the names without touching the native library.
 */
object FeedbackPolicy {
    /** Send what the DAC asks. What the output did before B12a; the override to diagnose with. */
    const val FOLLOW = 0

    /** Ignore the feedback: packets at the nominal rate. */
    const val NOMINAL = 1

    /** What the DAC asks, within +-300 ppm of nominal. */
    const val CLAMP = 2

    /**
     * The default. Follow, except while the feedback is stuck or implausible (beyond +-2 % at any moment, or beyond
     * +-0.5 % for 5 s): then the last healthy average (nominal if there was none).
     */
    const val AUTO = 3

    /**
     * B46-C2, opt-in, never the default: [AUTO], but a plateau of 0.5 % or more is called stuck after 3.5 s instead of 5 s. It can only
     * stop following a reading sooner than [AUTO] would, never follow one [AUTO] would refuse. Only the debug bridge selects it.
     */
    const val AUTO_EARLY = 4

    /** The id for a `--es mode` value, or null for anything that is not a policy (so a typo changes nothing). */
    fun parse(name: String?): Int? = when (name) {
        "follow" -> FOLLOW
        "nominal" -> NOMINAL
        "clamp" -> CLAMP
        "auto" -> AUTO
        "auto-early" -> AUTO_EARLY
        else -> null
    }

    fun name(id: Long?): String = when (id) {
        FOLLOW.toLong() -> "follow"
        NOMINAL.toLong() -> "nominal"
        CLAMP.toLong() -> "clamp"
        AUTO.toLong() -> "auto"
        AUTO_EARLY.toLong() -> "auto-early"
        else -> "?"
    }

    /** Fpmf values cross JNI as millionths of a frame per microframe. */
    fun fpmf(micro: Long?): Double? = micro?.let { it / 1_000_000.0 }
}
