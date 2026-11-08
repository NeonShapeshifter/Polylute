package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * B58 9 (#72): the cover follower's two new log lines and nothing else. The behaviour is untouched (the existing carousel tests pass unchanged); the lines are what the next report of
 * "the big cover stopped following" is read from. The carousel itself cannot be driven to the "gave up" end on the JVM (a dragged carousel never idles in Robolectric), so the lines are
 * checked as written, as emitted, and in the place of the follower they are written from.
 */
class B58CoverFollowLogTest {
    private class Recorder : Timber.Tree() {
        val lines = ArrayList<Pair<String?, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lines += tag to message }
    }

    @Test
    fun `the wait line says what the follower waits on`() {
        assertThat(CoverFollowLog.waitingLine("5794", 3, 1, scrolling = false, fingerDown = true)).isEqualTo("COVER_BIND_WAIT song=5794 target=3 attempt=1 why=finger down")
        assertThat(CoverFollowLog.waitingLine("5794", 3, 2, scrolling = true, fingerDown = false)).isEqualTo("COVER_BIND_WAIT song=5794 target=3 attempt=2 why=scroll in progress")
        assertThat(CoverFollowLog.waitingLine(null, 0, 1, scrolling = true, fingerDown = true)).isEqualTo("COVER_BIND_WAIT song=null target=0 attempt=1 why=finger down and scroll in progress")
    }

    @Test
    fun `the gave up line says where the cover is and how many attempts were spent`() {
        assertThat(CoverFollowLog.gaveUpLine("5794", 3, 30, 7, 6)).isEqualTo("COVER_BIND_GAVE_UP song=5794 target=3 of=30 page=7 attempts=6")
    }

    @Test
    fun `both lines go to the CoverBind tag`() {
        val rec = Recorder()
        Timber.plant(rec)
        try {
            CoverFollowLog.waiting("1", 2, 1, scrolling = true, fingerDown = false)
            CoverFollowLog.gaveUp("1", 2, 10, 4, 6)
        } finally {
            Timber.uproot(rec)
        }
        assertThat(rec.lines.map { it.first }).containsExactly("CoverBind", "CoverBind")
        assertThat(rec.lines[0].second).startsWith("COVER_BIND_WAIT ")
        assertThat(rec.lines[1].second).startsWith("COVER_BIND_GAVE_UP ")
    }

    @Test
    fun `the follower writes the wait before it waits and the gave up line after its attempts are spent`() {
        val src = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/AlbumCarouselSelection.kt").readText().replace("\r\n", "\n")
        val wait = src.indexOf("CoverFollowLog.waiting(")
        val firstFlow = src.indexOf("snapshotFlow { carouselState.pagerState.isScrollInProgress || fingerTrace.down }")
        val gaveUp = src.indexOf("CoverFollowLog.gaveUp(")
        val bind = src.indexOf("\"COVER_BIND song=%s")
        assertThat(wait).isGreaterThan(0)
        assertThat(wait).isLessThan(firstFlow)
        assertThat(gaveUp).isGreaterThan(firstFlow)
        assertThat(gaveUp).isLessThan(bind)
        assertThat(src).contains("attempts > MAX_FOLLOW_ATTEMPTS && !carouselState.isAtRestOn(effectiveTargetIndex)")
        // no behaviour change: the follower's own work is as it was (the loop, the jump and the animated scroll)
        assertThat(src).contains("while (attempts++ < MAX_FOLLOW_ATTEMPTS)")
        assertThat(src).contains("carouselState.animateScrollToItem(effectiveTargetIndex)")
    }
}
