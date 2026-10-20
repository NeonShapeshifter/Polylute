package com.lostf1sh.pixelplayeross.utils

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * B16-G3: one log line (`SystemBars`) with what the window looks like at the points where it went wrong after a language
 * change, so a cold start and a language change can be set side by side on the device instead of guessed at: which
 * creation of the activity this is, whether the platform kept the previous instance's decor view (a relaunch that
 * preserves the window), the bar colours and contrast flags, the decor's background, what the window reports for the system
 * bars, and the language in force. Read-only; it changes nothing.
 */
object SystemBarsDiagnostic {
    private val creations = AtomicInteger()

    @Volatile
    private var lastDecorId = 0

    /** The number of this creation of the activity since the process started (1 on a cold start). */
    fun nextCreation(): Int = creations.incrementAndGet()

    /**
     * Whether this activity's decor view is the very object the previous instance had: the platform relaunched the
     * activity but kept its window. Remembers this one for the next call.
     */
    fun decorReused(activity: Activity): Boolean {
        val id = System.identityHashCode(activity.window.decorView)
        val reused = id == lastDecorId
        lastDecorId = id
        return reused
    }

    fun describe(
        phase: String,
        creation: Int,
        decorReused: Boolean,
        activity: Activity,
        language: String,
        restoredState: Boolean,
    ): String {
        val window = activity.window
        val decor = window.decorView
        val background = (decor.background as? ColorDrawable)?.let { String.format("#%08X", it.color) }
            ?: decor.background?.javaClass?.simpleName ?: "none"
        val bars = ViewCompat.getRootWindowInsets(decor)?.let { insets ->
            val bar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            "top=${bar.top} bottom=${bar.bottom} statusVisible=${insets.isVisible(WindowInsetsCompat.Type.statusBars())} " +
                "navVisible=${insets.isVisible(WindowInsetsCompat.Type.navigationBars())}"
        } ?: "not attached"
        @Suppress("DEPRECATION")
        val statusColor = String.format("#%08X", window.statusBarColor)
        @Suppress("DEPRECATION")
        val navigationColor = String.format("#%08X", window.navigationBarColor)
        @Suppress("DEPRECATION")
        val contrast = "status=${window.isStatusBarContrastEnforced} nav=${window.isNavigationBarContrastEnforced}"
        return "$phase creation=$creation decorReused=$decorReused language=$language restoredState=$restoredState " +
            "statusBarColor=$statusColor navigationBarColor=$navigationColor contrastEnforced[$contrast] " +
            "decorBackground=$background systemBars[$bars] sdk=${Build.VERSION.SDK_INT}"
    }
}
