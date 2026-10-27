package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.diagnostics.DetailedLogState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B40-F7: Maintenance's "Detailed logging" section, as the page draws it: off, on with the minutes left, with a log to share, and in the
 * other two languages. The switch's own rules (half an hour, the scrubber) are in `DetailedLoggingTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1400dp-xhdpi")
open class MaintenanceDetailedLoggingRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private class Recorder : DetailedLoggingActions {
        val calls = mutableListOf<String>()
        var shared = true
        override fun setOn(on: Boolean) { calls += "on:$on" }
        override fun setIncludeEverything(value: Boolean) { calls += "titles:$value" }
        override fun delete() { calls += "delete" }
        override fun share(): Boolean { calls += "share"; return shared }
    }

    private var state by mutableStateOf(DetailedLogState())

    private fun show(actions: DetailedLoggingActions, now: Long = 0L) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { MaintenanceDetailedLogging(state, now, actions) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `off, it says what it is for and offers nothing to share`() {
        val actions = Recorder()
        state = DetailedLogState(on = false)
        show(actions)
        compose.onNodeWithText(s(R.string.detailed_logging_title)).assertExists()
        compose.onNodeWithText(s(R.string.detailed_logging_off_summary)).assertExists()
        compose.onNodeWithText(s(R.string.detailed_logging_contents)).assertExists()
        compose.onNodeWithText(s(R.string.detailed_logging_empty)).assertExists()
        // titles and paths are an option of a run, and there is none; with no log there is nothing to share or delete
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.detailed_logging_share)).assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.detailed_logging_delete)).assertIsNotEnabled()
    }

    @Test
    fun `the first switch turns it on, and nothing else is changed by that`() {
        val actions = Recorder()
        state = DetailedLogState(on = false)
        show(actions)
        compose.onAllNodes(isToggleable())[0].performClick()
        assertThat(actions.calls).containsExactly("on:true")
    }

    @Test
    fun `on, it counts the minutes left, rounded up, and the second switch is available but off`() {
        val actions = Recorder()
        state = DetailedLogState(on = true, untilMs = 29 * 60_000L + 1L, sizeBytes = 2_048L)
        show(actions, now = 0L)
        compose.onNodeWithText(s(R.string.detailed_logging_on_summary, 30)).assertExists()
        val switches = compose.onAllNodes(isToggleable())
        switches[1].assertIsEnabled()
        compose.onAllNodes(isToggleable())[1].performClick()
        assertThat(actions.calls).containsExactly("titles:true")
        compose.onAllNodes(isToggleable())[0].performClick()
        assertThat(actions.calls).containsExactly("titles:true", "on:false").inOrder()
    }

    @Test
    fun `with a log on the phone it can be shared and deleted, and a share that fails is told`() {
        val actions = Recorder()
        state = DetailedLogState(on = false, sizeBytes = 5_120L)
        show(actions)
        compose.onNodeWithText(s(R.string.detailed_logging_share)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(s(R.string.detailed_logging_delete)).performScrollTo().performClick()
        assertThat(actions.calls).containsExactly("share", "delete").inOrder()
        compose.onNodeWithText(s(R.string.detailed_logging_empty)).assertDoesNotExist()
    }

    @Test
    fun `when titles and paths are included the switch says so`() {
        state = DetailedLogState(on = true, untilMs = 60_000L, includeEverything = true, sizeBytes = 10L)
        show(Recorder(), now = 0L)
        val switches = compose.onAllNodes(isToggleable())
        assertThat(switches.fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText(s(R.string.detailed_logging_titles_switch)).assertExists()
    }

    @Test
    fun `a log that holds titles and paths says so beside Share, whatever the switch says now`() {
        val actions = Recorder()
        // the switch is off now, and the file still has lines written while it was on (B40-fix1)
        state = DetailedLogState(on = false, includeEverything = false, sizeBytes = 5_120L, holdsTitlesAndPaths = true)
        show(actions)
        val size = android.text.format.Formatter.formatShortFileSize(context, 5_120L)
        compose.onNodeWithText(s(R.string.detailed_logging_share_size_with_titles, size)).performScrollTo().assertExists()
        compose.onNodeWithText(s(R.string.detailed_logging_share_size, size)).assertDoesNotExist()
    }

    @Test
    fun `a log without them does not say so`() {
        state = DetailedLogState(on = false, sizeBytes = 5_120L, holdsTitlesAndPaths = false)
        show(Recorder())
        val size = android.text.format.Formatter.formatShortFileSize(context, 5_120L)
        compose.onNodeWithText(s(R.string.detailed_logging_share_size, size)).performScrollTo().assertExists()
        compose.onNodeWithText(s(R.string.detailed_logging_share_size_with_titles, size)).assertDoesNotExist()
    }

    @Test
    fun `the minutes left are whole and rounded up`() {
        assertThat(detailedLoggingMinutesLeft(untilMs = 60_000L, nowMs = 0L)).isEqualTo(1)
        assertThat(detailedLoggingMinutesLeft(untilMs = 60_001L, nowMs = 0L)).isEqualTo(2)
        assertThat(detailedLoggingMinutesLeft(untilMs = 1L, nowMs = 0L)).isEqualTo(1)
        assertThat(detailedLoggingMinutesLeft(untilMs = 0L, nowMs = 5L)).isEqualTo(0)
        assertThat(detailedLoggingMinutesLeft(untilMs = 30 * 60_000L, nowMs = 0L)).isEqualTo(30)
    }

    @Test
    fun `a fresh 30 minute run says 30 even when the clock the section holds is seconds old`() {
        // B41: the section's clock is read when it is drawn and every half minute after; a switch turned on 20 s after that reading
        // started a run of 30 min 20 s by that clock, and the round-up said "31".
        assertThat(detailedLoggingMinutesLeft(untilMs = 30 * 60_000L + 20_000L, nowMs = 0L)).isEqualTo(30)
        assertThat(detailedLoggingMinutesLeft(untilMs = 30 * 60_000L + 59_999L, nowMs = 0L)).isEqualTo(30)
        // and a run is never said to be longer than it is
        assertThat(detailedLoggingMinutesLeft(untilMs = 45 * 60_000L, nowMs = 0L)).isEqualTo(30)
    }

    @Test
    fun `right after turning it on the row says 30 minutes, not 31`() {
        val actions = Recorder()
        state = DetailedLogState(on = true, untilMs = 30 * 60_000L + 20_000L, sizeBytes = 0L)
        show(actions, now = 0L)
        compose.onNodeWithText(s(R.string.detailed_logging_on_summary, 30)).assertExists()
        compose.onNodeWithText(s(R.string.detailed_logging_on_summary, 31)).assertDoesNotExist()
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h1400dp-xhdpi")
class MaintenanceDetailedLoggingRenderSpanishTest : MaintenanceDetailedLoggingRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h1400dp-xhdpi")
class MaintenanceDetailedLoggingRenderSwedishTest : MaintenanceDetailedLoggingRenderTest()
