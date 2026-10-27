package com.lostf1sh.pixelplayeross.ui.glass

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * B42-fix3, item 4: the glass follows Battery saver, "Remove animations" and the screen through **one** registration for the process.
 *
 * B42 made every composition of `CoverGlassHost` register a content observer and two broadcast receivers of its own, and let them go when it
 * left; the trace of nine song changes on the HONOR showed the pair torn down and made again about every song. The reviewer read it as
 * "one per copy" — the fix is the same either way: copies ask a shared source, and the platform sees one registration per action.
 *
 * The stops themselves (paused, screen off, hidden, Battery saver, "Remove animations", dozing) are B41's `GlassMotionPolicy` tests,
 * which hand the policy their own environment and stay as they were; the last two tests here run the real source against the platform's own
 * switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class B42Fix3SystemSignalsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun receivers(action: String) = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(action) }

    private fun powerSaveReceivers() = receivers(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
    private fun screenReceivers() = receivers(Intent.ACTION_SCREEN_OFF)

    /** The environment of one copy of the glass, as the real root makes it. */
    @Composable
    private fun OneCopy(onEnvironment: (GlassEnvironment) -> Unit = {}) {
        onEnvironment(rememberGlassEnvironment())
    }

    @Test
    fun `five copies of the glass are one registration per action`() {
        compose.setContent {
            repeat(5) { OneCopy() }
        }
        compose.waitForIdle()

        assertThat(powerSaveReceivers()).hasSize(1)
        assertThat(screenReceivers()).hasSize(1)
        // and the screen's broadcasts are one receiver: SCREEN_ON and SCREEN_OFF share it
        assertThat(receivers(Intent.ACTION_SCREEN_ON)).hasSize(1)
    }

    @Test
    fun `a copy that comes and goes with every song does not register again`() {
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) OneCopy()
        }
        compose.waitForIdle()
        val first = powerSaveReceivers().single().broadcastReceiver
        val firstScreen = screenReceivers().single().broadcastReceiver

        // nine song changes, each taking the copy out and putting it back (what the trace of the HONOR showed)
        repeat(9) {
            compose.runOnIdle { shown = false }
            compose.waitForIdle()
            // out for a moment: still registered
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertThat(powerSaveReceivers()).hasSize(1)
            compose.runOnIdle { shown = true }
            compose.waitForIdle()
            assertThat(powerSaveReceivers().single().broadcastReceiver).isSameInstanceAs(first)
            assertThat(screenReceivers().single().broadcastReceiver).isSameInstanceAs(firstScreen)
        }
    }

    @Test
    fun `the registration is let go after the last copy has been away for good`() {
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) repeat(3) { OneCopy() }
        }
        compose.waitForIdle()
        assertThat(powerSaveReceivers()).hasSize(1)

        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(GlassSystemSignals.GRACE_MS + 500, TimeUnit.MILLISECONDS)

        assertThat(powerSaveReceivers()).isEmpty()
        assertThat(screenReceivers()).isEmpty()

        // and it comes back when a copy does
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertThat(powerSaveReceivers()).hasSize(1)
        assertThat(screenReceivers()).hasSize(1)
    }

    @Test
    fun `Battery saver, Remove animations and a dark screen reach every copy`() {
        val seen = ArrayList<GlassEnvironment>()
        compose.setContent {
            repeat(3) { OneCopy { seen += it } }
        }
        compose.waitForIdle()
        val last = seen.takeLast(3)
        assertThat(last.map { it.animationsAllowed.value }).containsExactly(true, true, true)
        assertThat(last.map { it.interactive.value }).containsExactly(true, true, true)

        // Battery saver
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsPowerSaveMode(true)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertThat(last.map { it.animationsAllowed.value }).containsExactly(false, false, false)
        shadowOf(power).setIsPowerSaveMode(false)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(last.map { it.animationsAllowed.value }).containsExactly(true, true, true)

        // the screen goes dark
        shadowOf(power).turnScreenOn(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertThat(last.map { it.interactive.value }).containsExactly(false, false, false)
        shadowOf(power).turnScreenOn(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(last.map { it.interactive.value }).containsExactly(true, true, true)
    }

    @Test
    fun `Remove animations reaches every copy`() {
        val seen = ArrayList<GlassEnvironment>()
        compose.setContent {
            repeat(2) { OneCopy { seen += it } }
        }
        compose.waitForIdle()
        val last = seen.takeLast(2)
        assertThat(last.map { it.animationsAllowed.value }).containsExactly(true, true)

        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertThat(last.map { it.animationsAllowed.value }).containsExactly(false, false)

        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(last.map { it.animationsAllowed.value }).containsExactly(true, true)
    }

    @Suppress("unused")
    private fun keep(r: BroadcastReceiver) = r
}
