package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B29-fix1: where the changes are wired, read at the source (what each one does is measured by its own test).
 */
class B29Fix1WiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun code(path: String) = File(main, path).readText().replace("\r\n", "\n").lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trim().let { line -> line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    @Test
    fun `F1 the release counts the lift's own position and the gesture's own speed, and the offset is still written in the callback`() {
        val lock = code("presentation/lockscreen/DapLockScreen.kt")
        assertThat(lock).contains("finger.onLift(")
        assertThat(lock).contains("offset.drag(liftedPastTheDrag)")
        assertThat(lock).contains("minOf(tracked, finger.averageVelocityY())")
        // the lift is seen where every event of the gesture is seen first, and nothing is consumed there
        val observer = lock.substringAfter("PointerEventPass.Initial").substringBefore("detectVerticalDragGestures")
        assertThat(observer).doesNotContain("consume(")
        // still no coroutine between the finger and the offset
        assertThat(lock.substringAfter("onVerticalDrag = {").substringBefore("onDragEnd")).doesNotContain("launch")
    }

    @Test
    fun `F2 previous reads the position and the neighbour from one player, and hands a lagging controller the whole decision`() {
        val holder = code("presentation/viewmodel/PlaybackStateHolder.kt")
        val previous = holder.substringAfter("fun previousSong()").substringBefore("fun previousDecisionPositionMs")
        assertThat(previous).contains("val authority = previousAuthority(controller)")
        assertThat(previous).contains("authority.currentPosition")
        assertThat(previous).contains("authority.hasPreviousMediaItem()")
        // the neighbour is not asked of the controller on its own any more
        assertThat(previous).doesNotContain("controller.hasPreviousMediaItem()")
        assertThat(previous).contains("controller.seekToPrevious()")
        assertThat(previous).contains("controller.seekToPreviousMediaItem()")
    }

    @Test
    fun `F3 the genre page has one order and no sort, so no sort can finish out of order`() {
        // B33-U4: the genre page lists its songs by title and has no sort control; what F3 guarded (the newest sort owning the list) went with it
        val vm = code("presentation/viewmodel/GenreDetailViewModel.kt")
        assertThat(vm).doesNotContain("updateSortOption")
        assertThat(vm).doesNotContain("sortTicket")
        assertThat(vm).doesNotContain("sortJob")
        assertThat(vm).contains("songs = SourceOrder.genrePage(songs)")
    }

    @Test
    fun `F4 every publisher of the global palette asks whether its request is still the current one`() {
        val holder = code("presentation/viewmodel/ThemeStateHolder.kt")
        val writers = holder.lines().filter { it.contains("_currentAlbumArtColorSchemePair.value =") && !it.contains("= null") }
        // the ordinary request, the preferences refresh and the forced regeneration
        assertThat(writers).hasSize(3)
        // (B30-fix1 H4: the question is asked of the request as a whole: cover, generation, style, accuracy, settings epoch, regeneration)
        assertThat(holder).contains("if (isCurrentRequest(request)) _currentAlbumArtColorSchemePair.value = refreshedScheme")
        assertThat(holder).contains("_currentAlbumArtUri.value == uriString && isCurrentRequest(request)")
    }

    @Test
    fun `F5 and F6 the scrollbar measures its label at its own size, and draws a thumb and no track`() {
        val bar = code("presentation/components/ExpressiveScrollBar.kt")
        assertThat(bar).contains("Constraints.fixed(side, side)")
        assertThat(bar).doesNotContain("trackGap")
        assertThat(bar).doesNotContain("trackStroke")
        assertThat(bar).doesNotContain("drawLine(")
    }

    @Test
    fun `F7 the queue sheet is given the cover's accent`() {
        assertThat(code("presentation/components/QueueSurface.kt")).contains("LocalDapAccent provides queueAccent")
    }
}
