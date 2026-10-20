package com.decent.usbaudio.media3

import com.decent.usbaudio.NativeEngineExit

/** What the sink does when the native engine's decode thread is found stopped (AUD-013/AUD-056). */
object NativeEngineEndPolicy {
    enum class Action {
        /** Still running, or stopped on request: nothing to resolve. */
        NONE,
        /** The track ended: advance, repeat or stop at the end as Media3 would. */
        NATURAL_END,
        /** The file could not be read or decoded further: pause where it stopped, no advance. */
        PAUSE_AT_POSITION,
        /** The USB stream failed (detach, submit/reap error): Direct failed, pause, no advance. */
        FAIL_DIRECT,
    }

    fun resolve(exit: NativeEngineExit): Action = when (exit) {
        NativeEngineExit.END_OF_STREAM -> Action.NATURAL_END
        NativeEngineExit.USB_ERROR -> Action.FAIL_DIRECT
        NativeEngineExit.DECODE_ERROR -> Action.PAUSE_AT_POSITION
        NativeEngineExit.RUNNING, NativeEngineExit.STOPPED -> Action.NONE
    }

    /** Only a real end of stream may report the renderer ended. */
    fun reportsEnded(exit: NativeEngineExit): Boolean = exit == NativeEngineExit.END_OF_STREAM
}
