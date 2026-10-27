package com.lostf1sh.pixelplayeross.ui.theme

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B41-fix1 (B2): switching the theme only swaps the palette in composition. It does not touch the window.
 *
 * On the HONOR each theme change logged `VRI[MainActivity]: Visible with new config ... Applying new config to window` and stuttered:
 * a configuration change applied to the whole window. The cause is in two composables that run under the theme and write window
 * properties at every recomposition of it:
 *  - `PixelPlayerWindowBackground` set a new background drawable on the decor view for every palette change (a new drawable on the
 *    decor asks for a layout of the whole window);
 *  - `PixelPlayerStatusBarStyle` set the status and navigation bar colours and the contrast flags again, and the icon appearance, in a
 *    side effect after every recomposition of the theme (on Android 15 a colour also goes to the activity's window-controller callback,
 *    which is a call to the system server, whether or not it changed).
 * Nothing here is an `AppCompatDelegate` night mode, a `uiMode` change or a `recreate()`: the app has none of them for the theme (the
 * language has its own single recreation, pinned in `LanguageRecreateBoundaryTest`).
 *
 * The test runs the real theme in a real activity window and counts what reaches the window while the theme is switched between the
 * dark themes (nothing may), to the light one and back (the bars' icon appearance follows, and only that), and shows that the
 * window's own background still ends up the app's colour: when the activity stops. It compiles against the tree as it was before the
 * batch, and its first test fails there (the counts are in `B41-fix1-resultado.md`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class ThemeSwitchWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val theme = mutableStateOf(DapBaseTheme.Graphite)
    private val seenBackgrounds = mutableListOf<Int>()

    private class Spy {
        var attributesChanged = 0
        var statusBarColorUpdates = 0
        var navigationBarColorUpdates = 0
    }

    /** Counts what the window is told: its attribute changes, and (hidden API, through a proxy) the colours it hands to the activity. */
    private fun spyOn(window: Window): Spy {
        val spy = Spy()
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun onWindowAttributesChanged(attrs: android.view.WindowManager.LayoutParams?) {
                spy.attributesChanged++
                original.onWindowAttributesChanged(attrs)
            }
        }
        val field = Window::class.java.getDeclaredField("mWindowControllerCallback").apply { isAccessible = true }
        val controller = field.get(window)
        if (controller != null) {
            val type = Class.forName("android.view.Window\$WindowControllerCallback")
            field.set(window, Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "updateStatusBarColor" -> spy.statusBarColorUpdates++
                    "updateNavigationBarColor" -> spy.navigationBarColorUpdates++
                }
                method.invoke(controller, *(args ?: emptyArray()))
            })
        }
        return spy
    }

    private fun show() {
        compose.setContent {
            val base by theme
            PixelPlayerTheme(darkTheme = !base.palette.isLight, baseTheme = base, palette = base.palette) {
                PixelPlayerWindowBackground(color = Dap.background)
                val drawn = Dap.background.toArgb()
                SideEffect { seenBackgrounds += drawn }
            }
        }
        compose.waitForIdle()
    }

    private fun backgroundColor(window: Window) = (window.decorView.background as? ColorDrawable)?.color

    @Test
    fun a_switch_between_dark_themes_writes_nothing_to_the_window() {
        show()
        val window = compose.activity.window
        val spy = spyOn(window)
        val backgroundAtStart = window.decorView.background
        val colourAtStart = backgroundColor(window)
        for (next in listOf(DapBaseTheme.Black, DapBaseTheme.Navy, DapBaseTheme.Gray, DapBaseTheme.Graphite, DapBaseTheme.Black)) {
            theme.value = next
            compose.waitForIdle()
        }
        println("B41-fix1 B2 | dark to dark, 5 switches: window attribute changes ${spy.attributesChanged}, status bar colour updates ${spy.statusBarColorUpdates}, " +
            "navigation bar colour updates ${spy.navigationBarColorUpdates}, background replaced ${window.decorView.background !== backgroundAtStart}")
        // the palette really swapped under it (the test is not looking at a theme that never changed)
        assertThat(seenBackgrounds.toSet().size).isAtLeast(4)
        assertThat(spy.attributesChanged).isEqualTo(0)
        assertThat(spy.statusBarColorUpdates).isEqualTo(0)
        assertThat(spy.navigationBarColorUpdates).isEqualTo(0)
        // and the window's own background was not given a new drawable while the app was in front
        assertThat(window.decorView.background).isSameInstanceAs(backgroundAtStart)
        assertThat(backgroundColor(window)).isEqualTo(colourAtStart)
    }

    @Test
    fun a_switch_to_the_light_theme_and_back_flips_the_bars_icons_and_nothing_else() {
        show()
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertThat(controller.isAppearanceLightStatusBars).isFalse()   // Graphite: light icons
        val spy = spyOn(window)
        val backgroundAtStart = window.decorView.background
        theme.value = DapBaseTheme.Light
        compose.waitForIdle()
        // the bars still follow the theme: dark icons on the light one
        assertThat(controller.isAppearanceLightStatusBars).isTrue()
        assertThat(controller.isAppearanceLightNavigationBars).isTrue()
        theme.value = DapBaseTheme.Graphite
        compose.waitForIdle()
        assertThat(controller.isAppearanceLightStatusBars).isFalse()
        assertThat(controller.isAppearanceLightNavigationBars).isFalse()
        println("B41-fix1 B2 | dark to light and back: window attribute changes ${spy.attributesChanged}, status bar colour updates ${spy.statusBarColorUpdates}, " +
            "navigation bar colour updates ${spy.navigationBarColorUpdates}, background replaced ${window.decorView.background !== backgroundAtStart}")
        assertThat(spy.statusBarColorUpdates).isEqualTo(0)
        assertThat(spy.navigationBarColorUpdates).isEqualTo(0)
        assertThat(window.decorView.background).isSameInstanceAs(backgroundAtStart)
    }

    @Test
    fun the_windows_own_background_becomes_the_apps_colour_when_the_activity_stops() {
        show()
        val window = compose.activity.window
        // the first composition set it
        assertThat(backgroundColor(window)).isEqualTo(DapBaseTheme.Graphite.palette.background.toArgb())
        theme.value = DapBaseTheme.Navy
        compose.waitForIdle()
        // in front it is left alone ...
        assertThat(backgroundColor(window)).isEqualTo(DapBaseTheme.Graphite.palette.background.toArgb())
        // ... and the next time the app is behind, it is the colour of the theme now in force
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertThat(backgroundColor(window)).isEqualTo(DapBaseTheme.Navy.palette.background.toArgb())
    }
}
