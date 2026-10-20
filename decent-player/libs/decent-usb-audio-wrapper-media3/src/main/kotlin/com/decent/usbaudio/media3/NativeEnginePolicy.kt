package com.decent.usbaudio.media3

/**
 * Whether the native FLAC engine may own decode for the current stream.
 *
 * Since B12a the engine is **plan B, not the default**: the ExoPlayer pipeline (`enqueueRaw`) is the
 * PRISTINE producer for every format, FLAC included, because Media3 is the one player and the one
 * clock and only the USB output is ours. The engine exists only while the host asks for it with
 * [debugForceNativeEngine] (`DirectSignalSource.debugForceNativeEngine`, a persisted debug switch;
 * release builds can never turn it on).
 *
 * When it is asked for, the old rules still hold: the engine decodes straight into the USB URBs and
 * never lets the application see a sample, so it is by definition a pristine-only path; and it
 * cannot downmix or upmix, because it writes source frames as-is.
 *
 * It can only ever be *denied* by what it used to be denied by, never allowed by something new:
 * with [debugForceNativeEngine] true the result is exactly the pre-B12a expression, for every input;
 * with it false (the default) it is false for every input.
 */
object NativeEnginePolicy {
    fun allowed(
        mode: DirectSignalMode,
        downmixSourceChannels: Int,
        upmixMonoSource: Boolean,
        debugForceNativeEngine: Boolean,
    ): Boolean =
        debugForceNativeEngine &&
            mode == DirectSignalMode.PRISTINE && downmixSourceChannels == 0 && !upmixMonoSource
}
