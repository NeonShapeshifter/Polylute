package com.decent.usbaudio

/**
 * Why the native decode thread stopped, mirroring the native `EngineExit` (AUD-013/AUD-056).
 *
 * Only [END_OF_STREAM] is the end of the track. The thread used to stop the same way on a USB
 * submit failure, a detach or a read timeout, and the sink read every stop as a natural end: the
 * position jumped to the end and the queue advanced.
 */
enum class NativeEngineExit {
    RUNNING,
    END_OF_STREAM,
    DECODE_ERROR,
    USB_ERROR,
    STOPPED;

    companion object {
        fun fromNative(code: Int): NativeEngineExit = entries.getOrElse(code) { DECODE_ERROR }
    }
}
