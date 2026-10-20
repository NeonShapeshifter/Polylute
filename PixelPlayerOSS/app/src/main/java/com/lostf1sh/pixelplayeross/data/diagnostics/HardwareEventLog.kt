package com.lostf1sh.pixelplayeross.data.diagnostics

import android.os.SystemClock

/**
 * The kinds of hardware-integration event worth keeping. These are the transitions Phase 13 is
 * about: a DAC arriving or leaving, permission being asked for and answered, the route moving
 * between Android and Direct, and the power / screen state changing underneath playback.
 */
enum class HardwareEventType {
    USB_ATTACHED,
    USB_DETACHED,
    USB_PERMISSION_REQUESTED,
    USB_PERMISSION_GRANTED,
    USB_PERMISSION_DENIED,
    USB_PERMISSION_LOST,
    DIRECT_CANDIDATE,
    DIRECT_ACTIVE,
    DIRECT_FALLBACK,
    ROUTE_ANDROID,
    SCREEN_OFF,
    SCREEN_ON,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
}

data class HardwareEvent(
    val type: HardwareEventType,
    /** Monotonic, so the log survives clock changes and is comparable with uptime. */
    val elapsedRealtimeMs: Long,
    val detail: String? = null,
) {
    /** `mm:ss` since boot — short enough to read in a diagnostic list. */
    val uptimeLabel: String
        get() {
            val totalSeconds = elapsedRealtimeMs / 1000
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }
}

/**
 * A bounded, in-memory record of hardware transitions for the Maintenance diagnostic surface.
 *
 * Deliberately process-local and deliberately small: this exists so a hardware fault can be read
 * back after the fact without a debugger, not to provide history. Nothing is written to disk,
 * nothing leaves the device, and the buffer is dropped with the process — a permanent verbose
 * hardware database would be a liability, not a diagnostic.
 *
 * Follows [PerformanceMetrics] rather than the DataStore-backed stores: a plain object with
 * synchronous access, so a broadcast receiver can record into it without a coroutine scope.
 */
object HardwareEventLog {

    /** Enough to cover a plug/unplug session with its route transitions; small enough to render. */
    const val MAX_EVENTS = 80

    private val events = ArrayDeque<HardwareEvent>(MAX_EVENTS)
    private var droppedEventCount = 0

    fun record(type: HardwareEventType, detail: String? = null) {
        synchronized(events) {
            if (events.size >= MAX_EVENTS) {
                events.removeFirst()
                droppedEventCount++
            }
            events.addLast(
                HardwareEvent(
                    type = type,
                    elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    detail = detail?.take(MAX_DETAIL_LENGTH),
                )
            )
        }
    }

    /** Newest first, which is the order a diagnostic list wants to read. */
    fun snapshot(): List<HardwareEvent> = synchronized(events) { events.toList().asReversed() }

    /** How many events were evicted. Non-zero means the buffer wrapped during the window. */
    fun droppedCount(): Int = synchronized(events) { droppedEventCount }

    fun clear() {
        synchronized(events) {
            events.clear()
            droppedEventCount = 0
        }
    }

    private const val MAX_DETAIL_LENGTH = 120
}
