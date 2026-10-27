package com.lostf1sh.pixelplayeross.data.volume

import android.content.Context
import androidx.core.content.edit
import com.lostf1sh.pixelplayeross.data.service.player.AppliedSignalFacts
import kotlin.math.pow

/**
 * B40-P3: what the listener said about a device whose volume Polylute cannot set.
 *
 * USB cannot tell a DAC with its own volume knob from a dongle with no control at all: both lack a UAC volume control. So the app asks
 * once per device, before it plays:
 *
 *  - **It has a knob or buttons** ([hasOwnControl]): Resonance with Polylute's own volume lowered to a safe level (recommended), or Purity,
 *    which plays at the device's full level, so the listener turns the device down first.
 *  - **It has none**: always Resonance, with Polylute's own volume starting low. Purity is not offered for that device.
 *
 * The answer is kept per device and can be changed from the Audio page. The digital volume only exists in Resonance, and nothing here
 * adds gain, resampling or processing to Purity.
 */
enum class DeviceVolumeMode { RESONANCE, PURITY }

data class DeviceVolumeAnswer(val hasOwnControl: Boolean, val mode: DeviceVolumeMode) {
    init {
        require(hasOwnControl || mode == DeviceVolumeMode.RESONANCE) { "a device with no control of any kind has no Purity" }
    }

    /** Purity may be chosen for this device. */
    val purityOffered: Boolean get() = hasOwnControl

    /** Purity is held off while this device is attached: the lock does not apply to it, so the digital volume works. */
    val holdsPurityOff: Boolean get() = mode == DeviceVolumeMode.RESONANCE

    fun encode(): String = when {
        !hasOwnControl -> NO_CONTROL
        mode == DeviceVolumeMode.PURITY -> KNOB_PURITY
        else -> KNOB_RESONANCE
    }

    companion object {
        const val NO_CONTROL = "NO_CONTROL"
        const val KNOB_RESONANCE = "KNOB_RESONANCE"
        const val KNOB_PURITY = "KNOB_PURITY"

        fun decode(raw: String?): DeviceVolumeAnswer? = when (raw) {
            NO_CONTROL -> DeviceVolumeAnswer(hasOwnControl = false, mode = DeviceVolumeMode.RESONANCE)
            KNOB_RESONANCE -> DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.RESONANCE)
            KNOB_PURITY -> DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.PURITY)
            else -> null
        }

        val noControl = DeviceVolumeAnswer(hasOwnControl = false, mode = DeviceVolumeMode.RESONANCE)
    }
}

object DeviceVolumePolicy {
    /**
     * Where Polylute's own volume starts for a device it cannot lower: the level the app already treats as safe to start at
     * ([HandoffResumeSafety.CONSERVATIVE_RESUME_DB]), one number for "a safe starting level" and not a new unit of risk.
     */
    const val SAFE_START_DB = HandoffResumeSafety.CONSERVATIVE_RESUME_DB

    /** [SAFE_START_DB] as the digital gain the player takes (linear, 0..1). */
    val safeStartGain: Float = 10.0.pow(SAFE_START_DB / 20.0).toFloat()

    /**
     * Due when the device is *known* to have no volume the app can set (unknown is not an answer: nothing is held on a guess) and the
     * listener has not answered for it yet.
     */
    fun due(hasVolumeControl: Boolean?, answer: DeviceVolumeAnswer?): Boolean = hasVolumeControl == false && answer == null

    /**
     * The Pure Direct lock as the signal path reads it: the listener's lock, except where the attached device holds Purity off (an answer
     * of Resonance, or "no control at all"). Only ever turns Purity *off*: it adds nothing to a Purity path.
     */
    fun effectiveLock(persistedLock: Boolean, heldOffByDevice: Boolean): Boolean = persistedLock && !heldOffByDevice

    /** The digital gain to start at: the safe one, never above what the listener already has (it only ever lowers). */
    fun startGain(current: Float): Float = minOf(current, safeStartGain)

    /**
     * How long a held play waits for [protectionInEffect] after an answer (B40-fix1). The chain it waits on is a handful of main-thread
     * hops, so this is generous; past it the play is not started and the listener is told.
     */
    const val PROTECTION_BOUND_MS = 3_000L

    /** A level this close to the safe start counts as at it (float round trips through the setter). */
    private const val GAIN_EPSILON = 1e-4f

    /**
     * B40-fix1 (P3): the protection a "no control" or "knob, Resonance" answer stands for is **in effect**: the signal path has been told
     * the listening state, the Purity lock no longer applies to it, the digital volume is part of it (a gain stage is in the signal, not
     * left out as pristine) and that volume is at or below the safe start. Only then may the play the question held start. The lock flag
     * alone does not say it, nor does the request to lower the gain: both travel through collectors and the main looper.
     */
    fun protectionInEffect(signal: AppliedSignalFacts, userDigitalGain: Float): Boolean =
        signal.known && !signal.lockInForce && signal.digitalVolume && userDigitalGain <= safeStartGain + GAIN_EPSILON
}

/**
 * The answers, one per device. [read] and [write] are the storage (per device key, kept across launches), so the rule is the same one in
 * the app and in a test.
 */
class DeviceVolumeLedger(
    private val read: (deviceKey: String) -> String?,
    private val write: (deviceKey: String, encoded: String?) -> Unit,
) {
    fun answerFor(deviceKey: String): DeviceVolumeAnswer? = DeviceVolumeAnswer.decode(read(deviceKey))

    fun due(deviceKey: String, hasVolumeControl: Boolean?): Boolean = DeviceVolumePolicy.due(hasVolumeControl, answerFor(deviceKey))

    fun record(deviceKey: String, answer: DeviceVolumeAnswer) = write(deviceKey, answer.encode())

    /** Forgets the answer, so the question is asked again at the next play (the Audio page's way to change it). */
    fun forget(deviceKey: String) = write(deviceKey, null)

    companion object {
        /** A new file: the B39 notice kept a plain "told" flag per device, which is not an answer to this question. */
        const val PREFS = "dac_volume_answers"

        fun persistent(context: Context): DeviceVolumeLedger {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DeviceVolumeLedger(
                read = { prefs.getString(it, null) },
                write = { key, value -> prefs.edit { if (value == null) remove(key) else putString(key, value) } },
            )
        }
    }
}
