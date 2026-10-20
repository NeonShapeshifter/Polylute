package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlinx.coroutines.CoroutineDispatcher
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
) {
    /** Held for the whole of a session. A debug command that opens its own connection (the probe, the listen) takes it too. */
    val lock = Mutex()

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
    ): HidUse<T> = lock.withLock {
        stale()?.let { return@withLock HidUse.Unavailable(it, stale = true) }
        withContext(dispatcher) { opener.use(attached, log, block) }
    }
}
