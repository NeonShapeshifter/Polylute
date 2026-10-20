package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixHeader
import com.lostf1sh.pixelplayeross.presentation.screens.YourMixHeader
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B25-S7: Home's mix sections have one header each. "TU MIX" used to sit over "Tu mix de hoy", and "MIX DIARIO" over "Basado en
 * el historial": the same section named twice. What is drawn is the heading and nothing above it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
open class HomeMixHeadersRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun render() = compose.setContent {
        Column {
            YourMixHeader(song = context.getString(R.string.home_your_mix_heading), onPlayShuffled = {})
            DailyMixHeader(thumbnails = persistentListOf())
        }
    }

    @Test
    fun `each mix section shows its name once, as the heading`() {
        render()
        compose.onAllNodesWithText(context.getString(R.string.home_your_mix_heading)).assertCountEquals(1)
        compose.onAllNodesWithText(context.getString(R.string.home_daily_mix_title)).assertCountEquals(1)
    }

    @Test
    fun `there is no second header over or under the mix heading, in any case`() {
        render()
        val yourMix = context.getString(R.string.home_your_mix_heading)
        // the old label was the section's short name ("TU MIX", "YOUR MIX"): nothing but the heading itself may say it
        val shortName = yourMix.substringBefore(" de hoy").substringBefore(" for you").substringBefore(" åt dig")
        val labelLike = listOf("TU MIX", "YOUR MIX", "DIN MIX", "MIX DIARIO", "DAILY MIX", "DAGLIG MIX")
        labelLike.forEach { label ->
            // a label would be a node whose whole text is the short name; the headings are longer or differently cased
            val matches = compose.onAllNodesWithText(label, ignoreCase = false).fetchSemanticsNodes().size
            org.junit.Assert.assertEquals("an upper-case label '$label' is still drawn (short name '$shortName')", 0, matches)
        }
        compose.onAllNodesWithText("Basado en el historial", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Based on History", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Baserad på historik", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the shuffle action is still there beside the heading`() {
        render()
        compose.onNodeWithText(context.getString(R.string.home_your_mix_heading)).assertExists()
        compose.onAllNodesWithContentDescription(context.getString(R.string.cd_shuffle_play)).assertCountEquals(1)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescription(text: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text))
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
class HomeMixHeadersRenderSpanishTest : HomeMixHeadersRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xxhdpi")
class HomeMixHeadersRenderSwedishTest : HomeMixHeadersRenderTest()
