package com.decent.usbaudio.media3

import com.decent.usbaudio.RestartOutcome
import com.decent.usbaudio.StreamingInterfaceSequence
import com.decent.usbaudio.UsbDeviceOwner

/**
 * B48-1: the platform half of one stream's DAC recovery. It belongs to the stream of [generation] (fixed when the writer thread was made) and does nothing for any other: the
 * device's [UsbDeviceOwner] decides at [begin] and again before the first device call, and the rate and alt it restarts are those the owner recorded for that generation.
 *
 * Called by [UsbStreamingThread] on its own thread only, so the claim it holds between [begin] and [end] needs no lock of its own.
 */
class OwnedRecoveryHost(
    private val owner: UsbDeviceOwner,
    private val sequence: StreamingInterfaceSequence,
    private val generation: Long,
    private val log: (String) -> Unit = {},
) : UsbStreamingThread.DacRecoveryHost {

    private var claim: UsbDeviceOwner.RecoveryClaim? = null

    override fun streamIsCurrent(): Boolean = owner.generation == generation && owner.facts?.generation == generation

    override fun begin(step: Int): Boolean {
        claim = owner.claimForRecovery(generation, step)
        return claim != null
    }

    override fun restartInterface(step: Int): RestartOutcome {
        val held = claim ?: return RestartOutcome.SUPERSEDED
        val startedAt = System.nanoTime()
        val outcome = try {
            sequence.restart(held)
        } catch (e: RuntimeException) {
            log("DAC recovery step $step threw: ${e.javaClass.simpleName}: ${e.message}")
            RestartOutcome.FAILED
        }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        val facts = held.facts
        log("DAC recovery step $step: interface restart $outcome in $tookMs ms (gen=${facts.generation} rate=${facts.sampleRateHz} alt=${facts.altSetting})")
        DirectPathTelemetry.recordIfCapturing("DAC_RECOVERY") {
            "step=$step outcome=$outcome tookMs=$tookMs gen=${facts.generation} rate=${facts.sampleRateHz} alt=${facts.altSetting}"
        }
        return outcome
    }

    override fun end(step: Int) {
        claim?.close()
        claim = null
    }
}
