package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.headphones.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GraphicEqPreviewOwnershipTest {
    @Test fun `profile switch with identical persisted curves discards preview and rejects stale owner`() = runTest {
        val eq = HeadphoneEqState(enabled = true)
        val a = HeadphoneProfile("a", "A", eq, 1, 1)
        val b = a.copy(profileId = "b", displayName = "B")
        val catalog = MutableStateFlow(HeadphoneProfileState(listOf(a, b), "a"))
        val repository = mockk<HeadphoneProfileRepository> { every { state } returns catalog }
        // This stream deliberately never changes: A and B's equalizer values are equal. Ownership
        // must follow the catalog, not an equality-deduplicated curve stream.
        val coordinator = mockk<HeadphoneEqualizerCoordinator> {
            every { effectiveStateFlow } returns MutableStateFlow(eq)
        }
        val dsp = HeadphoneGraphicEqController(coordinator, mockk<MaintenanceOverrideStore>(relaxed = true), backgroundScope, repository)
        runCurrent()
        dsp.previewBands(HeadphoneEqMode.GRAPHIC_10, List(10) { 9 }, "a")
        assertEquals(9f, dsp.settings().gainsDb[0])
        catalog.value = catalog.value.copy(selectedProfileId = "b")
        // No scheduler yield: the audio read must never apply A's preview in this race window.
        assertTrue(dsp.settings().isBypass)
        runCurrent()
        assertEquals(List(10) { 0f }, dsp.settings().gainsDb.toList())
        dsp.previewBands(HeadphoneEqMode.GRAPHIC_10, List(10) { 15 }, "a")
        assertEquals(List(10) { 0f }, dsp.settings().gainsDb.toList())
        dsp.previewBands(HeadphoneEqMode.GRAPHIC_10, List(10) { -4 }, "b")
        assertEquals(-4f, dsp.settings().gainsDb[0])
        catalog.value = catalog.value.copy(selectedProfileId = "a")
        runCurrent()
        assertEquals(List(10) { 0f }, dsp.settings().gainsDb.toList())
    }
}
