package com.lostf1sh.pixelplayeross.dap.mode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The app that answers the HOME button right now (B34). Read from Android, the same on every brand: no package name is known in advance.
 *
 *  - [isChooser]: no default is chosen, so Android would ask (the resolver is `android`'s own `ResolverActivity`).
 *  - [isPolylute]: it is this app.
 */
data class HomeApp(
    val component: ComponentName?,
    val label: String?,
    val isPolylute: Boolean,
    val isChooser: Boolean,
) {
    companion object {
        val Unknown = HomeApp(component = null, label = null, isPolylute = false, isChooser = false)

        /** Pure: what a resolved HOME activity means. [resolved] null = nothing resolves. */
        fun from(resolved: ComponentName?, label: String?, ownPackage: String): HomeApp {
            if (resolved == null) return Unknown
            val chooser = resolved.packageName == ANDROID_PACKAGE
            return HomeApp(
                component = resolved,
                label = label?.takeIf { it.isNotBlank() },
                isPolylute = resolved.packageName == ownPackage,
                isChooser = chooser,
            )
        }

        private const val ANDROID_PACKAGE = "android"
    }
}

/** Android's home picker, with nothing hard-coded about any brand's launcher. */
object HomePicker {
    /**
     * B34-fix3 R1: the picker is a task of its own and does not outlive the choice. Started with no flags from Polylute's activity it landed
     * in Polylute's task: on the HONOR `HomeSettingsActivity` took Polylute's `type=home` task and forwarded to `DefaultAppActivity`, which
     * joins the task of the activity that started it, and HONOR does not finish that page after a choice. So the Polylute icon, and then
     * HOME itself, showed the picker (unlocked, over a contained app) until Back.
     *  - `NEW_TASK` is the flag that matters: the picker, and whatever it forwards to, is not part of Polylute's task, so nothing of it can
     *    sit on top of `MainActivity`. It does not change what fix2 relies on: the activity is still paused by the picker taking the
     *    foreground and resumed when it ends, which is what settles a pending HOME exit.
     *  - `NO_HISTORY`: where the intent resolves straight to the picker (a stock Settings screen), it is finished as soon as the listener
     *    leaves it. On the HONOR the first activity is a trampoline that starts `DefaultAppActivity` with flags of its own, so this one
     *    cannot be counted on to reach the real page there.
     *  - `EXCLUDE_FROM_RECENTS`: a one-step screen is not a Recents card. Like `NO_HISTORY` it is applied to the first activity of the chain.
     * Not `CLEAR_TASK` or `MULTIPLE_TASK`: both would reach into tasks that are not Polylute's.
     */
    private const val TASK_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_NO_HISTORY or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

    /** What "Elegir app de inicio" opens first: Android's own HOME settings. No package, no component. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(TASK_FLAGS)

    /** The fallback when a device has no HOME settings screen: the system chooser over every HOME activity. */
    fun chooserIntent(): Intent = Intent.createChooser(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        null,
    ).addFlags(TASK_FLAGS)

    /** The first of [settingsIntent] / [chooserIntent] that something resolves, or null. */
    fun resolveIntent(context: Context): Intent? =
        settingsIntent().takeIf { it.resolveActivity(context.packageManager) != null }
            ?: chooserIntent().takeIf { it.resolveActivity(context.packageManager) != null }
}

/** Reads the current home app from the platform. */
class HomeAppReader(context: Context) {
    private val appContext = context.applicationContext

    fun current(): HomeApp = runCatching {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val component = info?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        val label = info?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
        HomeApp.from(component, label, appContext.packageName)
    }.getOrDefault(HomeApp.Unknown)
}
