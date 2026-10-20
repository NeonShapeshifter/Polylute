package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B23-P4: every source change posted a route evaluation that enumerated the USB devices, asked for the permission and, even with
 * Direct decided, read `/proc/asound` and the platform's outputs: 8-56 ms on the main thread, per song.
 *
 * Held facts may only **confirm** the route the last live evaluation chose. A change is always decided from a live probe, so a
 * stale fact can never start a handoff or a rebuild.
 */
class UsbRouteFactsCacheTest {

    /** A bus the test can unplug. */
    private class Bus {
        var device: String? = "FiiO SNOWSKY Melody"
        var permission = true
        var probes = 0
    }

    private class Step(
        override val decision: AudioRouteDecision,
        override val rebuildNeeded: Boolean,
        val seenDevice: String?,
    ) : RouteStep

    private val direct = AudioRouteDecision(AudioBackend.USB_DIRECT, AudioRouteReason.DIRECT_PRISTINE)
    private val noDac = AudioRouteDecision(AudioBackend.ANDROID_AUDIO, AudioRouteReason.NO_USB_DAC)

    /** The engine's loop, reduced to what the cache judges: the decision is Direct when a permitted DAC is in the facts. */
    private class Engine(val bus: Bus, val cache: UsbRouteFactsCache<String>, val direct: AudioRouteDecision, val noDac: AudioRouteDecision) {
        var lastLive: AudioRouteDecision? = null
        var handoffInFlight = false
        var guardHolding = false
        var rebuildWhenDirectIs: Boolean? = null     // set to simulate "the player was built Android-only and must be rebuilt for Direct"
        val acted = mutableListOf<String>()

        fun evaluate(trigger: String): UsbRouteFactsCache.Evaluated<Step> {
            val evaluated = cache.evaluate(trigger, lastLive, handoffInFlight, guardHolding) { facts ->
                val decision = if (facts.device != null && facts.permission) direct else noDac
                Step(decision, rebuildNeeded = decision == direct && rebuildWhenDirectIs == true, seenDevice = facts.device)
            }
            if (!evaluated.reused) lastLive = evaluated.step.decision
            acted += "${trigger}:${evaluated.step.decision.reason}:${if (evaluated.reused) "reused" else "live"}"
            return evaluated
        }
    }

    private fun engine(bus: Bus = Bus()): Engine {
        val cache = UsbRouteFactsCache {
            bus.probes++
            UsbRouteFacts(bus.device, bus.device != null && bus.permission)
        }
        return Engine(bus, cache, direct, noDac)
    }

    @Test
    fun `route evaluation with unchanged facts does no probe`() {
        val e = engine()
        e.evaluate("USB availability changed")            // the refresh at start-up: live
        assertThat(e.bus.probes).isEqualTo(1)
        repeat(20) { e.evaluate(RouteFactsReuse.SOURCE_CHANGED) }
        repeat(5) { e.evaluate(RouteFactsReuse.SOURCE_FORMAT_CHANGED) }
        assertThat(e.bus.probes).isEqualTo(1)             // twenty-five songs, no read of the bus
        assertThat(e.cache.reusedEvaluations).isEqualTo(25L)
        assertThat(e.acted.drop(1).all { it.endsWith("DIRECT_PRISTINE:reused") }).isTrue()
    }

    @Test
    fun `with a detach the next evaluation does probe, and the route follows what the bus says`() {
        val e = engine()
        e.evaluate("USB availability changed")
        e.evaluate(RouteFactsReuse.SOURCE_CHANGED)
        assertThat(e.bus.probes).isEqualTo(1)
        // The unplug: the engine's diagnostics refresh (what a detach, an attach and a permission answer end in) drops what is held
        // and evaluates live.
        e.bus.device = null
        e.cache.invalidate()
        val after = e.evaluate("USB availability changed")
        assertThat(e.bus.probes).isEqualTo(2)
        assertThat(after.reused).isFalse()
        assertThat(after.step.decision).isEqualTo(noDac)
        // and a song change on the new facts is live too (nothing held in between), then held again
        assertThat(e.evaluate(RouteFactsReuse.SOURCE_CHANGED).step.decision).isEqualTo(noDac)
    }

    @Test
    fun `a stale fact never starts a rebuild or a handoff, whatever would change is decided again from a live probe`() {
        val e = engine()
        e.evaluate("USB availability changed")
        // The DAC is gone and nobody told the engine; the held facts still say it is there, and the player is waiting for a rebuild
        // for Direct. Acting on the held facts would rebuild a Direct player for a DAC that is not there.
        e.bus.device = null
        e.rebuildWhenDirectIs = true
        val evaluated = e.evaluate(RouteFactsReuse.SOURCE_CHANGED)
        assertThat(evaluated.reused).isFalse()
        assertThat(evaluated.step.decision).isEqualTo(noDac)
        assertThat(evaluated.step.rebuildNeeded).isFalse()
        assertThat(e.cache.discardedReuses).isEqualTo(1L)
        assertThat(e.bus.probes).isEqualTo(2)
    }

    @Test
    fun `a handoff in flight, the Android-route hold and a changed decision all go to a live probe`() {
        val e = engine()
        e.evaluate("USB availability changed")
        e.handoffInFlight = true
        assertThat(e.evaluate(RouteFactsReuse.SOURCE_CHANGED).reused).isFalse()
        e.handoffInFlight = false
        e.guardHolding = true
        assertThat(e.evaluate(RouteFactsReuse.SOURCE_CHANGED).reused).isFalse()
        e.guardHolding = false
        // the last live decision was something else (the route changed since): not a confirmation
        e.lastLive = noDac
        assertThat(e.evaluate(RouteFactsReuse.SOURCE_CHANGED).reused).isFalse()
        // settled again: held facts serve
        assertThat(e.evaluate(RouteFactsReuse.SOURCE_CHANGED).reused).isTrue()
    }

    @Test
    fun `only the two per-song triggers may reuse, every hardware, claim, permission and requirement trigger probes live`() {
        val e = engine()
        e.evaluate("USB availability changed")
        val before = e.bus.probes
        for (trigger in listOf(
            "USB availability changed", "USB playback probe", "USB drivers rebound", "Audio outputs changed",
            "Direct claim failed", "Listening requirements changed", "Maintenance override changed", "Playback paused",
            "Source changed: deferred route reevaluation",
        )) {
            assertThat(RouteFactsReuse.triggerMayReuse(trigger)).isFalse()
        }
        for (trigger in listOf("USB availability changed", "Direct claim failed", "Listening requirements changed")) e.evaluate(trigger)
        assertThat(e.bus.probes).isEqualTo(before + 3)
        assertThat(RouteFactsReuse.triggerMayReuse("Source changed")).isTrue()
        assertThat(RouteFactsReuse.triggerMayReuse("Source format changed")).isTrue()
    }

    @Test
    fun `only a steady Direct route is confirmed`() {
        fun may(d: AudioRouteDecision, last: AudioRouteDecision?, rebuild: Boolean = false, handoff: Boolean = false, hold: Boolean = false) =
            RouteFactsReuse.mayActOn(d, last, rebuild, handoff, hold)
        assertThat(may(direct, direct)).isTrue()
        assertThat(may(direct, null)).isFalse()
        assertThat(may(direct, noDac)).isFalse()
        assertThat(may(noDac, noDac)).isFalse()           // an Android decision needs the live Android-route probe and the guard
        assertThat(may(direct, direct, rebuild = true)).isFalse()
        assertThat(may(direct, direct, handoff = true)).isFalse()
        assertThat(may(direct, direct, hold = true)).isFalse()
    }

    @Test
    fun `the Android route fact is read only when the decision needs it`() {
        var reads = 0
        val dac = AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true)
        // Direct: never asked
        val d = AudioRoutePolicy.decide(dac) { reads++; false }
        assertThat(d.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(reads).isEqualTo(0)
        // Android with a DAC (crossfade): asked, and a missing platform output blocks it, exactly as the eager rule said
        val crossfade = dac.copy(processing = AudioProcessingRequirements(crossfade = true))
        val blocked = AudioRoutePolicy.decide(crossfade) { reads++; false }
        assertThat(reads).isEqualTo(1)
        assertThat(blocked.androidOutputBlocked).isTrue()
        assertThat(blocked).isEqualTo(AudioRoutePolicy.decide(crossfade.copy(androidUsbRouteAvailable = false)))
        // Android with no DAC: nothing to play to, so nothing to ask
        val none = AudioRoutePolicy.decide(AudioRouteFacts(usbDacPresent = false, usbPermissionGranted = false)) { reads++; false }
        assertThat(reads).isEqualTo(1)
        assertThat(none.androidOutputBlocked).isFalse()
    }
}
