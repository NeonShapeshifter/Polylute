package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.Context

/**
 * What a debug build adds to the app without the main source knowing how (B27-A5). Null in every other build: the debug source set
 * installs [markNoise] from a provider that exists only there, and the More menu shows its row only when it is installed *and*
 * `BuildConfig.DEBUG` (belt and braces: a release build never gets either).
 */
object DebugHooks {
    /**
     * Writes the "I heard noise" file (the last seconds of the USB output's summaries and the stream's state) and returns its name,
     * or null if it could not be written.
     */
    @Volatile
    var markNoise: ((Context) -> String?)? = null
}
