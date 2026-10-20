package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U1: the queue's rows. The reorder handle keeps the left content margin and its 48 dp touch target, and now has a clear gap between its
 * glyph and the album thumbnail: [QueueHandleArtGap] = [DapSpacing.Md] (12 dp), the DS's gap between a row's leading element and its art. The title
 * still gets more width than before B32 (the list's extra 8 dp of start padding stays gone). Drawn the way the queue sheet lays them out, at the
 * HONOR's width (360 dp, 2 px per dp), with real touches.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B33QueueRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val touch by lazy { SyntheticTouch(compose) }
    private val px = 2f

    private var played = 0
    private var handleTaps = 0
    private var dragStarts = 0
    private val dismissed = mutableListOf<String>()

    private fun song(title: String, artist: String) = Song.emptySong().copy(id = title, title = title, artist = artist)

    private fun show() = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(Modifier.fillMaxWidth().background(Dap.background).padding(start = QueueListStartPadding, end = 8.dp)) {
                listOf(true, false, false).forEachIndexed { index, current ->
                    QueuePlaylistSongItem(
                        onClick = { played++ },
                        song = song(if (current) "(Irreversible)" else if (index == 1) "(Mentiras Piadosas)" else "2 Of Amerikaz Most Wanted", if (index == 2) "2Pac, Snoop Dogg" else "Dillom"),
                        isCurrentSong = current, isPlaying = false, isDragging = false, onRemoveClick = {},
                        dragHandle = {
                            IconButton(
                                onClick = { handleTaps++ },
                                modifier = Modifier
                                    .pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { dragStarts++ }) { _, _ -> } }
                                    .size(48.dp),
                            ) { Icon(Icons.Rounded.DragIndicator, "Reordenar", tint = Dap.disabledText) }
                        },
                        isReorderModeEnabled = false, onMoreOptionsClick = {}, isDragHandleVisible = !current,
                        isRemoveButtonVisible = false, isFromPlaylist = true,
                        // the queue offers the swipe on the rows after the one that is playing
                        enableSwipeToDismiss = !current, swipeStateIdentity = index.toLong(), onDismissSong = { dismissed += if (index == 1) "second" else "third" },
                    )
                }
            }
        }
    }

    private val handle get() = compose.onAllNodesWithContentDescription("Reordenar").onFirst().fetchSemanticsNode().boundsInRoot
    private val glyph get() = compose.onAllNodesWithContentDescription("Reordenar", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
    /** The thumbnail's own box: the image the semantics name is inset in it, so its left is its centre less half of the 48 dp box. */
    private fun artLeftDp(title: String) =
        compose.onNodeWithContentDescription(context.getString(R.string.cd_album_art_for_title, title), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center.x / px - 24f
    private fun titleLeftDp(title: String) = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left / px

    @Test
    fun `the handle keeps the 20 dp margin and its 48 dp touch target`() {
        show()
        val handle = handle
        assertThat(handle.width / px).isWithin(0.5f).of(48f)
        assertThat(handle.height / px).isAtLeast(48f)
        assertThat(handle.left / px).isWithin(0.5f).of(8f)
        // the centred 24 dp glyph is on the 20 dp margin
        assertThat((handle.left + handle.width / 2f) / px - 12f).isWithin(0.5f).of(20f)
    }

    @Test
    fun `a clear gap, the DS's 12 dp, separates the handle's glyph from the thumbnail`() {
        show()
        val glyphRight = (handle.left + handle.width / 2f + 12f * px) / px
        val gap = artLeftDp("(Mentiras Piadosas)") - glyphRight
        assertThat(gap).isWithin(0.5f).of(12f)
        assertThat(QueueHandleArtGap).isEqualTo(DapSpacing.Md)
        // and it is more than the 0 dp it was
        assertThat(gap).isGreaterThan(8f)
        // the touch target no longer overlaps the artwork: the artwork begins where the target ends
        assertThat(artLeftDp("(Mentiras Piadosas)")).isAtLeast(handle.right / px - 0.5f)
    }

    @Test
    fun `the title still gets more width than before B32, 8 dp earlier than the 124 dp it started at`() {
        show()
        // 8 dp row start + 48 dp handle + 48 dp artwork + 12 dp gap = 116 dp; before B32: 16 + 48 + 48 + 12 = 124 dp
        assertThat(titleLeftDp("(Mentiras Piadosas)")).isWithin(1f).of(116f)
        assertThat(titleLeftDp("(Mentiras Piadosas)")).isLessThan(124f)
        // the current row, whose handle is not offered, keeps the same slot: its title is on the same line as the others'
        assertThat(titleLeftDp("(Irreversible)")).isWithin(0.5f).of(titleLeftDp("(Mentiras Piadosas)"))
    }

    @Test
    fun `a tap on the handle is the handle's, a tap on the thumbnail or the text plays`() {
        show()
        val handle = handle
        touch.tap(Offset(handle.left + 10f, handle.center.y))
        touch.tap(Offset(handle.right - 4f, handle.center.y))
        assertThat(handleTaps).isEqualTo(2)
        assertThat(played).isEqualTo(0)
        // the thumbnail's first dp, right after the target
        touch.tap(Offset(handle.right + 4f, handle.center.y))
        assertThat(played).isEqualTo(1)
        touch.tap(Offset(240f * px, handle.center.y))
        assertThat(played).isEqualTo(2)
    }

    @Test
    fun `reorder still starts from the handle, over the whole 48 dp`() {
        show()
        val handle = handle
        touch.swipe(Offset(handle.center.x, handle.center.y), dyPx = 160f, millis = 200, delivery = Delivery.FRAMED)
        touch.swipe(Offset(handle.left + 46f * px, handle.center.y), dyPx = 160f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(dragStarts).isEqualTo(2)
        assertThat(played).isEqualTo(0)
    }

    @Test
    fun `swiping a row to the left dismisses it, and a short swipe does not`() {
        show()
        val row = titleLeftDp("(Mentiras Piadosas)") * px
        val y = compose.onNodeWithText("(Mentiras Piadosas)", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
        touch.swipe(Offset(row + 300f, y), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -60f)
        assertThat(dismissed).isEmpty()
        touch.swipe(Offset(row + 400f, y), dyPx = 0f, millis = 400, delivery = Delivery.FRAMED, dxPx = -600f)
        assertThat(dismissed).containsExactly("second")
        // the swipe on one row is not a tap on it
        assertThat(played).isEqualTo(0)
    }
}
