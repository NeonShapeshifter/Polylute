package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.decent.usbaudio.UsbDeviceOwner
import com.decent.usbaudio.UsbDiagnosticJournal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What opening the HID interface came to. */
sealed interface HidUse<out T> {
    data class Ran<T>(val value: T) : HidUse<T>

    /**
     * The interface could not be used (no device, no permission, no HID interface, a claim that failed): nothing was sent.
     * [stale]: the caller's own guard said the operation is no longer the one asked for (B30-fix1 H2); the interface was not opened.
     */
    data class Unavailable(val why: String, val stale: Boolean = false) : HidUse<Nothing>
}

/**
 * Opens an attached DAC's HID interface, hands a [FiioHidSession] to the caller and gives the interface back.
 * [UsbFiioHidOpener] is the Android one; the tests use a fake DAC.
 */
interface FiioHidOpener {
    /** Runs [block] on the caller's thread (it blocks on USB transfers), then releases the interface. */
    fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T>
}

/**
 * B30: the one door to the DAC's HID interface.
 *
 * - **One session at a time** (a mutex): the attach read, the screen's refresh, a write and a debug command never overlap, so
 *   two claims of the interface cannot fight.
 * - **Off the caller's thread**: everything runs on [dispatcher], a thread of its own (`DacHid`, background priority in the
 *   real app), never the main thread and never a thread of the audio path.
 * - **Claims only the HID interface** ([UsbFiioHidOpener]) and gives it back when [block] returns, whatever happened in it.
 *
 * Nothing in here knows what is sent: [block] decides, and the write half is reachable only through the gate (see
 * [FiioHidWrites]).
 */
class DacEqHidAccess(
    private val opener: FiioHidOpener,
    private val dispatcher: CoroutineDispatcher,
    private val owner: UsbDeviceOwner? = null,
    private val openGeneration: () -> Long = { 0L },
) {
    /** Held for the whole of a session. A debug command that opens its own connection (the probe, the listen) takes it too. */
    val lock = Mutex()

    /** B60 (#53): which preset each attachment last had selected, so a DAC that answers a marker instead of it (the Melody's 0x0A) is read as what it is. Shared by the reads and the writes. */
    val presetClaims = FiioPresetClaims()

    /**
     * [stale] is asked **after the lock is won and before the interface is opened**: a caller that waited behind another session (or
     * a debounce, a read, a write) finds out here whether what it came to do is still what is wanted, and an answer other than
     * null ends it with [HidUse.Unavailable] (`stale = true`) without the DAC being touched.
     */
    suspend fun <T> withSession(
        attached: AttachedFiio,
        log: (String) -> Unit,
        stale: () -> String? = { null },
        block: (FiioHidSession) -> T,
    ): HidUse<T> = withContext(dispatcher) {
        repeat(OWNER_POLLS) { poll ->
            val answer = lock.withLock {
                    stale()?.let { return@withLock HidUse.Unavailable(it, stale = true) }
                    if (owner == null) return@withLock opener.use(attached, log, block)
                    val claim = owner.tryClaimAccessory("HID", owner.generation, settled = true) ?: return@withLock null
                    claim.use {
                        if (!claim.isCurrent()) return@withLock null
                        stale()?.let { return@withLock HidUse.Unavailable(it, stale = true) }
                        val connection = openGeneration()
                        val started = System.nanoTime()
                        var ran = false
                        UsbDiagnosticJournal.control(connection, claim.generation, UsbDiagnosticJournal.Control.HID_BEGIN, "hid")
                        try {
                            opener.use(attached, log, block).also { ran = it is HidUse.Ran }
                        } finally {
                            UsbDiagnosticJournal.control(connection, claim.generation, UsbDiagnosticJournal.Control.HID_END, "hid",
                                result = if (ran) 1 else 0, elapsedMs = (System.nanoTime() - started) / 1_000_000)
                        }
                    }
            }
            if (answer != null) return@withContext answer
            if (poll < OWNER_POLLS - 1) delay(OWNER_POLL_MS)
        }
        HidUse.Unavailable("the USB device is busy")
    }

    private companion object {
        const val OWNER_POLLS = 121
        const val OWNER_POLL_MS = 50L
    }
}
