package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import java.lang.management.ManagementFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * AUD-113, measured: what one track change costs the composition that reads the album's colour
 * scheme, on the real animation function with a simulated 60 Hz frame clock.
 *
 * The scheme animated from the old cover's colours to the new one's over a spring of ~1 s, and each
 * frame built a new `ColorScheme` (~40 colour fields, each a snapshot state) and handed it to
 * the sheet: 20-60 recompositions of everything that read it — with the queue's 60 rows retained
 * under it — per track change. The device's GC bursts of 15-43 MB fell right after each one.
 */
class SheetThemeAllocationTest {

    private object UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    private class Measurement(val recompositions: Int, val allocatedBytes: Long)

    private val from = darkColorScheme(primary = Color(0xFF4F6BED), secondary = Color(0xFF7A8BCF), surface = Color(0xFF14161B))
    private val to = darkColorScheme(primary = Color(0xFFE0533D), secondary = Color(0xFFCF8B7A), surface = Color(0xFF1B1514))

    private fun allocatedBytes(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean)
            .getThreadAllocatedBytes(Thread.currentThread().id)

    /**
     * One track change: the target moves from [from] to [to], and frames are delivered at 60 Hz for
     * 1.5 s — past the settle time of the spring. Returns how often the reading scope recomposed and
     * how many bytes the whole thing allocated on this thread.
     */
    private fun oneTrackChange(scheme: @androidx.compose.runtime.Composable (ColorScheme) -> ColorScheme): Measurement =
        runBlocking {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val recomposerJob = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(UnitApplier, recomposer)
            var target by mutableStateOf(from)
            var recompositions = 0
            composition.setContent {
                // Read here, as `UnifiedPlayerSheetV2` reads it: this scope recomposes on every new value.
                val current = scheme(target)
                SideEffect { recompositions++; check(current.primary != Color.Unspecified) }
            }
            suspend fun pump() = repeat(6) { Snapshot.sendApplyNotifications(); yield() }
            var nanos = 0L
            suspend fun frames(count: Int) = repeat(count) {
                nanos += 16_666_667L
                clock.sendFrame(nanos)
                pump()
            }
            pump()
            frames(5)                       // settled on the first cover
            System.gc()
            val recompositionsBefore = recompositions
            val bytesBefore = allocatedBytes()
            target = to
            frames(90)
            val measurement = Measurement(recompositions - recompositionsBefore, allocatedBytes() - bytesBefore)
            composition.dispose()
            recomposer.close()
            recomposerJob.cancel()
            measurement
        }

    @Test
    fun `a hidden album scheme settles at once - no frames, no copies (AUD-113)`() {
        // Warm up the JIT and class loading so no measurement is the class loader's.
        oneTrackChange { rememberBatchAnimatedColorScheme(it, animate = true) }
        oneTrackChange { rememberBatchAnimatedColorScheme(it, animate = false) }

        // animate = true is the function as it was: the spring, a new ColorScheme every frame.
        val before = oneTrackChange { rememberBatchAnimatedColorScheme(it, animate = true) }
        val after = oneTrackChange { rememberBatchAnimatedColorScheme(it, animate = false) }
        // What the harness itself costs (95 pumped frames of coroutine and recomposer traffic), so the
        // two can be compared for what the scheme adds.
        val floor = oneTrackChange { it }
        val beforeNet = before.allocatedBytes - floor.allocatedBytes
        val afterNet = after.allocatedBytes - floor.allocatedBytes
        println("AUD113_MEASURE per track change, reading scope: " +
            "animated recompositions=${before.recompositions} allocatedKB=${before.allocatedBytes / 1024} (net ${beforeNet / 1024}) | " +
            "settled recompositions=${after.recompositions} allocatedKB=${after.allocatedBytes / 1024} (net ${afterNet / 1024}) | " +
            "harness floor allocatedKB=${floor.allocatedBytes / 1024}")

        // Measured before the change on the unmodified function: 31 recompositions.
        assertThat(before.recompositions).isAtLeast(20)
        assertThat(after.recompositions).isAtMost(2)
        assertThat(afterNet).isLessThan(beforeNet / 4)
    }

    @Test
    fun `visible, the crossfade still runs and ends on the new scheme`() = runBlocking<Unit> {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val job = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(UnitApplier, recomposer)
        var target by mutableStateOf(from)
        val seen = ArrayList<Color>()
        composition.setContent {
            val scheme = rememberBatchAnimatedColorScheme(target, animate = true)
            SideEffect { seen += scheme.primary }
        }
        suspend fun pump() = repeat(6) { Snapshot.sendApplyNotifications(); yield() }
        pump()
        target = to
        var nanos = 0L
        repeat(120) { nanos += 16_666_667L; clock.sendFrame(nanos); pump() }
        composition.dispose(); recomposer.close(); job.cancel()

        assertThat(seen.first()).isEqualTo(from.primary)
        assertThat(seen.last()).isEqualTo(to.primary)
        // Something in between: it did crossfade.
        assertThat(seen.count { it != from.primary && it != to.primary }).isAtLeast(5)
    }

    @Test
    fun `the queue and the song sheet ask for the crossfade only while they are open`() {
        val sheet = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/UnifiedPlayerSheetV2.kt")
            .readText()
        assertThat(sheet).contains("animateAlbumScheme = showQueueSheet || selectedSongForInfo != null,")
        val theme = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/scoped/SheetThemeState.kt")
            .readText().replace("\r\n", "\n")
        assertThat(theme).contains("rememberBatchAnimatedColorScheme(rawMiniPlayerScheme, animate = false)")
        // The player scheme every other reader takes is not static any more.
        val shared = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/UnifiedPlayerSheetShared.kt")
            .readText()
        assertThat(shared).contains("internal val LocalMaterialTheme = compositionLocalOf<ColorScheme>")
        assertThat(shared).doesNotContain("staticCompositionLocalOf")
    }
}
