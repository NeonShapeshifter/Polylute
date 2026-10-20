package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.OrderedTestTimeline
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import org.junit.jupiter.api.Test

/**
 * B17-H1. The session player's wrapper timeline used to answer "which window comes next" with the base class's
 * "the next index", so the shuffle order the player holds never reached a controller.
 */
class MappedTimelineTest {

    private fun mapped(vararg order: Int) = MappedTimeline(OrderedTestTimeline.of(*order)) { it }

    @Test
    fun `a controller reading the wrapped timeline sees the shuffle order`() {
        assertThat(PlayOrder.of(mapped(2, 0, 3, 1), shuffleEnabled = true)).isEqualTo(intArrayOf(2, 0, 3, 1))
    }

    @Test
    fun `first, last, next and previous are the delegate's, with and without shuffle`() {
        val timeline = mapped(2, 0, 3, 1)
        assertThat(timeline.getFirstWindowIndex(true)).isEqualTo(2)
        assertThat(timeline.getLastWindowIndex(true)).isEqualTo(1)
        assertThat(timeline.getNextWindowIndex(0, Player.REPEAT_MODE_OFF, true)).isEqualTo(3)
        assertThat(timeline.getPreviousWindowIndex(0, Player.REPEAT_MODE_OFF, true)).isEqualTo(2)
        assertThat(timeline.getNextWindowIndex(1, Player.REPEAT_MODE_OFF, true)).isEqualTo(C.INDEX_UNSET)
        assertThat(timeline.getNextWindowIndex(1, Player.REPEAT_MODE_ALL, true)).isEqualTo(2)

        assertThat(timeline.getFirstWindowIndex(false)).isEqualTo(0)
        assertThat(timeline.getNextWindowIndex(0, Player.REPEAT_MODE_OFF, false)).isEqualTo(1)
    }

    @Test
    fun `items still go through the mapping`() {
        val replacement = MediaItem.Builder().setMediaId("mapped").build()
        val timeline = MappedTimeline(OrderedTestTimeline.of(0, 1)) { replacement }
        val window = timeline.getWindow(0, Timeline.Window())
        assertThat(window.mediaItem.mediaId).isEqualTo("mapped")
    }

    @Test
    fun `the player hands controllers the forwarding timeline, not an anonymous one`() {
        val source = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/MappingPlayer.kt")
            .let { if (it.isFile) it else java.io.File("app/${it.path}") }.readText()
        assertThat(source).contains("MappedTimeline(timeline)")
        assertThat(source).doesNotContain("object : Timeline()")
    }
}
