package com.lostf1sh.pixelplayeross.ui.glass

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * B41: how the glass follows the song. It waits for the cover to settle (the app's own 350 ms, so skipping fast changes it once, where it
 * stops, not once per song passed), makes the field off the main thread (or takes it from the cache), and cross-fades to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class CoverGlassSceneTest {
    @get:Rule
    val compose = createComposeRule()

    private val graphite = DapBaseTheme.Graphite.palette
    private val band = GlassBands.of(graphite, graphite.accentColor(DapAccent.Sage)).band

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun cover(seed: Int) = Triple(96, 96, IntArray(96 * 96) { i -> rgb((i % 96 * 2 + seed * 40) % 256, (i / 96 * 2 + seed * 70) % 256, (seed * 90) % 256) })

    private val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f -> f.decoder = { uri -> cover(uri.hashCode() and 7) } }

    private var scene: CoverGlassScene? = null

    private fun show(kind: GlassKind?, uris: kotlinx.coroutines.flow.Flow<String?>) {
        compose.setContent { scene = rememberCoverGlassScene(kind, band, uris, fields) }
    }

    private fun awaitField() = compose.waitUntil(10_000) { scene?.crossfade?.current != null }
    private fun awaitSettled() = compose.waitUntil(10_000) { scene?.crossfade?.previous == null && scene?.crossfade?.blend == 1f }

    @Test
    fun `a fast skip makes one transition, to where it stops, not one per song passed`() {
        val uris = MutableStateFlow<String?>(null)
        show(GlassKind.GLASS, uris)
        compose.mainClock.autoAdvance = false
        // five songs in 300 ms: each is the song for 60 ms
        for (i in 1..5) {
            uris.value = "content://art/skip$i"
            compose.mainClock.advanceTimeBy(60)
        }
        // the song it stopped on is the one the glass is made for, after the settle
        compose.mainClock.advanceTimeBy(GlassMotion.SETTLE_MS + 100)
        compose.mainClock.autoAdvance = true
        awaitField()
        awaitSettled()
        assertThat(scene!!.crossfade.transitions).isEqualTo(1)
        assertThat(fields.decodes.get()).isEqualTo(1)
        assertThat(fields.builds.get()).isEqualTo(1)
    }

    @Test
    fun `the glass waits for the cover to settle before it changes`() {
        val uris = MutableStateFlow<String?>(null)
        show(GlassKind.GLASS, uris)
        compose.mainClock.autoAdvance = false
        uris.value = "content://art/settle"
        compose.mainClock.advanceTimeBy(GlassMotion.SETTLE_MS - 50)
        assertThat(scene!!.crossfade.transitions).isEqualTo(0)
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        awaitField()
        assertThat(scene!!.crossfade.transitions).isEqualTo(1)
    }

    @Test
    fun `the cache is used when a cover is shown again`() {
        val uris = MutableStateFlow<String?>("content://art/a")
        show(GlassKind.GLASS, uris)
        awaitField()
        awaitSettled()
        uris.value = "content://art/b"
        compose.waitUntil(10_000) { scene!!.crossfade.transitions == 2 }
        awaitSettled()
        val decodesBefore = fields.decodes.get()
        val buildsBefore = fields.builds.get()
        uris.value = "content://art/a"
        compose.waitUntil(10_000) { scene!!.crossfade.transitions == 3 }
        awaitSettled()
        // shown a second time: a transition, and no decode and no build
        assertThat(fields.decodes.get()).isEqualTo(decodesBefore)
        assertThat(fields.builds.get()).isEqualTo(buildsBefore)
    }

    @Test
    fun `without Cover glass, or without a cover, there is no glass`() {
        val uris = MutableStateFlow<String?>("content://art/none")
        show(null, uris)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertThat(scene!!.crossfade.current).isNull()
        assertThat(scene!!.visible).isFalse()
        assertThat(fields.decodes.get()).isEqualTo(0)
    }

    @Test
    fun `a song with no cover takes the glass away, and the pages are see-through until it is gone`() {
        val uris = MutableStateFlow<String?>("content://art/c")
        show(GlassKind.GLASS, uris)
        awaitField()
        awaitSettled()
        assertThat(scene!!.visible).isTrue()
        uris.value = null
        compose.waitUntil(10_000) { scene!!.crossfade.current == null }
        // still fading out: visible, so the pages do not pop opaque before the glass has gone
        awaitSettled()
        assertThat(scene!!.visible).isFalse()
    }

    @Test
    fun `an unreadable next cover keeps the last complete background until a replacement is ready`() {
        val uris = MutableStateFlow<String?>("content://art/a")
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val replacement = kotlinx.coroutines.CompletableDeferred<Triple<Int, Int, IntArray>?>()
        fields.decoder = { uri ->
            when (uri) {
                "content://art/bad" -> { failed.set(true); null }
                "content://art/b" -> replacement.await()
                else -> cover(1)
            }
        }
        show(GlassKind.GLASS, uris)
        awaitField()
        awaitSettled()
        val previous = scene!!.crossfade.current
        uris.value = "content://art/bad"
        compose.waitUntil(10_000) { failed.get() }
        compose.waitForIdle()
        assertThat(scene!!.crossfade.current).isSameInstanceAs(previous)
        assertThat(scene!!.crossfade.transitions).isEqualTo(1)
        uris.value = "content://art/b"
        compose.mainClock.advanceTimeBy(GlassMotion.SETTLE_MS + 100)
        compose.waitForIdle()
        assertThat(scene!!.crossfade.current).isSameInstanceAs(previous)
        replacement.complete(cover(3))
        compose.waitUntil(10_000) { scene!!.crossfade.current !== previous }
        awaitSettled()
        assertThat(scene!!.visible).isTrue()
        assertThat(scene!!.crossfade.transitions).isEqualTo(2)
    }

    @Test
    fun `a change of backdrop cross-fades to the other one, and the first is kept`() {
        val uris = MutableStateFlow<String?>("content://art/d")
        var kind by mutableStateOf<GlassKind?>(GlassKind.GLASS)
        compose.setContent { scene = rememberCoverGlassScene(kind, band, uris, fields) }
        awaitField()
        awaitSettled()
        val glass = scene!!.crossfade.current!!
        assertThat(glass.moves).isTrue()
        kind = GlassKind.GRADIENT
        compose.waitForIdle()
        compose.waitUntil(10_000) { scene!!.crossfade.current !== glass }
        awaitSettled()
        assertThat(scene!!.crossfade.current!!.moves).isFalse()
        assertThat(scene!!.canMove).isFalse()
    }
}
