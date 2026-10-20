package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.volume.DefaultDirectDuckHost
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.volume.TemporaryWrite
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** The audio-focus callbacks the Direct duck cares about. */
enum class DuckFocusEvent { LOSS, LOSS_TRANSIENT, CAN_DUCK, GAIN }

/**
 * B15-F4: which focus event the engine is on. Every focus callback takes the next number, on the thread that
 * receives it (the main thread); a pause the duck decided for one event carries that number to the main thread
 * and is dropped there if a newer event has arrived in between, whichever order the two got there in.
 *
 * Without it a `GAIN` could overtake the duck's pause: the engine handled GAIN first (nothing to resume yet), then
 * the pause that had been posted for the duck landed, and playback stayed paused with no GAIN left to end it.
 */
class FocusSequence {
    private var current = 0L

    /** A focus event has arrived: its number. Main thread, at the top of the focus callback. */
    fun next(): Long = ++current

    /** Whether no newer focus event than [generation] has arrived. Main thread. */
    fun isCurrent(generation: Long): Boolean = generation == current
}

/** What [DirectDuckController] needs from the rest of the app (B14-3). */
interface DirectDuckHost {
    fun directActive(): Boolean
    fun signalMode(): DirectSignalMode

    /**
     * The DAC's hardware volume in steps, with a read-back of the DAC itself (a blocking control transfer), and the
     * ticket ([PristineModePolicy.HardwareVolume.ticket]) a temporary writer must present from then on.
     */
    fun hardwareVolume(): PristineModePolicy.HardwareVolume?

    /**
     * The Safety Max ceiling in DAC steps when one is in force, now: read again at every step of a ramp. B16-G2: this is
     * the controller's *plan*; the authority is the one [setHardwareStepTemporarily] evaluates inside its compare-and-set.
     */
    fun ceilingStep(): Int?

    /**
     * One step of a duck's ramp: a compare-and-set against every other writer of the DAC's volume. [ticket] is what
     * [hardwareVolume] returned and [expectedStep] where the duck last left the DAC; if the listener or Safety has
     * written since, or the DAC is not at [expectedStep] (or not the same hardware backend), nothing is written and the
     * answer is [TemporaryWrite.SUPERSEDED]. B16-G2: a step that raises the DAC above the Safety Max ceiling *as it is when
     * the step is written* (the host reads it inside the compare-and-set, under the lock the write is under) is not
     * written either: [TemporaryWrite.ABOVE_CEILING]. Blocking. A temporary level is never remembered as the listener's
     * choice, which is what the DAC would otherwise be opened at next time.
     */
    fun setHardwareStepTemporarily(step: Int, ticket: Long, expectedStep: Int): TemporaryWrite

    /** The PROCESSED stage's duck target (1 = none); the stage ramps toward it. */
    fun setDigitalDuckGain(gain: Float)

    /**
     * Pauses as a transient focus loss does, so the next gain resumes. [generation] is the focus event the pause was
     * decided for ([FocusSequence]): the engine drops it if a newer one has arrived by the time it runs.
     */
    fun pauseForDuck(generation: Long)
}

/**
 * Attenuates USB Direct while another app asks for "may duck" focus, and gives the level back exactly (B14-3,
 * audit A-05). The decision of how is [DirectDuckPolicy]'s; this runs it, in order, off the main thread.
 *
 * One consumer takes the events in the order they came, so a gain that arrives while the duck is still ramping
 * down is served after it, never interleaved with it. Each hardware step is a blocking USB control transfer
 * (up to 250 ms on a bad DAC), hence the thread. A gain (or a loss) that is already queued stops the ramp down
 * where it stands, so the way back starts from the level the DAC really has.
 *
 * The invariants the tests pin:
 * - a duck never raises anything: every write is at or below the level the duck began at;
 * - **one owner of the DAC's volume** (B15-F1): every step of either ramp is a compare-and-set
 *   ([DirectDuckHost.setHardwareStepTemporarily]). If the listener or Safety wrote the volume since the duck read it,
 *   or the DAC is not where the duck left it, the step is refused without writing and the duck is over for good:
 *   the listener's choice stands and no later restore moves it;
 * - the way back is the level before the duck, capped at **every step** by the Safety Max ceiling read again then
 *   (it can drop, an override can end) and by the DAC's maximum; it also stops when Direct is no longer the route.
 *   B16-G2: and the cap is enforced where it cannot go stale: the compare-and-set judges the step against the ceiling at
 *   the moment of the write, so a ceiling that changes between this controller's reading and the write (a Safety Max
 *   set at the level the DAC is already at writes nothing, so nothing else would notice) refuses the step instead of
 *   passing it;
 * - Purity never reaches the samples: the digital route is only taken when the signal is already PROCESSED;
 * - any end of the duck (gain, or a loss that pauses anyway) leaves no attenuation behind;
 * - a pause the duck decides carries the focus event it was decided for, so it cannot land after the gain that
 *   ended it (B15-F4, [FocusSequence]).
 */
@Singleton
class DirectDuckController internal constructor(
    private val host: DirectDuckHost,
    scope: CoroutineScope,
    private val downStepMs: Long,
    private val upStepMs: Long,
) {
    @Inject
    constructor(host: DefaultDirectDuckHost, @com.lostf1sh.pixelplayeross.di.AppScope scope: CoroutineScope) :
        this(host as DirectDuckHost, scope, DOWN_STEP_MS, UP_STEP_MS)

    private sealed interface Ducked {
        data object None : Ducked

        /** [generation] is the focus event that began the duck. */
        data class Digital(val generation: Long) : Ducked
        data class Paused(val generation: Long) : Ducked

        /**
         * [preDuck] is where the DAC was; [written] the last step this duck put there; [ticket] what the DAC's
         * volume owner handed out when the duck read it.
         */
        data class Hardware(
            val preDuck: Int,
            var written: Int,
            val maxStep: Int,
            val ticket: Long,
            val generation: Long,
        ) : Ducked
    }

    private var state: Ducked = Ducked.None

    private sealed interface Command {
        data class Focus(val event: DuckFocusEvent, val generation: Long) : Command
        data object RouteChanged : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Gains and losses queued or being served: while one is, a ramp down gives way (and a duck that has not begun does not). */
    private val endsPending = AtomicInteger(0)

    init {
        scope.launch {
            for (command in commands) {
                runCatching {
                    when (command) {
                        is Command.Focus -> when (command.event) {
                            DuckFocusEvent.CAN_DUCK -> begin(command.generation)
                            DuckFocusEvent.GAIN, DuckFocusEvent.LOSS, DuckFocusEvent.LOSS_TRANSIENT -> end()
                        }
                        Command.RouteChanged -> routeChanged()
                    }
                }.onFailure { Timber.tag(TAG).w(it, "duck %s failed", command) }
                if (command is Command.Focus && command.event != DuckFocusEvent.CAN_DUCK) endsPending.decrementAndGet()
            }
        }
    }

    /**
     * Called by the engine for every focus callback, on whichever thread the platform delivers it. [generation] is the
     * number [FocusSequence] gave the event.
     */
    fun onFocusEvent(event: DuckFocusEvent, generation: Long) {
        if (event != DuckFocusEvent.CAN_DUCK) endsPending.incrementAndGet()
        commands.trySend(Command.Focus(event, generation))
    }

    /**
     * The route changed under a duck. A digital duck dies with the stage that carried it (the signal went
     * PRISTINE, or Direct ended), so the level is not left to jump: it pauses instead; a hardware duck on a DAC
     * that is gone has nothing to restore, and the DAC is opened at its own level next time.
     */
    fun onRouteChanged() {
        commands.trySend(Command.RouteChanged)
    }

    private fun endPending() = endsPending.get() > 0

    private suspend fun begin(generation: Long) {
        if (state != Ducked.None) return
        val direct = host.directActive()
        val mode = host.signalMode()
        // Only a PRISTINE signal has to look at the DAC: the others do not use it, and reading it is a transfer.
        val hardware = if (direct && mode == DirectSignalMode.PRISTINE) host.hardwareVolume() else null
        // The read can take a while; if the focus came back (or went) meanwhile there is nothing left to duck for.
        if (endPending()) {
            Timber.tag(TAG).d("focus ended before the duck began; not ducking")
            return
        }
        when (DirectDuckPolicy.actionFor(direct, mode, hardware)) {
            DirectDuckPolicy.Action.SYSTEM -> Unit
            DirectDuckPolicy.Action.DIGITAL_RAMP -> {
                host.setDigitalDuckGain(DirectDuckPolicy.DUCK_GAIN)
                state = Ducked.Digital(generation)
            }
            DirectDuckPolicy.Action.PAUSE -> {
                host.pauseForDuck(generation)
                state = Ducked.Paused(generation)
            }
            DirectDuckPolicy.Action.HARDWARE_VOLUME -> duckHardware(requireNotNull(hardware), generation)
        }
    }

    private suspend fun duckHardware(hardware: PristineModePolicy.HardwareVolume, generation: Long) {
        val target = requireNotNull(DirectDuckPolicy.usableHardwareDuckStep(hardware))
        val ducked = Ducked.Hardware(
            preDuck = hardware.currentStep,
            written = hardware.currentStep,
            maxStep = hardware.maxStep,
            ticket = hardware.ticket,
            generation = generation,
        )
        state = ducked
        // One step at a time: a ramp, and every write is a compare-and-set read back. A refusal stops where it is:
        // the duck is then shallower than asked, never louder than it began. Somebody else's write ends it for good.
        var refused = false
        while (ducked.written > target) {
            if (!host.directActive()) {
                // The route is not Direct any more: whatever the DAC holds is no longer this duck's to manage.
                Timber.tag(TAG).i("Direct ended during the duck at step %d; the duck is over", ducked.written)
                state = Ducked.None
                return
            }
            // A gain or loss already queued: stop going down. The duck stays, so the way back starts from where it is.
            if (endPending()) break
            val step = ducked.written - 1
            when (host.setHardwareStepTemporarily(step, ducked.ticket, expectedStep = ducked.written)) {
                TemporaryWrite.WRITTEN -> ducked.written = step
                TemporaryWrite.SUPERSEDED -> {
                    Timber.tag(TAG).i("duck step %d not written: the volume was set by someone else; the duck is over", step)
                    state = Ducked.None
                    return
                }
                // A step down never raises, so the ceiling cannot refuse it; handled as a refusal to be exhaustive.
                TemporaryWrite.REFUSED, TemporaryWrite.ABOVE_CEILING -> {
                    Timber.tag(TAG).w("DAC refused duck step %d; holding %d", step, ducked.written)
                    refused = true
                    break
                }
            }
            if (ducked.written > target) delay(downStepMs)
        }
        if (ducked.written == hardware.currentStep) {
            state = Ducked.None
            // Nothing was lowered. If the DAC would not take the first write: pause, as for a DAC without volume. If
            // the focus is already over or Direct is gone, there is nothing to pause for.
            if (refused && !endPending() && host.directActive()) {
                host.pauseForDuck(generation)
                state = Ducked.Paused(generation)
            }
        }
    }

    private suspend fun end() {
        val ducked = state
        state = Ducked.None
        when (ducked) {
            Ducked.None, is Ducked.Paused -> Unit
            is Ducked.Digital -> host.setDigitalDuckGain(1f)
            is Ducked.Hardware -> restoreHardware(ducked)
        }
    }

    /**
     * Gives the DAC back its level one step at a time. The ceiling and the route are looked at again before every step
     * (the ceiling can come down, an override can end, Direct can stop), and every step is the duck's compare-and-set:
     * if the listener touched the volume since, the first step that finds out is refused and the ramp is over, with the
     * level where the listener put it.
     */
    private suspend fun restoreHardware(ducked: Ducked.Hardware) {
        var at = ducked.written
        var aboveCeiling = 0
        while (true) {
            if (!host.directActive()) {
                Timber.tag(TAG).i("Direct ended during the restore at step %d; leaving the DAC as it is", at)
                return
            }
            // `held == written`: that the DAC is really there is the compare-and-set's to check, below.
            val target = DirectDuckPolicy.restoreStep(
                preDuck = ducked.preDuck,
                held = at,
                written = at,
                ceilingStep = host.ceilingStep(),
                maxStep = ducked.maxStep,
            ) ?: return
            val next = at + 1
            when (host.setHardwareStepTemporarily(next, ducked.ticket, expectedStep = at)) {
                TemporaryWrite.WRITTEN -> { at = next; aboveCeiling = 0 }
                TemporaryWrite.SUPERSEDED -> {
                    Timber.tag(TAG).i("restore step %d not written: the volume was set by someone else; it stands", next)
                    return
                }
                TemporaryWrite.ABOVE_CEILING -> {
                    // The ceiling came down between this loop's reading and the write. Nothing was written and the volume
                    // is still ours: read the ceiling again at the top (the target is then at or below it, or no longer
                    // above where the DAC is, and the ramp ends there). A host that keeps refusing a step this loop still
                    // wants would spin, so it gives up after a few.
                    if (++aboveCeiling > MAX_CEILING_RETRIES) {
                        Timber.tag(TAG).w("restore step %d keeps being refused as above the ceiling; leaving the DAC at %d", next, at)
                        return
                    }
                    Timber.tag(TAG).i("restore step %d not written: above the Safety Max ceiling now; reading it again", next)
                    continue
                }
                TemporaryWrite.REFUSED -> {
                    Timber.tag(TAG).w("DAC refused restore step %d; leaving it quieter", next)
                    return
                }
            }
            if (at < target) delay(upStepMs)
        }
    }

    private suspend fun routeChanged() {
        when (val ducked = state) {
            is Ducked.Digital -> if (!host.directActive() || host.signalMode() != DirectSignalMode.PROCESSED) {
                host.setDigitalDuckGain(1f)
                if (host.directActive()) host.pauseForDuck(ducked.generation)
                state = if (host.directActive()) Ducked.Paused(ducked.generation) else Ducked.None
            }
            is Ducked.Hardware -> if (!host.directActive()) state = Ducked.None
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "DirectDuck"
        const val DOWN_STEP_MS = 20L
        const val UP_STEP_MS = 40L
        const val MAX_CEILING_RETRIES = 3
    }
}
