package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

@Composable
internal fun FullPlayerPortraitContent(
    paddingValues: PaddingValues,
    albumCoverSection: @Composable (Modifier) -> Unit,
    songMetadataSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    controlsSection: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Measure the text and actions first. Artwork takes what is left, up to its own size, including when the user
        // enlarges text; a fixed estimate clipped actions.
        //
        // B25-S5: the block (cover, title, bar, transport) is one piece that starts right under the header, and the spare
        // height is left **below** it. The cover used to be centred in everything that was left and the title, bar and transport
        // pinned to the foot, so on a tall screen the spare height sat between the header and the cover and the controls were as
        // low as they could go: "I prefer space at the bottom rather than space at the top". `fill = false` keeps what the
        // weight was for (the cover yields to the text and the controls when there is not enough room) and no longer makes
        // the cover take room it does not need.
        Box(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            albumCoverSection(Modifier)
        }
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.align(Alignment.Start)) { songMetadataSection() }
            playerProgressSection()
        }
        Spacer(Modifier.height(18.dp))
        controlsSection()
        Spacer(Modifier.height(8.dp))
        // B33-U1: the room the queue sheet's folded state takes under the transport. The sheet draws itself there (it is not part of this
        // layout), so the layout only keeps clear of it: [QueuePeekHeight] and [QueuePeekBottomGap] are the numbers the sheet's folded
        // position uses.
        Spacer(Modifier.height(QueuePeekHeight + QueuePeekBottomGap))
    }
}

@Composable
internal fun FullPlayerLandscapeContent(
    paddingValues: PaddingValues,
    albumCoverSection: @Composable (Modifier) -> Unit,
    songMetadataSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    controlsSection: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(
                horizontal = 24.dp,
                vertical = 0.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        albumCoverSection(
            Modifier
                .fillMaxHeight()
                .weight(1f)
        )
        Spacer(Modifier.width(9.dp))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(
                    horizontal = 0.dp,
                    vertical = 0.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            songMetadataSection()
            playerProgressSection()
            controlsSection()
        }
    }
}
