package com.lostf1sh.pixelplayeross.data.service

import android.util.Printer

/**
 * B31 P2.3: **what the main thread was busy with while a message waited behind it.**
 *
 * [SessionPublicationMeter]'s "controllers" part is not the controllers' work: it is how long a message posted at the end of the main
 * looper's queue took to run. The device's log shows it at 148–448 ms for a 21-item timeline (the same publication of 4,870 items costs
 * 5 ms on a quiet main thread, and the host measures 0.24 ms for the controllers), so what fills that time is whatever else the main
 * thread ran in the window: a frame, a state-flow collector, a binder callback. This probe names it. It is attached to the looper's
 * message log only between [begin] and [end] (a few milliseconds when nothing is in the way), keeps the longest few messages the looper
 * dispatched meanwhile, and [end] hands them back.
 *
 * The looper builds the log strings only while a printer is attached, so outside the window nothing is paid. Main thread only.
 * [attach] is `Looper.getMainLooper().setMessageLogging`; nothing else in the app uses it.
 */
class MainLooperBusyProbe(
    private val clockMs: () -> Long,
    private val attach: (Printer?) -> Unit,
    private val keep: Int = 3,
) {
    /** One message the looper dispatched inside the window: how long it ran, and the looper's own description of it. */
    data class Message(val ms: Long, val what: String)

    private var users = 0
    private var runningSince = -1L
    private var running: String? = null
    private val longest = ArrayList<Message>()

    private val printer = Printer { line ->
        when {
            line.startsWith(START) -> {
                running = line.substring(START.length).take(MAX_WHAT)
                runningSince = clockMs()
            }
            line.startsWith(FINISH) -> {
                val since = runningSince
                if (since >= 0L) note(Message(clockMs() - since, running ?: UNKNOWN))
                runningSince = -1L
                running = null
            }
        }
    }

    /**
     * Opens the window (a second [begin] before [end] joins the first). Called from inside a message, so that message's own start was
     * never seen: its remainder counts from here, as [RUNNING_AT_START].
     */
    fun begin() {
        if (users++ == 0) {
            longest.clear()
            running = RUNNING_AT_START
            runningSince = clockMs()
            attach(printer)
        }
    }

    /** Closes one user of the window and returns the longest messages dispatched since it opened (longest first). */
    fun end(): List<Message> {
        val result = longest.toList()
        if (users > 0 && --users == 0) {
            attach(null)
            runningSince = -1L
            running = null
        }
        return result
    }

    private fun note(message: Message) {
        longest += message
        longest.sortByDescending { it.ms }
        while (longest.size > keep) longest.removeAt(longest.size - 1)
    }

    companion object {
        const val RUNNING_AT_START = "(the message that was running when the window opened)"
        private const val START = ">>>>> Dispatching to "
        private const val FINISH = "<<<<< Finished to "
        private const val UNKNOWN = "(unknown)"
        private const val MAX_WHAT = 140
    }
}
