package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-122 ("with the DAC connected, crossfade only dips the volume") — verified in B-ux: not
 * reproduced in code. The single-player suppression requires the engine to *want* Direct, and
 * crossfade makes the decided backend ANDROID (UsbPathReservationPolicyTest covers the decision);
 * the incoming player is always built Android-only. The "preserving single-player gapless" line is
 * only ever logged with crossfade off. Green from the start: this pins the wiring so the suppression
 * cannot start reading the DAC's availability alone again.
 */
class CrossfadeDualModeBoundaryTest {
    private val player = "src/main/java/com/lostf1sh/pixelplayeross/data/service/player"
    private val engine = File("$player/DualPlayerEngine.kt").readText()
    private val controller = File("$player/TransitionController.kt").readText()

    @Test
    fun `suppression follows the decided backend and the incoming player never takes Direct`() {
        assertThat(engine).contains("_usbAudioDiagnostics.value.reservesExclusiveUsbPathFor(desiredBackend)")
        assertThat(engine).contains("desiredBackend = decision.backend")
        assertThat(engine).contains("return buildPlayer(allowDirect = false).also { player ->")
        assertThat(controller.split("engine.shouldSuppressOverlapTransitions()").size - 1).isAtLeast(2)
    }
}
