package com.lostf1sh.pixelplayeross

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lostf1sh.pixelplayeross.utils.AppLocaleManager
import com.lostf1sh.pixelplayeross.utils.LanguageChangePolicy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B16-G3, on a device (written, compiled, NOT run: the reviewer runs it): changing the app language recreates
 * `MainActivity` **once**, and into a **new window** (a new decor view), not the platform's relaunch that keeps the old one.
 *
 * The device log of 2026-10-12 17:21:43 / 17:22:19 showed one `onCreate` per change and the very same window before and
 * after, and white bars where the system bars are afterwards. The JVM tests pin the structure (`LanguageRecreateBoundaryTest`,
 * `LanguageChangePolicyTest`); this is the behaviour.
 *
 * It sets the language through [AppLocaleManager], as the picker does, and puts it back to "follow the system" after.
 */
@RunWith(AndroidJUnit4::class)
class LanguageChangeRecreateInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as Application

    /** The decor view's identity at each creation of the main activity, in order. */
    private val decors = CopyOnWriteArrayList<Int>()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) decors += System.identityHashCode(activity.window.decorView)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @After
    fun backToTheSystemLanguage() {
        instrumentation.runOnMainSync { AppLocaleManager.applyLanguage(context, "") }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun changingTheLanguageRecreatesTheMainActivityOnceAndIntoANewWindow() {
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                val creationsBefore = decors.size
                assertTrue("the activity was created", creationsBefore >= 1)
                val decorBefore = decors.last()

                instrumentation.runOnMainSync { AppLocaleManager.applyLanguage(context, "sv") }

                assertTrue("the activity is recreated for the new language", waitUntil(8_000) { decors.size > creationsBefore })
                // Every chance for a second recreation to appear: the configuration event, then the fallback timer.
                Thread.sleep(LanguageChangePolicy.PLATFORM_EVENT_GRACE_MS + 2_000)
                assertEquals("exactly one recreation", creationsBefore + 1, decors.size)
                assertNotEquals("a new window, not the preserved one", decorBefore, decors.last())

                // And back: again one, and again a new window.
                val creationsMid = decors.size
                val decorMid = decors.last()
                instrumentation.runOnMainSync { AppLocaleManager.applyLanguage(context, "es") }
                assertTrue(waitUntil(8_000) { decors.size > creationsMid })
                Thread.sleep(LanguageChangePolicy.PLATFORM_EVENT_GRACE_MS + 2_000)
                assertEquals(creationsMid + 1, decors.size)
                assertNotEquals(decorMid, decors.last())
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
