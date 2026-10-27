package com.lostf1sh.pixelplayeross.ui.glass

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.management.ManagementFactory
import org.junit.Test

/**
 * B41-fix1 (B4): what one step of the glass costs the main thread. It is stepped on every frame of the display now (60 to 120 a second), so
 * what each of the two moving layers' lambdas does per frame is the cost of the glass.
 *
 * B41 built the whole list of three layers in each lambda (`GlassMotion.layers(clock.phase)[index]`): two lists and six layers, with the
 * sines of all three, where two layers are needed. The layers' own lambda now asks for its own layer ([GlassMotion.layer]).
 */
class B41Fix1GlassStepCostTest {
    private val backdrop = File("src/main/java/com/lostf1sh/pixelplayeross/ui/glass/CoverGlassBackdrop.kt").readText().replace("\r\n", "\n")

    @Test
    fun `the layers' lambdas ask for their own layer, not the list of all three`() {
        val lambda = backdrop.substringAfter("Modifier.graphicsLayer {\n").substringBefore("this.alpha = layer.alpha")
        assertThat(lambda).contains("GlassMotion.layer(clock.phase, index)")
        assertThat(lambda).doesNotContain("GlassMotion.layers(")
        assertThat(backdrop).doesNotContain("GlassMotion.layers(")
    }

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun bytesPerCall(calls: Int, block: (Float) -> Unit): Long {
        val id = Thread.currentThread().id
        var sink = 0f
        repeat(20_000) { block(it * 0.01f) }          // warm
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until calls) { block(i * 0.01f); sink += 1f }
        val bytes = (bean.getThreadAllocatedBytes(id) - before) / calls
        assertThat(sink).isGreaterThan(0f)
        return bytes
    }

    @Test
    fun `a layer at a phase allocates one layer, where the list of three allocated the list and every layer in it`() {
        var keep: Any? = null
        val one = bytesPerCall(200_000) { keep = GlassMotion.layer(it, 1) }
        val list = bytesPerCall(200_000) { keep = GlassMotion.layers(it)[1] }
        println("B41-fix1 B4 | per layer per frame: ${one} bytes with layer(phase, index), ${list} bytes with layers(phase)[index] (the base layer is never allocated)")
        assertThat(keep).isNotNull()
        // (each figure includes the 16 bytes of the boxed phase this harness passes in) a Layer is five floats and a boolean behind a header;
        // the list adds a second one, the base layer's, and its own storage
        assertThat(one).isAtMost(64L)
        assertThat(list).isAtLeast(2 * one)
    }
}
