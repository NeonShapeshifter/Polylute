package com.lostf1sh.pixelplayeross.utils

/**
 * B16-G3: when an activity has to be recreated because the app language changed, and how often.
 *
 * Changing the language used to recreate the activity from two directions at once: the picker called `Activity.recreate()`
 * itself, and on Android 13+ `AppCompatDelegate.setApplicationLocales` hands the change to the platform, which relaunches
 * every activity that does not declare `locale` in `configChanges` (the system's relaunch keeps the **window** across it).
 * The device showed one `onCreate` per change (2026-10-12 17:21:43 and 17:22:19), with the same `Window` before and after:
 * the platform's relaunch, not the picker's, and a window that survived it, with two white bars where the system bars
 * are afterwards.
 *
 * Now the activity declares `locale|layoutDirection`, so the platform's relaunch never happens, and the activity asks for
 * its **own** recreation, once, through a [LanguageRecreateGate]: a recreation made by the activity is a new window, as on
 * a cold start. Nothing else calls `recreate()` for the language.
 */
object LanguageChangePolicy {
    /** From Android 13 (API 33) the per-app locale belongs to the platform, which reports a change as a configuration event. */
    private const val PLATFORM_LOCALES_SDK = 33

    /**
     * How long the activity waits for that configuration event after the stored language changed before it recreates itself
     * anyway (an OEM that does not deliver it must not leave the old language on screen).
     */
    const val PLATFORM_EVENT_GRACE_MS = 1_500L

    fun platformReportsChange(sdk: Int): Boolean = sdk >= PLATFORM_LOCALES_SDK

    /**
     * The language an activity renders in: the app's own choice when there is one, else whatever the configuration says
     * (follow the system). Compared as strings, so two reads of the same state are equal.
     */
    fun effective(languageTag: String, configurationLocales: String): String = languageTag.ifBlank { configurationLocales }
}

/**
 * One instance per activity instance. Every signal that says "the language changed" (the stored preference, the
 * platform's configuration event, the fallback timer) asks it, and it says yes **at most once**: that is what makes a
 * language change exactly one recreation however the signals arrive, in whatever order, or all of them.
 *
 * @param createdWith the effective language the activity was created in ([LanguageChangePolicy.effective]).
 */
class LanguageRecreateGate(private val createdWith: String) {
    private var requested = false

    /** True only the first time the effective language, [now], differs from the one this instance was created with. */
    fun shouldRecreate(now: String): Boolean {
        if (requested || now == createdWith) return false
        requested = true
        return true
    }
}
