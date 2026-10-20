package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.size.Size
import com.lostf1sh.pixelplayeross.presentation.components.BackRowHeight
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapHeroTopScrim
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/** The height of a header with a cover: the album page's own (its largest, before it collapses), from the top edge of the screen. */
private val GenreHeaderHeight = 300.dp

/**
 * The top of the genre page, shaped like an album's (B31 P3.2): the cover of the genre's biggest album behind its name, the counts line
 * under it, then Play and Shuffle as the album and the playlist draw them ([DapCollectionActions]: Play the filled circle, Shuffle the
 * quiet one). The cover fades into the page's own background, as the album's does; the genre's colour is not used for anything.
 *
 * B33-U3: the cover runs to the top edge of the screen, under the status bar, with the short top scrim the album's header carries
 * ([DapHeroTopScrim]); the Back chevron floats over it (the page's scaffold draws it, with no band across the top). With no cover (no album
 * with art, or a page still loading, failed or empty) the header is the title alone, as a playlist's, and starts below the room the Back
 * chevron takes so the two never overlap.
 */
@Composable
internal fun GenreHeader(
    title: String,
    counts: String?,
    coverUri: String?,
    playLabel: String,
    shuffleLabel: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCover = counts != null && !coverUri.isNullOrBlank()
    val background = Dap.background
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    val requestSize = remember(widthPx, density.density) {
        with(density) { Size(widthPx.coerceAtLeast(1), GenreHeaderHeight.roundToPx()) }
    }
    val fade = remember(background) {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, background.copy(alpha = 0.30f), background.copy(alpha = 0.90f), background),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (hasCover) Modifier.height(GenreHeaderHeight) else Modifier)
            .clipToBounds(),
    ) {
        if (hasCover) {
            SmartImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                targetSize = requestSize,
                allowHardware = true,
                crossfadeDurationMillis = 0,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(fade))
            DapHeroTopScrim(Modifier.align(Alignment.TopCenter))
        }
        Column(
            modifier = (if (hasCover) Modifier.align(Alignment.BottomStart) else Modifier)
                .fillMaxWidth()
                .padding(
                    start = DapSpacing.Gutter,
                    end = DapSpacing.Gutter,
                    top = if (hasCover) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BackRowHeight,
                    bottom = if (hasCover) DapSpacing.Lg else 0.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
                Text(
                    text = title,
                    style = DapType.Title,
                    color = Dap.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (counts != null) {
                    Text(
                        text = counts,
                        style = DapType.ValueSmall,
                        color = Dap.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (counts != null) {
                DapCollectionActions(playLabel = playLabel, shuffleLabel = shuffleLabel, onPlay = onPlay, onShuffle = onShuffle)
            }
        }
    }
}
