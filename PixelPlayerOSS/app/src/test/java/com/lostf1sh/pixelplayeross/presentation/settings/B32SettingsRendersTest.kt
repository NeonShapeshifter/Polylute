package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.B32Renders
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSettingRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q6: renders of the Settings list and the System page, whole (a window tall enough to hold them), at the HONOR's width, in
 * en / es / sv. The list is drawn from the same categories and the same row the Settings root uses; "before" is that list as it was,
 * with the Developer options row a debug build added and no System row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1250dp-xhdpi")
open class B32SettingsRendersTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val language get() = context.resources.configuration.locales[0].language

    @Composable
    private fun CategoryList(categories: List<Pair<String, String>>) {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md)) {
                DapScreenTitle(text = stringResource(R.string.settings_top_bar_title))
                DapGroup(title = stringResource(R.string.settings_top_bar_title)) {
                    categories.forEach { (title, subtitle) ->
                        DapSettingRow(label = title, description = subtitle, onClick = {}, showChevron = true)
                        DapDivider()
                    }
                }
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun row(category: SettingsCategory) = text(category.titleRes) to text(category.subtitleRes)

    /** The row the Developer category had (its strings left with it), in the language of the run. */
    private fun developerRow() = when (language) {
        "es" -> "Opciones de desarrollador" to "Funciones experimentales y depuración"
        "sv" -> "Utvecklaralternativ" to "Experimentella funktioner och felsökning"
        else -> "Developer Options" to "Experimental features and debugging"
    }

    @Test
    fun `the Settings list gains System, and a debug build no longer adds Developer options`() {
        val after = SettingsSurfacePolicy.visibleCategories()
        compose.setContent { CategoryList(after.map(::row)) }
        B32Renders.saveAs(compose, "q6-settings-list-after-$language")
    }

    @Test
    fun `the Settings list as it was, with the Developer row of a debug build and no System row`() {
        // the list of B31: the public categories but System, then Developer, with About last (the root draws About after the others)
        val before = SettingsCategory.entries.filter { it.visibility == com.lostf1sh.pixelplayeross.presentation.model.SettingsVisibility.PUBLIC }
            .filter { it != SettingsCategory.SYSTEM && it != SettingsCategory.ABOUT }.map(::row) +
            developerRow() +
            row(SettingsCategory.ABOUT)
        compose.setContent { CategoryList(before) }
        B32Renders.saveAs(compose, "q6-settings-list-before-$language")
    }

    @Test
    fun `the whole System page`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = 16.dp)) {
                    DapScreenTitle(text = stringResource(R.string.settings_category_system_title))
                    SystemSettingsScreen(
                        state = SystemSettingsState(purgeableDormantCount = 62),
                        actions = object : SystemSettingsActions {
                            override fun onOpenAndroidSettings() {}
                            override fun onOpenUsbSettings() {}
                            override fun onOpenWifiSettings() {}
                            override fun onOpenBluetoothSettings() {}
                            override fun onRebuildDatabase() {}
                            override fun onDeleteImportedLyrics() {}
                            override fun onCleanUpItemsWithoutFile() {}
                            override fun onOpenMaintenance() {}
                        },
                        mountInputBridge = false,
                    )
                }
            }
        }
        B32Renders.saveAs(compose, "q6-system-page-whole-$language")
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h1250dp-xhdpi")
class B32SettingsRendersSpanishTest : B32SettingsRendersTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h1250dp-xhdpi")
class B32SettingsRendersSwedishTest : B32SettingsRendersTest()
