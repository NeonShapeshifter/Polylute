package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.content.SharedPreferences
import com.lostf1sh.pixelplayeross.BuildConfig

/** Where a debug flag lives between processes; a seam so the switch is testable without a Context. */
interface DebugFlagStore {
    fun read(): Boolean
    fun write(value: Boolean)
}

/**
 * DEBUG ONLY — plan B behind `SET_FORCE_NATIVE_ENGINE` (debug bridge), B12a.
 *
 * **The ExoPlayer pipeline (`enqueueRaw`) is the PRISTINE producer for every format, FLAC included, in
 * debug and in release.** Media3 is the player, the clock and the decoding; only the USB output is ours.
 * The native FLAC engine is a second player and a second clock: it is reachable only while this switch
 * is on, as a way back if the single-clock path ever misbehaves, and it is deleted in B12b.
 *
 * - **Off by default**, and off unless it was explicitly turned on: nothing is written when it is only
 *   read. With it off the engine is never created (`NativeEnginePolicy.allowed` is false for every
 *   input), so none of the glue that exists for it has anything to act on.
 * - **Persisted**, so it survives a process restart while someone compares the producers. That is also
 *   its risk — left on, the daily driver is back on two clocks — which is why every capture records
 *   whether it was on (`pipeline.debugForcedNativeEngine`, the `PRODUCER` event) and the bridge reports it.
 * - **Inert outside a debug build**: [debugBuild] false ignores a stored value and refuses to write, so
 *   no release path can create the engine.
 * - **Read from the audio thread as a cached field.** The store is read once, when the switch is built,
 *   never per call.
 *
 * ### The retired switch (`force_exoplayer_pipeline`, `SET_PRISTINE_EXOPLAYER_PIPELINE`)
 * Before B12a the persisted switch meant the opposite: `true` took the engine away from a PRISTINE FLAC.
 * That is now the default, so a stored `true` is already the behaviour and needs no migration. A stored
 * `false` meant "the engine as usual", **not** "force the engine", so it must not turn plan B on. Both
 * values are therefore left exactly where they are and never read: this switch lives in its own file
 * ([PREFERENCES]) under its own key ([KEY]), and [RETIRED_PREFERENCES] / [RETIRED_KEY] exist only so a
 * test can pin that nothing reads them.
 *
 * Only the debug receiver writes it (`NativeEngineSwitchBoundaryTest` pins that).
 */
class NativeEngineDebugSwitch(
    private val store: DebugFlagStore,
    private val debugBuild: Boolean,
) {
    @Volatile
    private var forced: Boolean = debugBuild && store.read()

    fun isForced(): Boolean = forced

    fun set(enabled: Boolean) {
        if (!debugBuild) return
        store.write(enabled)
        forced = enabled
    }

    companion object {
        const val PREFERENCES = "debug_native_engine"
        const val KEY = "force_native_engine"

        /** The pre-B12a switch's file and key. Never read, never written: see the class comment. */
        const val RETIRED_PREFERENCES = "debug_pristine_pipeline"
        const val RETIRED_KEY = "force_exoplayer_pipeline"

        @Volatile
        private var shared: NativeEngineDebugSwitch? = null

        /**
         * The process-wide switch, so the receiver's write is the value the sink's next read sees.
         * The first call loads the preference file: call it off the audio thread.
         */
        fun shared(context: Context): NativeEngineDebugSwitch =
            shared ?: synchronized(this) {
                shared ?: NativeEngineDebugSwitch(
                    PreferencesFlagStore(
                        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
                    ),
                    BuildConfig.DEBUG,
                ).also { shared = it }
            }
    }

    /** The persisted flag: [KEY] in whatever preferences it is given. Reads no other key. */
    class PreferencesFlagStore(private val preferences: SharedPreferences) : DebugFlagStore {
        override fun read(): Boolean = preferences.getBoolean(KEY, false)

        // commit, not apply: a shell that force-stops right after the broadcast must not lose it.
        override fun write(value: Boolean) {
            preferences.edit().putBoolean(KEY, value).commit()
        }
    }
}
