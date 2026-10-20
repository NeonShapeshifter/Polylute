package com.lostf1sh.pixelplayeross.presentation.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollection
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.presentation.components.OptimizedAlbumArt
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapArtworkPlaceholder
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/**
 * The cover of a collection: the first four songs' art as a 2×2 mosaic, one cover when there are fewer than four, the design's
 * placeholder when there are none. The collection has no art of its own, so it shows what is in it.
 */
@Composable
internal fun CollectionCover(items: List<CatalogItem>, modifier: Modifier = Modifier, size: Dp = DapSizes.ThumbFeature) {
    Box(modifier.size(size).clip(RoundedCornerShape(4.dp))) {
        val arts = items.map { it.song.albumArtUriString }.filter { !it.isNullOrBlank() }
        when {
            items.isEmpty() || arts.isEmpty() -> DapArtworkPlaceholder(Modifier.fillMaxSize())
            arts.size < 4 -> OptimizedAlbumArt(
                uri = arts.first(),
                title = items.first().title,
                targetSize = coil.size.Size(128, 128),
                modifier = Modifier.fillMaxSize(),
            )
            else -> Column(Modifier.fillMaxSize()) {
                arts.take(4).chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        pair.forEach { uri ->
                            Box(Modifier.weight(1f).fillMaxSize()) {
                                OptimizedAlbumArt(
                                    uri = uri,
                                    title = "",
                                    targetSize = coil.size.Size(64, 64),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One automatic collection in the Playlists list: its cover, its name, the line that says why these songs, how many there are, and
 * what can be done to it, which is to hide it. A hidden one is dimmed and offers to come back; an empty one says why instead of
 * why-these-songs, so the list never promises what the page cannot give.
 */
@Composable
internal fun AutoCollectionRow(
    collection: AutoCollection,
    hidden: Boolean,
    onOpen: () -> Unit,
    onHide: () -> Unit,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = collection.id.nameText()
    val line = if (collection.isEmpty) collection.emptyText() else collection.reasonText()
    Column(modifier.fillMaxWidth().then(if (hidden) Modifier.alpha(0.5f) else Modifier)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = DapSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CollectionCover(collection.items)
            Spacer(Modifier.width(DapSpacing.Lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = DapType.RowSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = line,
                    style = DapType.Note,
                    color = if (collection.isEmpty) Dap.tertiaryText else Dap.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DapSpacing.Md))
            Text(
                text = collection.items.size.toString(),
                style = DapType.ValueSmall,
                color = Dap.tertiaryText,
                maxLines = 1,
            )
            if (hidden) {
                // The visible row's 48 dp button leaves the count and its glyph about 15 dp apart; a text action brings only 4 dp of its own.
                Spacer(Modifier.width(DapSpacing.Md))
                DapTextAction(label = stringResource(R.string.playlists_show), onClick = onShow)
            } else {
                Box {
                    DapOverflowButton(
                        onClick = { menuOpen = true },
                        description = stringResource(R.string.playlists_collection_options, name),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.playlists_hide)) },
                            onClick = {
                                menuOpen = false
                                onHide()
                            },
                        )
                    }
                }
            }
        }
        DapDivider()
    }
}
