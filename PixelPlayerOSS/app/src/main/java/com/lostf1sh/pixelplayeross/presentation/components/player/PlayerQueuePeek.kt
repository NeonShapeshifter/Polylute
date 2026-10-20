package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import kotlinx.collections.immutable.ImmutableList

/**
 * The song after the one on screen (B32-Q4), by the rule the artwork's skip uses ([predictSkipNextCarouselIndex]): the next in the queue as
 * it is listed (which is play order when shuffle is on), the first again when the queue repeats, none at the end of a queue that does not.
 */
internal fun upNextSong(queue: ImmutableList<Song>, currentIndex: Int?, repeatMode: Int): Song? =
    predictSkipNextCarouselIndex(currentIndex, queue, repeatMode)?.let { queue.getOrNull(it) }

/**
 * How tall the folded queue is above the system's strip (B33-U1): a handle (8 dp above and 4 dp tall) and a 48 dp row, plus the [QueuePeekBottomGap]
 * under it. **The one number the player's layout and the queue sheet share**: the portrait player leaves this much room under the transport, and the
 * queue sheet's folded position shows its top this far above the navigation bar, so the two always line up.
 */
internal val QueuePeekHeight = 12.dp + DapSizes.MinTouchTarget

/** The air under the folded queue, above the navigation bar. */
internal val QueuePeekBottomGap = 8.dp

/**
 * The queue sheet's **folded state** (B33-U1): the first thing the sheet shows when it is not open, at the foot of the portrait full player. A handle
 * and "A continuación" with the next song's title (the label alone when there is none), and a chevron.
 *
 * It is not a second control: it is drawn **by the queue sheet**, at its top, so that dragging it up is dragging the sheet and the sheet
 * unfolds under the finger. The sheet's own header (the title, the count) fades in as this fades out ([QueueFoldedStrip] is given nothing to
 * tap: the sheet's header takes the touches, a tap opens and a drag moves the same sheet). What this adds is what a screen reader needs: one
 * button with a description (the next song) and an accessibility action that opens the queue.
 */
@Composable
internal fun QueueFoldedStrip(
    next: Song?,
    interactive: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(R.string.player_queue_peek_open)
    val label = stringResource(R.string.queue_title_next)
    val nextTitle = next?.let { songTitleLabel(it) }
    val spoken = listOfNotNull(label, nextTitle).joinToString(", ")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                // folded: one button, with what is next said and a way in; open: the strip is not there for a screen reader (the sheet is)
                if (interactive) {
                    role = Role.Button
                    contentDescription = spoken
                    onClick(label = openLabel) { onOpen(); true }
                    customActions = listOf(CustomAccessibilityAction(openLabel) { onOpen(); true })
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(top = DapSpacing.Sm)
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Dap.disabledText),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DapSizes.MinTouchTarget)
                // the player's own margin (its title and its transport start there)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = DapType.Caption,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                )
                if (nextTitle != null) {
                    Text(
                        text = nextTitle,
                        style = DapType.Body,
                        color = Dap.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DapSpacing.Md))
            DapIcon(DapIconKind.ChevronUp, tint = Dap.secondaryText, size = 16.dp)
        }
    }
}
