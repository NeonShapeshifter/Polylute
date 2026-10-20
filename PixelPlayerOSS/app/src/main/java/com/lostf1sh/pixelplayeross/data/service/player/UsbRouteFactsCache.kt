package com.lostf1sh.pixelplayeross.data.service.player

/** What an evaluation hands back to the cache to judge: the decision and whether acting on it would rebuild the player. */
internal interface RouteStep {
    val decision: AudioRouteDecision
    val rebuildNeeded: Boolean
}

/** What one route evaluation reads off the USB bus: the DAC that is there (if any) and whether the app may open it. */
internal class UsbRouteFacts<D : Any>(val device: D?, val permission: Boolean)

/**
 * The DAC facts of the last **live** probe, kept for the evaluations a new song causes (B23-P4).
 *
 * Every source change posts a route evaluation, and each one used to enumerate the USB devices and ask for the permission
 * (Binder calls, a descriptor walk and a log line each) on the main thread: 8–56 ms measured, on every song. Nothing about the
 * hardware changes between two songs, and the events that do change it (an attach or detach, a permission answer, a claim
 * that fails, a driver handed back, a listening change) all run their own evaluation, which probes live and replaces what is
 * held here.
 *
 * ## A stale fact never causes a handoff or a rebuild
 *
 * Held facts may only **confirm** the route the last live evaluation chose ([RouteFactsReuse.mayActOn]). Any evaluation that
 * would change something (a different decision, a rebuild, a handoff in flight, the Android-route guard holding playback) is
 * discarded and decided again from a live probe before it is acted on, so the only thing a stale fact can do is let a route
 * that was already right go on being right for the rest of the song. Failed-claim, permission and unplug handling are
 * untouched: they are the triggers that always probe live.
 */
internal class UsbRouteFactsCache<D : Any>(private val probe: () -> UsbRouteFacts<D>) {
    /** How many probes went to the bus, and how many evaluations were served from what was held (for the trace and the tests). */
    var liveProbes = 0L
        private set
    var reusedEvaluations = 0L
        private set
    var discardedReuses = 0L
        private set

    private var held: UsbRouteFacts<D>? = null

    /** A fresh probe; it replaces whatever was held. */
    fun live(): UsbRouteFacts<D> {
        liveProbes++
        return probe().also { held = it }
    }

    /**
     * One route evaluation. [decide] turns facts into a decision and says whether it would rebuild; it acts on nothing, so it may
     * run twice. The held facts are used only for a trigger that says nothing about the hardware, and their answer is acted on
     * only when it confirms the route the last live evaluation chose ([RouteFactsReuse.mayActOn]); otherwise the facts are
     * dropped and [decide] runs again on a live probe. The caller records [Evaluated.step]'s decision as the last live one when
     * [Evaluated.reused] is false.
     */
    fun <S : RouteStep> evaluate(
        trigger: String,
        lastLiveDecision: AudioRouteDecision?,
        handoffInFlight: Boolean,
        androidRouteGuardHolding: Boolean,
        decide: (UsbRouteFacts<D>) -> S,
    ): Evaluated<S> {
        val heldFacts = if (RouteFactsReuse.triggerMayReuse(trigger)) held else null
        if (heldFacts != null) {
            val step = decide(heldFacts)
            if (RouteFactsReuse.mayActOn(step.decision, lastLiveDecision, step.rebuildNeeded, handoffInFlight, androidRouteGuardHolding)) {
                reusedEvaluations++
                return Evaluated(step, reused = true)
            }
            discardedReuses++
            held = null
        }
        return Evaluated(decide(live()), reused = false)
    }

    /** A decision taken from [step], and whether it came from held facts. */
    class Evaluated<S : RouteStep>(val step: S, val reused: Boolean)

    /** A hardware event the engine knows about (a diagnostics refresh runs its own live probe). */
    fun invalidate() {
        held = null
    }
}

internal object RouteFactsReuse {
    /** The two evaluations a new song causes; nothing in them is about the USB hardware. */
    const val SOURCE_CHANGED = "Source changed"
    const val SOURCE_FORMAT_CHANGED = "Source format changed"

    fun triggerMayReuse(trigger: String): Boolean = trigger == SOURCE_CHANGED || trigger == SOURCE_FORMAT_CHANGED

    /**
     * Whether an evaluation decided from held facts may be acted on. Only a steady Direct route qualifies: the decision is
     * the one the last live evaluation took, nothing is to be rebuilt, and no handoff or Android-route hold is in flight.
     * Every other case is decided again from a live probe, so an unplug, a revoked permission or a changed capability is
     * always seen by a live read before the engine pauses, hands off or rebuilds.
     */
    fun mayActOn(
        decision: AudioRouteDecision,
        lastLiveDecision: AudioRouteDecision?,
        rebuildNeeded: Boolean,
        handoffInFlight: Boolean,
        androidRouteGuardHolding: Boolean,
    ): Boolean = decision.backend == AudioBackend.USB_DIRECT &&
        decision == lastLiveDecision &&
        !rebuildNeeded &&
        !handoffInFlight &&
        !androidRouteGuardHolding
}
