package com.lostf1sh.pixelplayeross.ui.glass

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: a sheet's own copy of the glass ([StillGlassBackdrop]), against the app's glass behind it.
 *
 *  - **The same glass.** At the same phase and the same veil it draws the very pixels the root's backdrop draws, at rest and in every frame of
 *    a song change (the 350 ms settle, the 400 ms cross-fade): there is one transition, drawn by one piece of code ([GlassFieldStack]).
 *  - **Still.** The root's glass can step on for a minute and the copy does not change a pixel, and nothing in it is recomposed.
 *  - **Builds nothing.** Showing a copy never makes a field: it reads the scene's.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class StillGlassTest {
    @get:Rule
    val compose = createComposeRule()

    private val palette = DapBaseTheme.Graphite.palette
    private val look = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Two covers of the same lightness class (a dark theme's field for each), unlike each other. */
    private fun fieldOf(seed: Int): GlassField {
        val px = IntArray(96 * 96) { i ->
            val x = i % 96
            val y = i / 96
            if (seed == 0) rgb(x * 2, 40 + y, 200 - x) else rgb(220 - y * 2, x, 30 + y)
        }
        return GlassFieldMath.field(GlassFieldMath.grid(px, 96, 96), look.band)
    }

    private val fieldA by lazy { fieldOf(0) }
    private val fieldB by lazy { fieldOf(1) }

    private fun Bitmap.rows(from: Int, to: Int): IntArray {
        val out = IntArray(width * (to - from))
        getPixels(out, 0, width, 0, from, width, to - from)
        return out
    }

    private fun maxDifference(a: IntArray, b: IntArray): Int {
        check(a.size == b.size)
        var worst = 0
        for (i in a.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                worst = maxOf(worst, Math.abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
            }
        }
        return worst
    }

    // ── the same glass, in every frame of a song change ─────────────────────────────────────────────

    /** The root's backdrop in the top half and a sheet's copy in the bottom half, the same size, the same phase, the same veil. */
    @Test
    fun `a copy draws the pixels the app's glass draws, at rest and in every frame of a song change`() {
        val crossfade = GlassCrossfade()
        val rootClock = GlassClock().also { it.advance(23f) }
        val scene = CoverGlassScene(crossfade, rootClock, look.band.base)
        val frozen = rootClock.frozenCopy()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) {
                crossfade.show(fieldA)
                crossfade.show(fieldB)
            }
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(400.dp).clipToBounds()) {
                    CoverGlassBackdrop(crossfade, rootClock, look.band.base, expansion = { 0f })
                }
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    StillGlassBackdrop(scene, frozen, scrimAlpha = GlassScrim.PAGE_ALPHA)
                }
            }
        }
        var compared = 0
        var sawMidFade = false
        repeat(40) {
            compose.mainClock.advanceTimeBy(50)
            val shot = compose.onRoot().captureToImage().asAndroidBitmap()
            val half = shot.height / 2
            val root = shot.rows(0, half)
            val copy = shot.rows(half, 2 * half)
            assertThat(maxDifference(root, copy)).isAtMost(1)
            compared++
            if (crossfade.previous != null && crossfade.blend in 0.1f..0.9f) sawMidFade = true
        }
        println("B42 | the copy equals the app's glass in $compared frames (cross-fade seen mid-way: $sawMidFade)")
        assertThat(sawMidFade).isTrue()
        assertThat(crossfade.current).isSameInstanceAs(fieldB)
    }

    @Test
    fun `a copy is the app's glass at the phase it was taken, and never moves after`() {
        val crossfade = GlassCrossfade()
        val rootClock = GlassClock().also { it.advance(14f) }
        val scene = CoverGlassScene(crossfade, rootClock, look.band.base)
        val frozen = rootClock.frozenCopy()
        compose.setContent {
            LaunchedEffect(Unit) { crossfade.show(fieldA) }
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(400.dp).clipToBounds()) {
                    CoverGlassBackdrop(crossfade, rootClock, look.band.base, expansion = { 0f })
                }
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    StillGlassBackdrop(scene, frozen, scrimAlpha = GlassScrim.PAGE_ALPHA)
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        val half = before.height / 2
        val rootBefore = before.rows(0, half)
        val copyBefore = before.rows(half, 2 * half)
        assertThat(maxDifference(rootBefore, copyBefore)).isAtMost(1)

        // the player's glass steps on for a minute at 60 frames a second, a frame at a time
        repeat(60) {
            compose.runOnIdle { repeat(60) { rootClock.advance(1f / 60f) } }
            compose.waitForIdle()
        }
        assertThat(rootClock.phase).isWithin(0.5f).of(74f)
        assertThat(frozen.phase).isEqualTo(14f)
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        val rootAfter = after.rows(0, half)
        val copyAfter = after.rows(half, 2 * half)
        // the app's glass moved; the copy did not change a pixel
        val rootMoved = maxDifference(rootBefore, rootAfter)
        val copyMoved = maxDifference(copyBefore, copyAfter)
        println("B42 | after a minute of the player's glass: the app's moved by up to $rootMoved levels, the copy by $copyMoved")
        assertThat(rootMoved).isAtLeast(3)
        assertThat(copyMoved).isEqualTo(0)
    }

    // ── what it costs ───────────────────────────────────────────────────────────────────────────────

    private class Counter : CompositionTracer {
        val runs = mutableMapOf<String, Int>()
        override fun isTraceInProgress(): Boolean = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            runs[name] = (runs[name] ?: 0) + 1
        }
        override fun traceEventEnd() {}
    }

    @Test
    fun `while the player's glass steps on every frame, a copy is not recomposed`() {
        val crossfade = GlassCrossfade()
        val rootClock = GlassClock()
        val scene = CoverGlassScene(crossfade, rootClock, look.band.base)
        compose.setContent {
            LaunchedEffect(Unit) { crossfade.show(fieldA) }
            val frozen = rememberStillGlassClock(scene)!!
            StillGlassBackdrop(scene, frozen)
        }
        compose.waitForIdle()
        val counter = Counter()
        Composer.setTracer(counter)
        try {
            repeat(120) {
                compose.runOnIdle { rootClock.advance(1f / 60f) }
                compose.waitForIdle()
            }
            val recomposed = counter.runs.filterKeys { it.startsWith("StillGlass") || it.startsWith("Glass") }
            println("B42 | recompositions of the copy in 120 steps of the app's glass: $recomposed")
            assertThat(rootClock.phase).isGreaterThan(1.9f)
            assertThat(recomposed).isEmpty()
        } finally {
            Composer.setTracer(object : CompositionTracer {
                override fun isTraceInProgress() = false
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {}
                override fun traceEventEnd() {}
            })
        }
    }

    @Test
    fun `taking the clock never subscribes the caller to the one that moves`() {
        val rootClock = GlassClock().also { it.advance(5f) }
        var compositions = 0
        compose.setContent {
            compositions++
            val copy = remember(rootClock) { rootClock.frozenCopy() }
            assertThat(copy.phase).isEqualTo(5f)
        }
        compose.waitForIdle()
        repeat(30) { compose.runOnIdle { rootClock.advance(0.5f) }; compose.waitForIdle() }
        assertThat(compositions).isEqualTo(1)
    }

    // ── builds nothing ──────────────────────────────────────────────────────────────────────────────

    private fun cover(seed: Int) = Triple(96, 96, IntArray(96 * 96) { i -> rgb((i % 96 * 2 + seed * 40) % 256, (i / 96 * 2 + seed * 70) % 256, (seed * 90) % 256) })

    @Test
    fun `showing and hiding copies, again and again, makes no field and no transition`() {
        val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f -> f.decoder = { uri -> cover(uri.hashCode() and 7) } }
        val uris = MutableStateFlow<String?>("content://art/sheets")
        var scene: CoverGlassScene? = null
        val showCopies = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val s = rememberCoverGlassScene(GlassKind.GLASS, look.band, uris, fields)
            scene = s
            CompositionLocalProvider(LocalCoverGlass provides s) {
                if (showCopies.value && s.visible) {
                    val clock = rememberStillGlassClock(s)!!
                    StillGlassBackdrop(s, clock)
                }
            }
        }
        compose.waitUntil(10_000) { scene?.crossfade?.current != null }
        compose.waitUntil(10_000) { scene?.crossfade?.previous == null && scene?.crossfade?.blend == 1f }
        val decodes = fields.decodes.get()
        val builds = fields.builds.get()
        val transitions = scene!!.crossfade.transitions
        assertThat(builds).isEqualTo(1)
        repeat(5) {
            compose.runOnIdle { showCopies.value = true }
            compose.waitForIdle()
            compose.runOnIdle { showCopies.value = false }
            compose.waitForIdle()
        }
        println("B42 | five opens and closes of a copy: decodes $decodes -> ${fields.decodes.get()}, builds $builds -> ${fields.builds.get()}, transitions $transitions -> ${scene!!.crossfade.transitions}")
        assertThat(fields.decodes.get()).isEqualTo(decodes)
        assertThat(fields.builds.get()).isEqualTo(builds)
        assertThat(scene!!.crossfade.transitions).isEqualTo(transitions)
    }

    @Test
    fun `a song change while copies are shown is one transition, and the copies make no field of their own`() {
        val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f -> f.decoder = { uri -> cover(uri.hashCode() and 7) } }
        val uris = MutableStateFlow<String?>("content://art/one")
        var scene: CoverGlassScene? = null
        compose.setContent {
            val s = rememberCoverGlassScene(GlassKind.GLASS, look.band, uris, fields)
            scene = s
            CompositionLocalProvider(LocalCoverGlass provides s) {
                if (s.visible) {
                    val clock = rememberStillGlassClock(s)!!
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(400.dp)) { StillGlassBackdrop(s, clock) }
                        Box(Modifier.fillMaxWidth().height(400.dp)) { StillGlassBackdrop(s, clock) }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { scene?.crossfade?.current != null }
        compose.waitUntil(10_000) { scene?.crossfade?.previous == null && scene?.crossfade?.blend == 1f }
        val firstDecodes = fields.decodes.get()
        val firstBuilds = fields.builds.get()
        val transitions = scene!!.crossfade.transitions
        uris.value = "content://art/two"
        compose.waitUntil(10_000) { scene!!.crossfade.transitions == transitions + 1 }
        compose.waitUntil(10_000) { scene!!.crossfade.previous == null && scene!!.crossfade.blend == 1f }
        // one transition for the song, one field made for it (by the root's scene, once), and the two copies drew it
        assertThat(scene!!.crossfade.transitions).isEqualTo(transitions + 1)
        assertThat(fields.builds.get()).isEqualTo(firstBuilds + 1)
        assertThat(fields.decodes.get()).isEqualTo(firstDecodes + 1)
    }

    @Test
    fun `no field is made by the glass files a sheet uses`() {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "src/main/AndroidManifest.xml").isFile }
            .resolve("src/main/java/com/lostf1sh/pixelplayeross")
        val still = java.io.File(dir, "ui/glass/StillGlass.kt").readText()
        val wrapper = java.io.File(dir, "presentation/components/GlassModalBottomSheet.kt").readText()
        val queue = java.io.File(dir, "presentation/components/QueueGlass.kt").readText()
        for ((name, source) in listOf("StillGlass.kt" to still, "GlassModalBottomSheet.kt" to wrapper, "QueueGlass.kt" to queue)) {
            for (forbidden in listOf("CoverGlassFields", "fieldFor", "GlassFieldMath", "GlassCrossfade()", ".show(", "rememberCoverGlassScene", "GlassTicker", ".advance(")) {
                assertThat(source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
                    .joinToString("\n")).doesNotContain(forbidden)
            }
            assertThat("$name has no `.phase`").isNotEmpty()
        }
        // nothing a sheet draws reads the clock that moves: the only clock it touches is a frozen copy of it
        assertThat(still.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")).doesNotContain(".phase")
    }
}
