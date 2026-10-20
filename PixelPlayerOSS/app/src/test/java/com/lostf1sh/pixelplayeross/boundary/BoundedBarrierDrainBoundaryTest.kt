package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-112 follow-up, measured on the HONOR with build fix2: phase 3 of `drainAllUrbs` waited
 * ~0.5 s for completions that never came ("reaped 79 completions" after 528 ms; seek barrier
 * "61/80" after 548 ms). The wait is now bounded, and a URB that is not back is quarantined — the
 * slot gets a fresh struct — never resubmitted while the kernel may still complete it.
 */
class BoundedBarrierDrainBoundaryTest {
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
    private val source = File(jni, "usb-audio-output.cpp").readText().replace("\r\n", "\n")

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun `phase 3 waits only for the discarded URBs, within a bounded time`() {
        assertThat(source).contains("#define URB_DRAIN_COMPLETION_WAIT_MS 50")
        val drain = body("static int drainAllUrbs(")
        assertThat(drain).contains("urbDrainResolve(&pending, c)")
        assertThat(drain).contains("URB_DRAIN_COMPLETION_WAIT_MS")
        // The 500-attempt wait survives only as the fallback when a URB cannot be quarantined.
        val phase3 = drain.substring(drain.indexOf("// Phase 3"), drain.indexOf("// Still missing"))
        assertThat(phase3).doesNotContain("attempt < 500")
    }

    @Test
    fun `a data completion reaped while draining the feedback URB still counts`() {
        val drain = body("static int drainAllUrbs(")
        val feedback = drain.substring(0, drain.indexOf("// Phase 1"))
        assertThat(feedback).contains("urbDrainResolve(&pending, c)")
    }

    @Test
    fun `URBs not back are quarantined and replaced, not reused`() {
        val drain = body("static int drainAllUrbs(")
        assertThat(drain).contains("urbQuarantineAdd(")
        assertThat(drain).contains("ctx->ring[slot].urb = fresh")
    }

    @Test
    fun `every reap site frees a quarantined URB instead of treating it as a completion`() {
        assertThat(body("static int reapOldestUrb(")).contains("urbQuarantineTake(c)")
        assertThat(body("static int drainAllUrbs(").split("urbQuarantineTake(c)").size - 1).isAtLeast(3)
        // The one-shot feedback read reaps through one helper, and the helper is a reap site like the rest.
        assertThat(body("static OwnReap reapUntilOwn(")).contains("urbQuarantineTake(c)")
        assertThat(File(jni, "CMakeLists.txt").readText()).contains("urb-quarantine.cpp")
    }

    /**
     * B14-1, audit A-03: the one-shot feedback read at stream start used to free a URB the kernel could still hand
     * back, with a data buffer on its own stack. The behaviour is pinned by the fault-injection simulation
     * (`feedback-lifetime-test.cpp`, run by `test-direct-sequence-boundaries.sh`); this keeps the shape of the fix
     * from being edited back without that test being looked at.
     */
    @Test
    fun `the one-shot feedback read owns its buffer and frees a URB only after its own completion`() {
        val read = body("static double readFeedback(")
        // The data lives in the URB's own allocation, not in a local that is gone when the completion is written.
        assertThat(read).doesNotContain("uint8_t fb[")
        assertThat(read).contains("(uint8_t *)u + urbBytes")
        // It never reaps by itself: every reap goes through the helper that checks identity.
        assertThat(read).doesNotContain("USBDEVFS_REAPURBNDELAY")
        assertThat(read).contains("reapUntilOwn(ctx, u,")
        // Not back in time: parked, and never touched again.
        assertThat(read).contains("urbQuarantineAdd(u)")
        // Two frees only: the kernel refused the submit (it never held the URB), and after its own completion.
        assertThat(read.split("free(u)").size - 1).isEqualTo(2)
        // The helper identifies the URB by address before it says it came back.
        assertThat(body("static OwnReap reapUntilOwn(")).contains("if (c == own) return OwnReap::REAPED")
    }
}
