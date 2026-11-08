package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqDefaults
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.presentation.B61Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B61 item 4 (owner 11-07): the DAC EQ's filter editor, numbers only, easy to use. Tapping a number opens a decimal keyboard and takes the exact value typed (the owner asked for 200 / 5 500 / 8 300 /
 * 10 400 Hz and the arrows' steps had left 199 / 5 472 / 8 461 / 10 148: the DAC echoes the frequency exactly, 5 of 5 writes in `logcat-live-0900.txt`, and only rounds Q by 0.01); a value outside the
 * limits is refused in the field with the range; holding an arrow repeats and speeds up; Done is the app's primary button; and **nothing is handed over until the edit is finished**: one hand-over, so
 * one write, and a curve the gate would refuse is shown as refused here, before any write is tried.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h900dp-xxhdpi")
open class B61DacEqEditorTest {
    @get:Rule
    val compose = createComposeRule()

    protected open val tag: String = "en"
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val capability = DacEqCapability.of(melody)
    private val flat = DacEqDefaults.flatBands(10)

    private fun editor(
        band: DacEqBand,
        curve: List<DacEqBand> = emptyList(),
        sense: PreGainSense = PreGainSense.BOOST,
        handedOver: MutableList<DacEqBand>,
        dismissed: MutableList<Unit> = mutableListOf(),
    ) = compose.setContent {
        DapTheme(darkTheme = true) {
            Column(Modifier.fillMaxWidth().background(Dap.background)) {
                DacEqFilterEditorContent(band, capability, { change -> handedOver += change(band) }, { dismissed += Unit }, curve = curve, sense = sense)
            }
        }
    }

    private val keyboardType = SemanticsMatcher("asks for the decimal keyboard") { it.config.contains(DacEqFieldKeyboard) && it.config[DacEqFieldKeyboard] == "Decimal" }

    private fun type(shown: String, text: String) {
        compose.onNodeWithContentDescription(shown).performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun `tapping a number opens a decimal keyboard, and the keyboard of every field is the same one`() {
        val got = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), handedOver = got)
        for (shown in listOf(frequencyText(1000), "${formatGain(0)} dB", formatQ(100))) {
            compose.onNodeWithContentDescription(shown).performClick()
            compose.onAllNodes(keyboardType).assertCountEquals(1)
            // the field is closed by leaving it: a tap on another number moves the editing there
        }
        assertThat(DacEqNumberKeyboard.keyboardType).isEqualTo(KeyboardType.Decimal)
        assertThat(got).isEmpty()                                                  // opening a keyboard writes nothing
    }

    @Test
    fun `the exact value typed is the value kept, not the nearest step of the arrows`() {
        val got = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), handedOver = got)
        // the owner's asks of 11-07: the arrows could only reach 199, 5 472, 8 461 and 10 148 Hz and Q 0.78, 3.01, 3.80 and 5.33
        type(frequencyText(1000), "200")
        type("${formatGain(0)} dB", "-3.5")
        type(formatQ(100), "3.00")
        compose.onNodeWithText(s(R.string.dac_eq_done)).performClick()
        assertThat(got).hasSize(1)
        assertThat(got.single().frequencyHz).isEqualTo(200)
        assertThat(got.single().gainTenthsDb).isEqualTo(-35)
        assertThat(got.single().qHundredths).isEqualTo(300)
    }

    private fun typedPair(hz: Int, q: Int) {
        val got = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(1, FiioFilterType.PEAK, 1000, 0.0, 1.0), handedOver = got)
        type(frequencyText(1000), hz.toString())
        type(formatQ(100), DacEqNumbers.qText(q))
        compose.onNodeWithText(s(R.string.dac_eq_done)).performClick()
        assertThat(got.single().frequencyHz).isEqualTo(hz)
        assertThat(got.single().qHundredths).isEqualTo(q)
    }

    @Test fun `5500 hertz and Q 0 point 8 are taken as typed`() = typedPair(5500, 80)
    @Test fun `8300 hertz and Q 4 are taken as typed`() = typedPair(8300, 400)
    @Test fun `10400 hertz and Q 5 are taken as typed`() = typedPair(10400, 500)

    @Test
    fun `a value outside the limits is refused in the field with the range, and is never handed over`() {
        val got = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), handedOver = got)
        type(frequencyText(1000), "30000")
        compose.onNodeWithText(s(R.string.dac_eq_range_hz, frequencyText(20), frequencyText(20_000))).assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.dac_eq_range_hz, frequencyText(20), frequencyText(20_000))).assertCountEquals(1)
        compose.onNodeWithText(s(R.string.dac_eq_done)).performClick()
        // the draft kept its last valid value (1 kHz), which is the band's own: nothing changed, nothing handed over
        assertThat(got).isEmpty()
        B61Renders.save(compose, "dac-eq-editor-range-$tag")
    }

    /** The gain the editor shows, in tenths of a dB (read from its value text; the comma of a Spanish or Swedish page is read as the point). */
    private fun shownGainTenths(): Int {
        val text = compose.onAllNodesWithText(" dB", substring = true)[0].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        return Math.round(text.replace(" dB", "").replace(',', '.').replace('\u2212', '-').toDouble() * 10).toInt()
    }

    /** The gain after holding `+` for [ms]: the press, then the repeats of the contract (the first at 400 ms, then every [HoldRepeat.intervalAfter], taking [HoldRepeat.multiplierAfter] steps). */
    private fun expectedAfterHolding(ms: Long): Int {
        var at = HoldRepeat.FIRST_DELAY_MS
        var tenths = 5
        var repeat = 0
        while (at <= ms) {
            tenths += 5 * HoldRepeat.multiplierAfter(repeat)
            at += HoldRepeat.intervalAfter(repeat)
            repeat++
        }
        return tenths.coerceAtMost(120)
    }

    @Test
    fun `holding an arrow repeats after 400 ms and speeds up, a press is one step, and a release stops it`() {
        val got = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), handedOver = got)
        compose.mainClock.autoAdvance = false
        val raise = compose.onNodeWithContentDescription(s(R.string.dac_eq_increase, s(R.string.dac_eq_field_gain)))
        raise.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()                                       // one frame (16 ms) after the press
        assertThat(shownGainTenths()).isEqualTo(5)                                    // the press: one step at once (+0.5 dB)
        compose.mainClock.advanceTimeBy(300L, ignoreFrameDuration = true); compose.mainClock.advanceTimeByFrame()
        assertThat(shownGainTenths()).isEqualTo(5)                                    // ~320 ms in: still that one
        compose.mainClock.advanceTimeBy(180L, ignoreFrameDuration = true); compose.mainClock.advanceTimeByFrame()
        assertThat(shownGainTenths()).isEqualTo(10)                                   // ~500 ms in: the first repeat (at 400 ms) and no second yet (the next is ~580 ms)
        // hold on to ~1.5 s: the repeats of the contract, each taking more steps as the hold goes on (within a frame either way)
        compose.mainClock.advanceTimeBy(1_000L, ignoreFrameDuration = true); compose.mainClock.advanceTimeByFrame()
        val heldFor = 16L + 300L + 16L + 180L + 16L + 1_000L + 16L
        val at = shownGainTenths()
        assertThat(at).isAtLeast(expectedAfterHolding(heldFor - 40L))
        assertThat(at).isAtMost(expectedAfterHolding(heldFor + 40L))
        assertThat(at).isGreaterThan(40)                                              // far more than a step per repeat: it sped up
        raise.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100L, ignoreFrameDuration = true); compose.mainClock.advanceTimeByFrame()
        val released = shownGainTenths()
        compose.mainClock.advanceTimeBy(3_000L, ignoreFrameDuration = true); compose.mainClock.advanceTimeByFrame()
        assertThat(shownGainTenths()).isEqualTo(released)                             // released: nothing more happens
        assertThat(got).isEmpty()                                                    // the whole hold handed nothing over
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `the interval shrinks to 40 ms and the steps per repeat grow, from the contract`() {
        assertThat(HoldRepeat.intervalAfter(0)).isEqualTo(180L)
        assertThat((0..60).map { HoldRepeat.intervalAfter(it) }.zipWithNext().all { (a, b) -> b <= a }).isTrue()
        assertThat(HoldRepeat.intervalAfter(60)).isEqualTo(HoldRepeat.MIN_INTERVAL_MS)
        assertThat((0..60).map { HoldRepeat.multiplierAfter(it) }.zipWithNext().all { (a, b) -> b >= a }).isTrue()
        assertThat(HoldRepeat.multiplierAfter(0)).isEqualTo(1)
        assertThat(HoldRepeat.multiplierAfter(60)).isEqualTo(10)
        // a full run of the gain range (24 dB in 0.5 dB taps) is under five seconds of holding
        var ms = HoldRepeat.FIRST_DELAY_MS
        var tenths = 5
        var repeat = 0
        while (tenths < 240) {
            tenths += 5 * HoldRepeat.multiplierAfter(repeat)
            ms += HoldRepeat.intervalAfter(repeat)
            repeat++
        }
        assertThat(ms).isLessThan(5_000L)
    }

    @Test
    fun `a curve the gate would refuse is shown as refused in the editor and is not handed over`() {
        val got = mutableListOf<DacEqBand>()
        val dismissed = mutableListOf<Unit>()
        // the owner's 24 refusals: a gain typed without its minus (PRE_GAIN_SENSE_UNCONFIRMED, "the curve rises 3.5 dB above 0")
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), curve = flat.map { if (it.index == 3) DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0) else it },
            sense = PreGainSense.UNCONFIRMED, handedOver = got, dismissed = dismissed)
        compose.onAllNodesWithText(s(R.string.dac_eq_done)).assertCountEquals(1)
        type("${formatGain(0)} dB", "3.5")
        compose.onNodeWithContentDescription("refused").assertExists()
        compose.onNodeWithText(s(R.string.dac_eq_edit_refused, 3.5), substring = true).assertIsDisplayed()
        B61Renders.save(compose, "dac-eq-editor-refused-$tag")
        compose.onNodeWithText(s(R.string.dac_eq_discard)).performClick()
        assertThat(got).isEmpty()                                                  // refused before any write: nothing handed over
        assertThat(dismissed).hasSize(1)
    }

    @Test
    fun `with its minus the same gain is a cut, which the gate takes, and it is handed over once`() {
        val ok = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), curve = flat, sense = PreGainSense.UNCONFIRMED, handedOver = ok)
        type("${formatGain(0)} dB", "-3.5")
        compose.onAllNodesWithText(s(R.string.dac_eq_discard)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.dac_eq_done)).performClick()
        assertThat(ok.map { it.gainTenthsDb }).containsExactly(-35)
    }

    @Test
    fun `typing into a field hands nothing over, and Done hands the finished band over exactly once`() {
        val got = mutableListOf<DacEqBand>()
        editor(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 0.0, 1.0), handedOver = got)
        compose.onNodeWithContentDescription(frequencyText(1000)).performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        for (key in "2000") {
            compose.onNode(hasSetTextAction()).performTextInput(key.toString())
            assertThat(got).isEmpty()                                                // each keystroke: nothing goes anywhere
        }
        B61Renders.save(compose, "dac-eq-editor-after-$tag")
        RenderChecks.assertInsideWindow(compose, "editor $tag")
        RenderChecks.assertNoCutText(compose, "editor $tag")
        compose.onNodeWithText(s(R.string.dac_eq_done)).performClick()
        assertThat(got).hasSize(1)
        assertThat(got.single().frequencyHz).isEqualTo(2000)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h900dp-xxhdpi")
class B61DacEqEditorEsTest : B61DacEqEditorTest() { override val tag = "es" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h900dp-xxhdpi")
class B61DacEqEditorSvTest : B61DacEqEditorTest() { override val tag = "sv" }
