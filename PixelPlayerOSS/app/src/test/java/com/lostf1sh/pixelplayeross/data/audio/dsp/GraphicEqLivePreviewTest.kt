package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

/**
 * AUD-111: the slider's live value reaches the DSP at once; the 150 ms debounce only delays the
 * write to storage. And while the editor is open the signal stays PROCESSED, so editing does not
 * toggle PRISTINE ↔ PROCESSED (each toggle rebuilt the producer and triggered AUD-110).
 */
class GraphicEqLivePreviewTest {

    private val scope = TestScope(StandardTestDispatcher())
    private val persisted = MutableStateFlow(HeadphoneEqState(enabled = true))
    private val coordinator = mockk<HeadphoneEqualizerCoordinator>().also {
        every { it.effectiveStateFlow } returns persisted
    }
    private val profiles = mockk<HeadphoneProfileRepository> { every { state } returns MutableStateFlow(HeadphoneProfileState()) }
    private val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)

    private fun controller() = HeadphoneGraphicEqController(coordinator, overrides, scope, profiles).also { scope.runCurrent() }

    @Test
    fun `a dragged band is rendered before it is persisted`() {
        val controller = controller()
        val bands = List(10) { if (it == 0) 9 else 0 }
        controller.previewBands(HeadphoneEqMode.GRAPHIC_10, bands, null)
        assertThat(controller.settings().gainsDb[0]).isEqualTo(9f)
    }

    @Test
    fun `an older persisted value does not pull the live curve back while dragging`() {
        val controller = controller()
        controller.previewBands(HeadphoneEqMode.GRAPHIC_10, List(10) { if (it == 0) 9 else 0 }, null)
        persisted.value = HeadphoneEqState(enabled = true, bands10 = List(10) { if (it == 0) 4 else 0 })
        scope.runCurrent()
        assertThat(controller.settings().gainsDb[0]).isEqualTo(9f)
    }

    @Test
    fun `once storage catches up, or the editor closes, the store owns the curve again`() {
        val controller = controller()
        controller.setEditorOpen(true)
        val bands = List(10) { if (it == 0) 9 else 0 }
        controller.previewBands(HeadphoneEqMode.GRAPHIC_10, bands, null)
        persisted.value = HeadphoneEqState(enabled = true, bands10 = bands)
        scope.runCurrent()
        persisted.value = HeadphoneEqState(enabled = true, bands10 = List(10) { if (it == 0) 2 else 0 })
        scope.runCurrent()
        assertThat(controller.settings().gainsDb[0]).isEqualTo(2f)

        controller.previewBands(HeadphoneEqMode.GRAPHIC_10, List(10) { 5 }, null)
        controller.setEditorOpen(false)
        assertThat(controller.settings().gainsDb[0]).isEqualTo(2f)
    }

    @Test
    fun `a dragged boost carries its own headroom at once, not the persisted curve's (B14-2)`() {
        // The audit: the DSP rendered a preview while the compensation still followed the persisted state, so a
        // quick boost ran with the previous compensation until the debounced write landed. The headroom is now
        // measured from the snapshot the audio thread renders, so there is no second source to be stale.
        val controller = controller()
        val rate = 48_000
        fun headroomOf(settings: GraphicEqSettings) = GraphicEqFilterBank().also {
            it.prepare(rate, 1)
            it.apply(settings)
        }.headroomDb

        val persistedHeadroom = headroomOf(controller.settings())
        assertThat(persistedHeadroom).isEqualTo(0f)

        val dragged = List(10) { 15 }
        controller.previewBands(HeadphoneEqMode.GRAPHIC_10, dragged, null)
        val previewed = headroomOf(controller.settings())
        assertThat(previewed).isGreaterThan(23f)

        // The same bands once they have been written: the identical headroom, whichever route they took.
        persisted.value = HeadphoneEqState(enabled = true, bands10 = dragged)
        scope.runCurrent()
        assertThat(headroomOf(controller.settings())).isEqualTo(previewed)

        // And back down: a drag that lowers the boost lowers the headroom with it, with nothing left over.
        controller.previewBands(HeadphoneEqMode.GRAPHIC_10, List(10) { 0 }, null)
        assertThat(headroomOf(controller.settings())).isEqualTo(0f)
    }

    @Test
    fun `the editor being open is published for the processing requirement`() {
        val controller = controller()
        assertThat(controller.editorOpen.value).isFalse()
        controller.setEditorOpen(true)
        assertThat(controller.editorOpen.value).isTrue()
    }
}
