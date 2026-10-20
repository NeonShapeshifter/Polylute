package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * B30-P1: what the app knows of the attached DAC's EQ, kept live.
 *
 * **The read.** When a FiiO/Snowsky DAC the app can ask is attached and USB Direct is carrying a stream, the controller reads the
 * DAC's whole EQ state **once for that attachment** ([FiioEqReader]), after [settleMs] (so never inside the open path, and not
 * in the first moments of the stream), on [DacEqHidAccess]'s own thread. A new song, a rate change that rebuilds the stream, a
 * route flicker: none of those read again; a new attachment (unplug and plug) does. The state is read again after the app's own
 * write ([publish]) and when the listener asks ([refresh]).
 *
 * **What it never does.** It never sends anything but GETs, never on the main thread, and it has no way to write: the write half
 * is a separate object that needs the gate's permit. A failed read, a timeout or a garbled reply leaves the state unknown or
 * unreadable, which no label turns into "Purity"; audio does not depend on any of it.
 *
 * Plain Kotlin on purpose (the Hilt module builds it): the tests drive it with fake flows and a fake DAC.
 *
 * @param attached the DAC the app can ask, or null: see [AttachedFiioSelector].
 * @param directActive the route says USB Direct is carrying the stream.
 */
class DacEqController(
    private val attached: Flow<AttachedFiio?>,
    private val directActive: Flow<Boolean>,
    val access: DacEqHidAccess,
    private val clock: () -> Long,
    private val settleMs: Long = SETTLE_MS,
    private val log: (String) -> Unit = {},
    /** The DAC the app can ask, from what is known this instant (no waiting for a flow); used to catch up, never to read. */
    private val attachedNow: () -> AttachedFiio? = { null },
) : DacEqLabelSource {
    private val _readout = MutableStateFlow<DacEqReadout>(DacEqReadout.NotApplicable)
    val readout: StateFlow<DacEqReadout> = _readout.asStateFlow()

    private val _label = MutableStateFlow(DacEqLabel.NOT_APPLICABLE)
    override val label: StateFlow<DacEqLabel> = _label.asStateFlow()

    @Volatile
    private var readKey: String? = null
    private var readJob: Job? = null

    @Volatile
    private var directNow = false

    /** Moves whenever the DAC goes away or another one comes: see [AttachmentToken]. */
    @Volatile
    private var generation = 0L

    /** The DAC the app can ask right now, with the token of this attachment of it; null when none is attached. One read of the readout. */
    fun attachment(): Pair<AttachedFiio, AttachmentToken>? {
        val att = _readout.value.attachedOrNull ?: return null
        return att to AttachmentToken(att.attachKey, generation)
    }

    /** Whether [token] is still this attachment of this DAC. */
    fun isCurrent(token: AttachmentToken): Boolean = attachment()?.second == token

    private fun set(value: DacEqReadout) {
        _readout.value = value
        _label.value = value.label
    }

    /**
     * Brings the readout up to what is attached now: if a DAC the app can ask is there and nothing has been published yet, it
     * is [DacEqReadout.Pending]. Reads nothing; the attach read (or a screen's refresh) does that.
     */
    fun resolveNow() {
        val att = attachedNow() ?: return
        if (_readout.value is DacEqReadout.NotApplicable) set(DacEqReadout.Pending(att))
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(attached, directActive) { a, d -> a to d }.collect { (att, direct) -> onInputs(scope, att, direct) }
        }
    }

    private fun onInputs(scope: CoroutineScope, att: AttachedFiio?, direct: Boolean) {
        directNow = direct
        if (att == null) {
            readJob?.cancel()
            readKey = null
            if (_readout.value !is DacEqReadout.NotApplicable) log("DAC_EQ|detached: the DAC's EQ is not known any more")
            generation++
            set(DacEqReadout.NotApplicable)
            return
        }
        val current = _readout.value
        if (current.attachedOrNull?.attachKey != att.attachKey) {
            // A different attachment: what was read belongs to the one before.
            readJob?.cancel()
            readKey = null
            generation++
            set(DacEqReadout.Pending(att))
        } else if (current.attachedOrNull != att) {
            // The same attachment, now with more known about it (the catalog registered the DAC): no new read.
            set(
                when (current) {
                    is DacEqReadout.Pending -> current.copy(attached = att)
                    is DacEqReadout.Known -> current.copy(attached = att)
                    is DacEqReadout.Unreadable -> current.copy(attached = att)
                    DacEqReadout.NotApplicable -> current
                }
            )
        }
        if (direct && readKey != att.attachKey) {
            readKey = att.attachKey
            readJob = scope.launch {
                delay(settleMs)
                if (!directNow) {
                    // Direct went away during the settle: not up any more, so not now. The next time it is up, ask.
                    if (readKey == att.attachKey) readKey = null
                    return@launch
                }
                read(att)
            }
        }
    }

    /** One read of [att]'s whole EQ state, published if [att] is still the attached DAC. */
    private suspend fun read(att: AttachedFiio): DacEqReadout {
        val use = try {
            access.withSession(att, log) { session -> FiioEqReader.read(session) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A USB error is not a reason to disturb anything else: the state is unreadable, which no label turns into Purity.
            HidUse.Unavailable("the read failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        val result = when (use) {
            is HidUse.Ran -> DacEqReadout.Known(att, use.value, clock())
            is HidUse.Unavailable -> DacEqReadout.Unreadable(att, use.why)
        }
        if (_readout.value.attachedOrNull?.attachKey == att.attachKey) {
            set(result)
            log(summary(result))
        }
        return result
    }

    /** Asks again, now (the screen's "read again", or a reviewer command), whatever the route. Null when no DAC is attached. */
    suspend fun refresh(): DacEqReadout? {
        val att = _readout.value.attachedOrNull ?: return null
        return read(att)
    }

    /**
     * B30-fix1 H3: what the app knew of [att]'s EQ is no longer to be trusted (a write that may have changed the DAC did not end in a
     * read that says what it holds, or a read failed): the readout becomes unreadable, which every label shows as unknown and none as
     * Purity. Ignored for a DAC that is not the attached one any more.
     */
    fun invalidate(att: AttachedFiio, why: String) {
        if (_readout.value.attachedOrNull?.attachKey != att.attachKey) return
        set(DacEqReadout.Unreadable(att, why))
        log(summary(_readout.value))
    }

    /** A state the app has just read back after its own write: what the labels follow from now on. */
    fun publish(att: AttachedFiio, state: DacEqState) {
        if (_readout.value.attachedOrNull?.attachKey != att.attachKey) return
        set(DacEqReadout.Known(att, state, clock()))
        log(summary(_readout.value))
    }

    private fun summary(readout: DacEqReadout): String = when (readout) {
        DacEqReadout.NotApplicable -> "DAC_EQ|no DAC the app can ask"
        is DacEqReadout.Pending -> "DAC_EQ|waiting to read ${readout.attached.displayName}"
        is DacEqReadout.Unreadable -> "DAC_EQ|${readout.attached.displayName} not readable: ${readout.why} → label=${readout.label}"
        is DacEqReadout.Known ->
            "DAC_EQ|${readout.attached.displayName} verdict=${readout.state.verdict} label=${readout.label} " +
                "preset=${readout.state.activePreset} complete=${readout.state.isComplete}"
    }

    companion object {
        /** After Direct is carrying the stream and before the first read: the stream's first moments are left alone. */
        const val SETTLE_MS = 2_000L
    }
}
