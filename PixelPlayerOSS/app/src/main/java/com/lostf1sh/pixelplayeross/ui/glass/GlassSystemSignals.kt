package com.lostf1sh.pixelplayeross.ui.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/**
 * B42-fix3 (item 4): the two facts about the system that decide whether the glass may move, followed through **one** registration for the
 * whole process, however many copies of the glass ask.
 *
 * B42 had every `CoverGlassHost` composition register its own `ContentObserver` and two `BroadcastReceiver`s (Battery saver and "Remove
 * animations", and the screen's on and off) and unregister them when it left. The host is removed and put back whenever the glass is
 * (a song with no cover, a mode switch), and a trace of nine song changes on the HONOR showed the pair torn down and made again about every
 * song, each a binder call into the activity manager on the main thread. Now the app registers **once**, with the application context, when the
 * first copy asks, and lets go [graceMillis] after the last one leaves (so a host that is out for a moment does not cost a registration).
 *
 * Every reader gets the same [State]s: a change is one write and the copies that read it follow. Main thread only (composition effects and
 * the main-looper callbacks below).
 */
class GlassSystemSignals internal constructor(
    private val appContext: Context,
    private val graceMillis: Long = GRACE_MS,
) {
    private val animations = mutableStateOf(systemAnimationsAllowed(appContext))
    private val interactive = mutableStateOf(screenInteractive(appContext))

    /** "Remove animations" (animator duration scale 0) and Battery saver both turn animators off; the glass follows them. */
    val animationsAllowed: State<Boolean> get() = animations

    /** The screen is on and interactive: a lock screen on a dark (dozing) screen holds still. */
    val interactiveScreen: State<Boolean> get() = interactive

    private val handler = Handler(Looper.getMainLooper())
    private var leases = 0
    private var registered = false

    /** How many times the real registrations were made; what a test counts next to the platform's own list. */
    internal var registrations = 0
        private set

    private val scaleObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refreshAnimations()
    }
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = refreshAnimations()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = refreshInteractive()
    }
    private val unregisterWhenIdle = Runnable { if (leases == 0) unregister() }

    /** A copy of the glass that wants to follow the system: registers on the first, and is released with [Lease.release]. */
    fun acquire(): Lease {
        leases++
        handler.removeCallbacks(unregisterWhenIdle)
        if (!registered) register()
        // the world may have moved while nobody was looking (the registration was let go, or has just been made)
        refreshAnimations()
        refreshInteractive()
        return Lease(this)
    }

    /** One copy's hold on the registration; releasing twice counts once. */
    class Lease internal constructor(private var owner: GlassSystemSignals?) {
        fun release() {
            owner?.releaseOne()
            owner = null
        }
    }

    private fun releaseOne() {
        if (leases > 0) leases--
        if (leases == 0) {
            handler.removeCallbacks(unregisterWhenIdle)
            handler.postDelayed(unregisterWhenIdle, graceMillis)
        }
    }

    private fun register() {
        appContext.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver)
        ContextCompat.registerReceiver(
            appContext, powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        registrations++
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        appContext.contentResolver.unregisterContentObserver(scaleObserver)
        appContext.unregisterReceiver(powerSaveReceiver)
        appContext.unregisterReceiver(screenReceiver)
    }

    private fun refreshAnimations() {
        animations.value = systemAnimationsAllowed(appContext)
    }

    private fun refreshInteractive() {
        interactive.value = screenInteractive(appContext)
    }

    companion object {
        /** How long the registration outlives its last copy: longer than a song change takes to put the glass back. */
        const val GRACE_MS = 10_000L

        @Volatile
        private var shared: GlassSystemSignals? = null

        /** The process's one source, made with the application context on first use. */
        fun of(context: Context): GlassSystemSignals {
            val app = context.applicationContext ?: context
            shared?.takeIf { it.appContext === app }?.let { return it }
            return synchronized(this) {
                shared?.takeIf { it.appContext === app } ?: GlassSystemSignals(app).also { shared = it }
            }
        }
    }
}
