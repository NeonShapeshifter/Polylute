package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.DeviceVolumeMode
import com.lostf1sh.pixelplayeross.data.volume.FixedOutputDac
import com.lostf1sh.pixelplayeross.data.volume.FixedOutputNotice
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.VolumeQuestionStage
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B40-P3: the question about a device whose volume Polylute cannot set, page by page, in the words the three languages have for it. The
 * Spanish and Swedish classes below run the same tests. Resonance is the default button; "No" answers; Purity's rise is confirmed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class FixedOutputQuestionDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val dac = FixedOutputDac("dac-1", "USB-C dongle")
    private val calls = mutableListOf<String>()

    @Before
    fun hiltAnswers() {
        val repository = mockk<UserPreferencesRepository>(relaxed = true)
        every { repository.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val entryPoint = object : ModalInputEntryPoint {
            override fun safetyVolumeController(): SafetyVolumeController = mockk(relaxed = true)
            override fun userPreferencesRepository(): UserPreferencesRepository = repository
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), ModalInputEntryPoint::class.java)
        } returns entryPoint
    }

    @After
    fun hiltIsBack() = unmockkStatic(EntryPointAccessors::class)

    private fun show(notice: FixedOutputNotice) {
        compose.setContent {
            FixedOutputQuestionDialog(
                notice = notice,
                onDismiss = { calls += "dismiss" },
                onOwnControl = { calls += "ownControl:$it" },
                onChoose = { calls += "choose:$it" },
                onConfirmRise = { calls += "confirmRise" },
            )
        }
        compose.waitForIdle()
    }

    private fun notice(stage: VolumeQuestionStage, riseDb: Float? = null) =
        FixedOutputNotice(dac, holdsPlayback = true, stage = stage, riseDb = riseDb)

    @Test
    fun `the first page asks whether the device has its own volume control, and names it`() {
        show(notice(VolumeQuestionStage.ASK_CONTROL))
        compose.onNodeWithText(s(R.string.fixed_output_notice_title)).assertExists()
        compose.onNodeWithText(s(R.string.fixed_output_question_body, "USB-C dongle")).assertExists()
    }

    @Test
    fun `yes and no answer the question, Not now closes it`() {
        show(notice(VolumeQuestionStage.ASK_CONTROL))
        compose.onNodeWithText(s(R.string.fixed_output_question_yes)).performClick()
        compose.onNodeWithText(s(R.string.fixed_output_question_no)).performClick()
        compose.onNodeWithText(s(R.string.fixed_output_notice_not_now)).performClick()
        assertThat(calls).containsExactly("ownControl:true", "ownControl:false", "dismiss").inOrder()
    }

    @Test
    fun `the choice offers Resonance as the default button and Purity beside it, and says what each does`() {
        show(notice(VolumeQuestionStage.CHOOSE_MODE))
        compose.onNodeWithText(s(R.string.fixed_output_mode_title, "USB-C dongle")).assertExists()
        compose.onNodeWithText(s(R.string.fixed_output_mode_body)).assertExists()
        compose.onNodeWithText(s(R.string.fixed_output_mode_resonance)).performClick()
        compose.onNodeWithText(s(R.string.fixed_output_mode_purity)).performClick()
        assertThat(calls).containsExactly("choose:RESONANCE", "choose:PURITY").inOrder()
    }

    @Test
    fun `a rise Purity would bring is shown in decibels and applied only on a yes`() {
        show(notice(VolumeQuestionStage.CONFIRM_RISE, riseDb = 29.2f))
        compose.onNodeWithText(s(R.string.fixed_output_rise_body, "USB-C dongle", 30)).assertExists()
        compose.onNodeWithText(s(R.string.fixed_output_rise_confirm)).performClick()
        assertThat(calls).containsExactly("confirmRise")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class FixedOutputQuestionDialogSpanishTest : FixedOutputQuestionDialogTest()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class FixedOutputQuestionDialogSwedishTest : FixedOutputQuestionDialogTest()
