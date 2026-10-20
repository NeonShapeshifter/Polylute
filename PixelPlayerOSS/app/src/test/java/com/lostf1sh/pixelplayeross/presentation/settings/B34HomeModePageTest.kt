package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.ActivityManager
import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.admin.PinningReadiness
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.HomeApp
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import com.lostf1sh.pixelplayeross.presentation.B34Renders
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B34: "Inicio y modo DAP", the page. Made-up data (the page is pure: it draws a [HomeModeState] and calls [HomeModeActions]); the renders
 * under `B34-evidence/renders/` are Robolectric images, not phone screenshots.
 *
 *  - not home: one row (the home app) and one line that says the levels come once Polylute is home;
 *  - home: the three levels as one choice, the requested level next to the one Android confirms, and a block per level;
 *  - Dedicated without Device Owner is a guide with this build's command and no switch; with Device Owner the lock state, "Volver al DAP"
 *    and "Dejar de ser propietario" (which asks first and runs nothing until it is confirmed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1250dp-xhdpi")
open class B34HomeModePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val language get() = context.resources.configuration.locales[0].language

    private class Recorder : HomeModeActions {
        val calls = mutableListOf<String>()
        override fun onChooseHomeApp() { calls += "chooseHome" }
        override fun onSelectLevel(level: ProductLevel) { calls += "level:${level.name}" }
        override fun onPinNow() { calls += "pin" }
        override fun onUnpin() { calls += "unpin" }
        override fun onOpenSecuritySettings() { calls += "security" }
        override fun onCopyDpmCommand() { calls += "copy" }
        override fun onReturnToDap() { calls += "return" }
        override fun onReleaseDeviceOwner() { calls += "release" }
    }

    private val actions = Recorder()
    private val command = "adb shell dpm set-device-owner app.polylute/com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver"

    private fun state(
        requested: ProductModeRequest? = null,
        owner: Boolean = false,
        home: Boolean = true,
        flag: Boolean = false,
        permitted: Boolean = false,
        lock: Int = ActivityManager.LOCK_TASK_MODE_NONE,
        homeApp: HomeApp = HomeApp(ComponentName("com.example.launcher", "Main"), "Example Launcher", isPolylute = false, isChooser = false),
        pinning: PinningReadiness = PinningReadiness.READY,
    ) = HomeModeState(
        product = ProductModeResolver.resolve(requested, DedicatedProvisioningEvidence(owner, home, flag, permitted, lock)),
        home = homeApp,
        pinning = pinning,
        dpmCommand = command,
    )

    private val polyluteHome = HomeApp(ComponentName("app.polylute", "MainActivity"), "Polylute", isPolylute = true, isChooser = false)

    private fun show(state: HomeModeState) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                HomeModeScreen(state, actions, mountInputBridge = false)
            }
        }
    }

    private fun node(id: Int) = compose.onNodeWithText(s(id)).performScrollTo()
    private fun count(id: Int) = compose.onAllNodesWithText(s(id)).fetchSemanticsNodes().size

    @Test
    fun `when Polylute is not home only the home row shows, with the line that says why`() {
        show(state(home = false))
        node(R.string.home_mode_choose_home).assertHasClickAction()
        node(R.string.home_mode_levels_after_home).assertExists()
        // the row says which app is home now
        compose.onNodeWithText(context.getString(R.string.home_mode_home_now, "Example Launcher")).assertExists()
        listOf(R.string.home_mode_section_level, R.string.home_mode_requested, R.string.home_mode_actual, R.string.home_mode_level_pinned, R.string.home_mode_level_dedicated)
            .forEach { assertEquals(0, count(it)) }
        B34Renders.saveAs(compose, "page-not-home-$language")
    }

    @Test
    fun `the row says when no home app is chosen`() {
        show(state(home = false, homeApp = HomeApp(ComponentName("android", "ResolverActivity"), "Android System", isPolylute = false, isChooser = true)))
        compose.onNodeWithText(s(R.string.home_mode_home_none)).assertExists()
    }

    @Test
    fun `choosing the home app is one tap when Polylute is not Dedicated`() {
        show(state(home = false))
        node(R.string.home_mode_choose_home).performClick()
        assertEquals(listOf("chooseHome"), actions.calls)
    }

    @Test
    fun `the levels show only when Polylute is home, and Launcher is what choosing it gives`() {
        show(state(homeApp = polyluteHome))
        listOf(R.string.home_mode_section_level, R.string.home_mode_requested, R.string.home_mode_actual).forEach { node(it).assertExists() }
        assertEquals(0, count(R.string.home_mode_levels_after_home))
        node(R.string.home_mode_launcher_body).assertExists()
        // nothing surprising: no Pinned or Dedicated block until one is asked for
        assertEquals(0, count(R.string.home_mode_pinned_body))
        assertEquals(0, count(R.string.home_mode_dedicated_body))
        B34Renders.saveAs(compose, "page-launcher-$language")
    }

    @Test
    fun `picking a level calls the action for that level`() {
        show(state(homeApp = polyluteHome))
        compose.onNodeWithText(s(R.string.home_mode_level_pinned)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.home_mode_level_dedicated)).performScrollTo().performClick()
        assertEquals(listOf("level:PINNED", "level:DEDICATED"), actions.calls)
    }

    @Test
    fun `Pinned asked for but not confirmed shows Fijar and says Android has not confirmed it`() {
        show(state(requested = ProductModeRequest.PINNED, homeApp = polyluteHome))
        node(R.string.home_mode_pinned_body).assertExists()
        node(R.string.home_mode_not_confirmed).assertExists()
        node(R.string.home_mode_pin_now).assertHasClickAction().performClick()
        node(R.string.home_mode_unpin_how).assertExists()
        assertEquals(listOf("pin"), actions.calls)
        assertEquals(0, count(R.string.home_mode_pinned_active))
        B34Renders.saveAs(compose, "page-pinned-requested-$language")
    }

    @Test
    fun `Pinned confirmed by Android shows it and offers to release it`() {
        show(state(requested = ProductModeRequest.PINNED, lock = ActivityManager.LOCK_TASK_MODE_PINNED, homeApp = polyluteHome))
        node(R.string.home_mode_pinned_active).assertExists()
        assertEquals(0, count(R.string.home_mode_not_confirmed))
        assertEquals(0, count(R.string.home_mode_pin_now))
        node(R.string.home_mode_unpin_now).performClick()
        node(R.string.home_mode_unpin_how).assertExists()
        assertEquals(listOf("unpin"), actions.calls)
        B34Renders.saveAs(compose, "page-pinned-active-$language")
    }

    @Test
    fun `when Android's App pinning is off the page says so and offers the security settings, and Fijar waits`() {
        show(state(requested = ProductModeRequest.PINNED, homeApp = polyluteHome, pinning = PinningReadiness.DISABLED_IN_SETTINGS))
        node(R.string.home_mode_pin_disabled).assertExists()
        node(R.string.home_mode_open_security).performClick()
        node(R.string.home_mode_pin_now).assertHasNoClickAction()
        assertEquals(listOf("security"), actions.calls)
        B34Renders.saveAs(compose, "page-pinned-switch-off-$language")
    }

    @Test
    fun `Dedicated without Device Owner is a guide with this build's command, never a switch or a fake active`() {
        show(state(requested = ProductModeRequest.DEDICATED, homeApp = polyluteHome))
        node(R.string.home_mode_dedicated_body).assertExists()
        node(R.string.home_mode_guide_title).assertExists()
        node(R.string.home_mode_guide_requirements).assertExists()
        compose.onNodeWithText(command).performScrollTo().assertExists()
        node(R.string.home_mode_guide_may_refuse).assertExists()
        node(R.string.home_mode_guide_copy).performClick()
        assertEquals(listOf("copy"), actions.calls)
        // what Android confirms is Launcher, and the page says the request is not confirmed
        node(R.string.home_mode_not_confirmed).assertExists()
        // none of the Device Owner controls is on the page
        listOf(R.string.home_mode_lock_state, R.string.maintenance_exit, R.string.home_mode_release_owner).forEach { assertEquals(0, count(it)) }
        B34Renders.saveAs(compose, "page-dedicated-no-owner-$language")
    }

    @Test
    fun `as Device Owner Dedicated shows the lock state, Volver al DAP and the release, and the level choice is closed`() {
        show(state(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED, homeApp = polyluteHome))
        node(R.string.home_mode_lock_state).assertExists()
        node(R.string.home_mode_lock_held).assertExists()
        node(R.string.maintenance_exit).assertHasClickAction()
        node(R.string.home_mode_release_owner).assertHasClickAction()
        node(R.string.home_mode_locked_by_owner).assertExists()
        // Launcher and Pinned cannot be picked on an owner: it stays Dedicated until the role is given up
        compose.onNodeWithText(s(R.string.home_mode_level_pinned)).performScrollTo().performClick()
        assertTrue(actions.calls.isEmpty())
        assertEquals(0, count(R.string.home_mode_guide_title))
        B34Renders.saveAs(compose, "page-dedicated-owner-$language")
    }

    @Test
    fun `a Device Owner whose lock is not held says so`() {
        show(state(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_NONE, homeApp = polyluteHome))
        node(R.string.home_mode_lock_not_held).assertExists()
    }

    // ── B34-fix2 R2: "Nivel actual" is what Android confirms ────────────────────────────────────────────────────────

    @Test
    fun `a Device Owner with Polylute home and nothing locked shows Launcher as the actual level, says Android has not confirmed Dedicated, and says what fixes it`() {
        show(state(owner = true, home = true, lock = ActivityManager.LOCK_TASK_MODE_NONE, homeApp = polyluteHome))
        // "Launcher" is the option and the actual level (two nodes); "Dedicated" is the option and the block's title, not a third time as the level
        assertEquals(2, count(R.string.home_mode_level_launcher))
        assertEquals(2, count(R.string.home_mode_level_dedicated))
        node(R.string.home_mode_lock_not_held).assertExists()
        node(R.string.home_mode_not_confirmed).assertExists()
        node(R.string.home_mode_not_locked_hint).assertExists()
        node(R.string.maintenance_exit).assertHasClickAction()
    }

    @Test
    fun `a locked Device Owner is Dedicated as the actual level, with no hint and nothing to confirm`() {
        show(state(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED, homeApp = polyluteHome))
        assertEquals(3, count(R.string.home_mode_level_dedicated))
        assertEquals(1, count(R.string.home_mode_level_launcher))
        assertEquals(0, count(R.string.home_mode_not_confirmed))
        assertEquals(0, count(R.string.home_mode_not_locked_hint))
    }

    @Test
    fun `the hint names the row that fixes it`() {
        val hint = s(R.string.home_mode_not_locked_hint)
        assertTrue(hint, hint.contains(s(R.string.maintenance_exit)))
    }

    @Test
    fun `releasing the owner asks first, says what it does, and runs nothing until it is confirmed`() {
        show(state(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED, homeApp = polyluteHome))
        node(R.string.home_mode_release_owner).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.home_mode_release_confirm_title)).assertExists()
        compose.onNodeWithText(s(R.string.home_mode_release_confirm_body)).assertExists()
        B34Renders.saveAs(compose, "release-confirm-$language")
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertTrue("the release ran without a yes", actions.calls.isEmpty())
        compose.onAllNodesWithText(s(R.string.home_mode_release_confirm_title)).assertCountEquals(0)

        node(R.string.home_mode_release_owner).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.home_mode_release_confirm_action)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("release"), actions.calls)
    }

    @Test
    fun `Volver al DAP asks first too`() {
        show(state(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_NONE, homeApp = polyluteHome))
        node(R.string.maintenance_exit).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.system_confirm_return_title)).assertExists()
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithText(s(R.string.system_confirm_return_action)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("return"), actions.calls)
    }

    @Test
    fun `choosing another home while Dedicated asks first, because it lifts the lock`() {
        show(state(owner = true, flag = true, permitted = true, lock = ActivityManager.LOCK_TASK_MODE_LOCKED, homeApp = polyluteHome))
        node(R.string.home_mode_choose_home).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.home_mode_leave_confirm_title)).assertExists()
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertTrue(actions.calls.isEmpty())
        node(R.string.home_mode_choose_home).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.maintenance_continue)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("chooseHome"), actions.calls)
    }

    @Test
    fun `the page has no HONOR in any language`() {
        show(state(requested = ProductModeRequest.DEDICATED, homeApp = polyluteHome))
        compose.onAllNodesWithText("HONOR", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("hihonor", substring = true).assertCountEquals(0)
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h1250dp-xhdpi")
class B34HomeModePageSpanishTest : B34HomeModePageTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h1250dp-xhdpi")
class B34HomeModePageSwedishTest : B34HomeModePageTest()
