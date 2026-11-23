package com.lostf1sh.pixelplayeross.presentation.screens.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.GenreGrouping
import com.lostf1sh.pixelplayeross.presentation.components.LocalArtworkLoadDeferred
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.getNavigationBarHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.components.rememberFlingState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize
import kotlinx.collections.immutable.ImmutableList

@Composable
fun GenreCategoriesGrid(
    genres: ImmutableList<Genre>,
    onGenreClick: (Genre) -> Unit,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    bottomContentPadding: androidx.compose.ui.unit.Dp? = null,
) {
    val isGrid by playerViewModel.isGenreGridView.collectAsStateWithLifecycle()
    val compact by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val icons by playerViewModel.customGenreIcons.collectAsStateWithLifecycle(initialValue = emptyMap())
    val padding = PaddingValues(top = 8.dp, bottom = bottomContentPadding ?: (28.dp + resolveNavBarOccupiedHeight(getNavigationBarHeight(), compact) + MiniPlayerHeight))
    val content = modifier.fillMaxSize().padding(horizontal = DapSpacing.Gutter)
    val (other, main) = remember(genres) { genres.partition { GenreGrouping.isOther(it) } }
    val header: @Composable () -> Unit = {
        GenreListHeader(playerViewModel::toggleGenreViewMode, playerViewModel::shuffleRandomGenre)
    }
    if (genres.isEmpty()) {
        Box(content, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_genres_available), style = DapType.Body, color = Dap.tertiaryText)
        }
    } else if (isGrid) {
        val gridState = rememberLazyGridState()
        val flinging = rememberFlingState(gridState, gridState.interactionSource)
        CompositionLocalProvider(LocalArtworkLoadDeferred provides flinging) {
            LazyVerticalGrid(GridCells.Fixed(2), content, state = gridState, contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                items(main, key = { it.id }) { genre -> GenreTile(genre, genre.chosenIcon(icons)) { onGenreClick(genre) } }
                // B56-5.2: the genres with fewer than three songs are not hidden: they go at the end, under one header
                if (other.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "other_header") { OtherGenresHeader() }
                    items(other, key = { it.id }) { genre -> GenreTile(genre, genre.chosenIcon(icons)) { onGenreClick(genre) } }
                }
            }
        }
    } else {
        val listState = rememberLazyListState()
        val flinging = rememberFlingState(listState, listState.interactionSource)
        CompositionLocalProvider(LocalArtworkLoadDeferred provides flinging) {
            LazyColumn(content, state = listState, contentPadding = padding) {
                item { header() }
                items(main, key = { it.id }) { genre -> GenreListRow(genre, genre.chosenIcon(icons)) { onGenreClick(genre) } }
                if (other.isNotEmpty()) {
                    item(key = "other_header") { OtherGenresHeader() }
                    items(other, key = { it.id }) { genre -> GenreListRow(genre, genre.chosenIcon(icons)) { onGenreClick(genre) } }
                }
            }
        }
    }
}

/** B56-5.2: the one header over the genres with fewer than three songs. */
@Composable
internal fun OtherGenresHeader() {
    Text(
        text = stringResource(R.string.genre_other_header),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
        style = DapType.SectionHeader,
        color = Dap.tertiaryText,
    )
}

@Composable
private fun GenreListHeader(onToggle: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.browse_by_genre), Modifier.weight(1f), style = DapType.SectionHeader, color = Dap.tertiaryText)
        DapIconButton(DapIconKind.Shuffle, stringResource(R.string.shuffle_all_genres), onShuffle)
        DapIconButton(DapIconKind.List, stringResource(R.string.cd_toggle_grid_list), onToggle)
    }
}

private val GenreListCoverSize = 68.dp
private val GenreTileRadius = 14.dp
private val GenreListCoverRadius = 12.dp

@Composable
internal fun GenreListRow(genre: Genre, icon: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GenreArt(
            genre, icon, Modifier.size(GenreListCoverSize).clip(RoundedCornerShape(GenreListCoverRadius)),
            DapType.Heading, ArtworkRequestSize.forCell(GenreListCoverSize),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(genre.name, style = DapType.RowSmall.copy(fontWeight = FontWeight.SemiBold), color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GenreSongCount(genre.songCount)
        }
        DapIcon(DapIconKind.ChevronRight, tint = Dap.secondaryText, size = 20.dp)
    }
}

@Composable
private fun GenreTile(genre: Genre, icon: Int?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(GenreTileRadius)).clickable(role = Role.Button, onClick = onClick)) {
        GenreArt(
            genre, icon, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(GenreTileRadius)),
            DapType.Heading, ArtworkRequestSize.forColumns(2, DapSpacing.Gutter * 2, 12.dp),
            columns = 2,
        )
        Spacer(Modifier.height(8.dp))
        Text(genre.name, style = DapType.RowSmall.copy(fontWeight = FontWeight.Bold), color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        GenreSongCount(genre.songCount)
    }
}

@Composable
private fun GenreSongCount(count: Int) {
    Text(pluralStringResource(R.plurals.genre_count_songs, count, count), style = DapType.ValueSmall.copy(fontSize = 11.sp), color = Dap.secondaryText)
}
