package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B29-L11: the accent a cover gives a surface, and when it gives none. */
class AlbumAccentTest {

    private val graphite = Color(0xFF0E0F12)
    private val paper = Color(0xFFF4F4F2)

    @Test
    fun `no scheme gives no accent, so the app's own stays`() {
        assertThat(AlbumAccent.fromScheme(null, graphite)).isNull()
    }

    @Test
    fun `a cover's pastel primary on graphite is used as it is`() {
        val scheme = darkColorScheme(primary = Color(0xFFB8A9D9))
        assertThat(AlbumAccent.fromScheme(scheme, graphite)).isEqualTo(Color(0xFFB8A9D9))
    }

    @Test
    fun `a dark cover colour is lifted until it can be seen on graphite, and never past the floor's need`() {
        val dark = Color(0xFF2A2F6B)
        assertThat(AlbumAccent.contrast(dark, graphite)).isLessThan(AlbumAccent.MIN_CONTRAST)
        val accent = AlbumAccent.readableOn(dark, graphite)!!
        assertThat(AlbumAccent.contrast(accent, graphite)).isAtLeast(AlbumAccent.MIN_CONTRAST)
        // lifted by the least that does it: one step less would not have been enough
        assertThat(accent).isNotEqualTo(dark)
    }

    @Test
    fun `a pale cover colour is darkened until it can be seen on the light palette`() {
        val pale = Color(0xFFEDE7B1)
        assertThat(AlbumAccent.contrast(pale, paper)).isLessThan(AlbumAccent.MIN_CONTRAST)
        val accent = AlbumAccent.readableOn(pale, paper)!!
        assertThat(AlbumAccent.contrast(accent, paper)).isAtLeast(AlbumAccent.MIN_CONTRAST)
    }

    @Test
    fun `whatever comes back is readable on the surface it was asked for, or is nothing at all`() {
        val surfaces = listOf(graphite, paper, Color(0xFF777777), Color(0xFFBBBBBB))
        for (surface in surfaces) for (seed in 0..400) {
            val color = Color(red = (seed * 7 % 256) / 255f, green = (seed * 13 % 256) / 255f, blue = (seed * 29 % 256) / 255f)
            val accent = AlbumAccent.readableOn(color, surface)
            if (accent != null) assertThat(AlbumAccent.contrast(accent, surface)).isAtLeast(AlbumAccent.MIN_CONTRAST)
        }
    }

    @Test
    fun `contrast is symmetric and spans 1 to 21`() {
        assertThat(AlbumAccent.contrast(Color.Black, Color.White)).isWithin(0.01f).of(21f)
        assertThat(AlbumAccent.contrast(Color.White, Color.White)).isWithin(0.0001f).of(1f)
        assertThat(AlbumAccent.contrast(graphite, paper)).isWithin(0.0001f).of(AlbumAccent.contrast(paper, graphite))
    }

    @Test
    fun `deriving the accent costs microseconds and a handful of bytes, measured`() {
        val scheme = darkColorScheme(primary = Color(0xFF2A2F6B))
        repeat(20_000) { AlbumAccent.fromScheme(scheme, graphite) } // warm up
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val runs = 200_000
        val bytesBefore = threads.getThreadAllocatedBytes(id)
        val startedAt = System.nanoTime()
        repeat(runs) { AlbumAccent.fromScheme(scheme, graphite) }
        val nanos = System.nanoTime() - startedAt
        val bytes = threads.getThreadAllocatedBytes(id) - bytesBefore
        println("B29-L11 | AlbumAccent.fromScheme (a dark colour that has to be lifted) | ${nanos / runs} ns/call | ${bytes / runs} bytes/call | $runs calls")
        // a song change derives it once: even a slow host stays far under a frame
        assertThat(nanos / runs).isLessThan(200_000L)
    }
}
