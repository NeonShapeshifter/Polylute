package com.lostf1sh.pixelplayeross.dap.mode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.dap.admin.AppPinningPolicy
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver
import com.lostf1sh.pixelplayeross.presentation.settings.DedicatedGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B34: the pieces of "Inicio y modo DAP" that need Android's own classes: the home picker intent (no brand in it), what the current home
 * app is, and the exact command for this build's package and receiver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B34HomePlatformTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the home picker is Android's HOME settings, with no package and no component`() {
        val intent = HomePicker.settingsIntent()
        assertEquals(Settings.ACTION_HOME_SETTINGS, intent.action)
        assertNull(intent.`package`)
        assertNull(intent.component)
    }

    @Test
    fun `the fallback is the system chooser over a plain HOME intent, with no package and no component either`() {
        val chooser = HomePicker.chooserIntent()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNull(chooser.`package`)
        assertNull(chooser.component)
        @Suppress("DEPRECATION")
        val wrapped = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(wrapped)
        assertEquals(Intent.ACTION_MAIN, wrapped!!.action)
        assertTrue(wrapped.hasCategory(Intent.CATEGORY_HOME))
        assertNull(wrapped.`package`)
        assertNull(wrapped.component)
    }

    @Test
    fun `the current home app is read from Android, on any brand`() {
        val own = context.packageName
        val honor = HomeApp.from(ComponentName("com.vendor.home", "Launcher"), "Vendor Home", own)
        assertEquals("Vendor Home", honor.label)
        assertFalse(honor.isPolylute)
        assertFalse(honor.isChooser)

        assertTrue(HomeApp.from(ComponentName(own, "MainActivity"), "Polylute", own).isPolylute)
        // "android" is Android's own resolver: no default is chosen
        assertTrue(HomeApp.from(ComponentName("android", "com.android.internal.app.ResolverActivity"), "Android System", own).isChooser)
        // nothing resolves, or a blank label: unknown, never a made-up name
        assertEquals(HomeApp.Unknown, HomeApp.from(null, null, own))
        assertNull(HomeApp.from(ComponentName("com.vendor.home", "Launcher"), "  ", own).label)
    }

    @Test
    fun `the guide's command names this build's package and its registered receiver`() {
        val receiver = ComponentName(context, DapDeviceAdminReceiver::class.java)
        val command = DedicatedGuide.command(context.packageName, receiver)
        assertEquals(
            "adb shell dpm set-device-owner ${context.packageName}/${DapDeviceAdminReceiver::class.java.name}",
            command,
        )
        // the receiver the command names is the one the manifest declares (a typo here would make Android refuse it)
        assertNotNull(context.packageManager.getReceiverInfo(receiver, 0))
    }

    @Test
    fun `the command follows the package of the build, for a debug build with its suffix too`() {
        val receiver = ComponentName("app.polylute", DapDeviceAdminReceiver::class.java.name)
        assertEquals(
            "adb shell dpm set-device-owner app.polylute.debug/com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver",
            DedicatedGuide.command("app.polylute.debug", receiver),
        )
        assertEquals(
            "adb shell dpm set-device-owner app.polylute/com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver",
            DedicatedGuide.command("app.polylute", receiver),
        )
    }

    @Test
    fun `the security shortcut is Android's security settings`() {
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, AppPinningPolicy.securitySettingsIntent().action)
    }

    // ── B34-fix3 R1: the picker is never part of Polylute's task ─────────────────────────────────────────────────────────────────

    private val pickerTaskFlags =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

    /** Something answers [action], as a device with that screen would (`Intent.resolveActivity` asks for the default category). */
    private fun resolves(action: String) {
        val component = ComponentName("com.vendor.picker", "Picker")
        val packages = shadowOf(context.packageManager)
        packages.addActivityIfNotPresent(component)
        packages.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
    }

    @Test
    fun `the HOME settings intent starts in a task of its own, is finished when the listener leaves it and stays out of Recents`() {
        assertEquals(pickerTaskFlags, HomePicker.settingsIntent().flags and pickerTaskFlags)
    }

    @Test
    fun `the chooser fallback carries the same flags`() {
        assertEquals(pickerTaskFlags, HomePicker.chooserIntent().flags and pickerTaskFlags)
    }

    @Test
    fun `when the device has a HOME settings screen that is the intent started, with the flags`() {
        resolves(Settings.ACTION_HOME_SETTINGS)
        val started = HomePicker.resolveIntent(context)
        assertNotNull(started)
        assertEquals(Settings.ACTION_HOME_SETTINGS, started!!.action)
        assertEquals(pickerTaskFlags, started.flags and pickerTaskFlags)
    }

    @Test
    fun `when it has none the chooser is the intent started, with the flags`() {
        resolves(Intent.ACTION_CHOOSER)
        val started = HomePicker.resolveIntent(context)
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        assertEquals(pickerTaskFlags, started.flags and pickerTaskFlags)
    }

    @Test
    fun `when nothing resolves nothing is started`() {
        assertNull(HomePicker.resolveIntent(context))
    }
}
