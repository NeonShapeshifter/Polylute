package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.ui.ds.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class EqualizerBandEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun prepare() { TestScreen.keepAwake(compose.activity) }
    @After fun cleanup() { TestScreen.release(compose.activity) }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Every band is a slider for accessibility too: a set-progress action moves it, whole dB only, clamped to the engine's +-15. */
    @Test fun accessibleAdjustmentQuantizesAndClamps() {
        var gains by mutableStateOf(List(15) { 0 })
        val labels = GraphicEqBands.FIFTEEN_BAND_LABELS
        compose.setContent { DapTheme { DapEqBandSliders(gains, labels, { i, g -> gains = gains.toMutableList().also { it[i] = g } }) } }
        val first = { gain: String -> context.getString(R.string.eq_band_accessible, labels[0], gain) }
        compose.onNodeWithContentDescription(first("0")).performSemanticsAction(SemanticsActions.SetProgress) { it(4.2f) }
        compose.runOnIdle { assertEquals(4, gains[0]); assertEquals(1, gains.count { it != 0 }) }
        compose.onNodeWithContentDescription(first("+4")).performSemanticsAction(SemanticsActions.SetProgress) { it(99f) }
        compose.runOnIdle { assertEquals(15, gains[0]) }
    }
}
