package com.lostf1sh.pixelplayeross.ui.glass

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion
import java.io.File
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Test

/**
 * B56 phase 7.1: what one frame of the live background costs **on the Kotlin side**, on the host. The JVM cannot run AGSL, so the shader's own work (the fragment program over the
 * screen, the uniform calls into the platform) is measured on the device; what is here is the part that is plain Kotlin: the clock's step, the classic layers' numbers, and the
 * live path's per-frame code (which is one clock read and two uniform sets: the source is checked for that). The figures are printed and written to
 * `B56-gates/phase7-1-frame-cost.log`.
 */
class B56BackgroundMotionCostProbe {
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun measure(label: String, calls: Int, block: (Int) -> Unit): Pair<Double, Long> {
        val id = Thread.currentThread().id
        repeat(300_000) { block(it) }
        val before = bean.getThreadAllocatedBytes(id)
        val t0 = System.nanoTime()
        for (i in 0 until calls) block(i)
        val ns = (System.nanoTime() - t0).toDouble() / calls
        val bytes = (bean.getThreadAllocatedBytes(id) - before) / calls
        println("B56 7.1 | $label: %.1f ns and $bytes bytes per frame".format(ns))
        return ns to bytes
    }

    @Test
    fun `a frame costs the clocks step and on the classic path two layers numbers and the live path adds no allocation`() {
        val lines = mutableListOf<String>("(host JVM, Kotlin side only; every figure includes the harness's own 15 bytes per call; the shader's GPU time and uniform calls are the device's)")
        fun record(label: String, r: Pair<Double, Long>) { lines += "%-58s %8.1f ns/frame %5d bytes/frame".format(label, r.first, r.second) }

        val clock = GlassClock().apply { motion = BackgroundMotion.GENTLE }
        record("clock.step (Gentle, both paths)", measure("clock.step Gentle", 2_000_000) { clock.step(0.033f) })
        val lively = GlassClock().apply { motion = BackgroundMotion.LIVELY }
        record("clock.step (Lively)", measure("clock.step Lively", 2_000_000) { lively.step(0.033f) })
        val still = GlassClock().apply { motion = BackgroundMotion.STILL }
        val stillCost = measure("clock.step Still", 2_000_000) { still.step(0.033f) }
        record("clock.step (Still: the ticker never calls it; a call does no work past the speed 0 check)", stillCost)

        var keep: Any? = null
        record("classic path: two layers (Gentle travel)", measure("classic two layers Gentle", 1_000_000) { i -> keep = GlassMotion.layer(i * 0.033f, 1, 1f); keep = GlassMotion.layer(i * 0.033f, 2, 1f) })
        record("classic path: two layers (Lively travel)", measure("classic two layers Lively", 1_000_000) { i -> keep = GlassMotion.layer(i * 0.033f, 1, 1.4f); keep = GlassMotion.layer(i * 0.033f, 2, 1.4f) })
        assertThat(keep).isNotNull()

        // Still runs no step at all: the ticker is not started (B41), and the clock holds its phase
        assertThat(still.phase).isEqualTo(0f)

        // the live path's draw code: the source says what it does per frame
        val live = File("src/main/java/com/lostf1sh/pixelplayeross/ui/glass/LiveBlobs.kt").readText()
        val draw = live.substringAfter("drawBehind {").substringBefore("drawRect(brush)")
        val perFrame = draw.substringAfter("pushed.height = size.height\n            }\n")
        assertThat(perFrame.lines().filter { it.isNotBlank() }.map { it.trim() }).containsExactly(
            "shader.setFloatUniform(\"time\", clock.phase)",
            "shader.setFloatUniform(\"travel\", clock.motion.travel.coerceAtLeast(0.0001f))",
        )
        lines += "live path (Android 13+): per frame = one clock read + two uniform sets + one drawRect; no list, no array, no allocation of its own"
        lines += "frame rate: ${LiveBlobs.FPS} a second (${LiveBlobs.FRAME_NANOS} ns between steps)"

        val out = File(System.getenv("B56_GATES") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates")
        out.mkdirs()
        File(out, "phase7-1-frame-cost.log").writeText(lines.joinToString("\n", postfix = "\n"))
        assertThat(stillCost.first).isLessThan(2_000.0)
    }
}
