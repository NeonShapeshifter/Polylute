package com.lostf1sh.pixelplayeross.presentation.screens.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.getNavigationBarHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.frostedPanelOr
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
    val header: @Composable () -> Unit = {
        GenreListHeader(playerViewModel::toggleGenreViewMode, playerViewModel::shuffleRandomGenre)
    }
    if (genres.isEmpty()) {
        Box(content, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_genres_available), style = DapType.Body, color = Dap.tertiaryText)
        }
    } else if (isGrid) {
        LazyVerticalGrid(GridCells.Fixed(2), content, contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(genres, key = { it.id }) { genre -> GenreTile(genre, icons[genre.id]) { onGenreClick(genre) } }
        }
    } else {
        LazyColumn(content, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { header() }
            items(genres, key = { it.id }) { genre -> GenreListRow(genre, icons[genre.id]) { onGenreClick(genre) } }
        }
    }
}

@Composable
private fun GenreListHeader(onToggle: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.browse_by_genre), Modifier.weight(1f), style = DapType.SectionHeader, color = Dap.tertiaryText)
        DapIconButton(DapIconKind.Shuffle, stringResource(R.string.shuffle_all_genres), onShuffle)
        DapIconButton(DapIconKind.List, stringResource(R.string.cd_toggle_grid_list), onToggle)
    }
}

@Composable
internal fun GenreListRow(genre: Genre, icon: Int?, onClick: () -> Unit) {
    DapMediaRow(
        title = genre.name,
        subtitle = pluralStringResource(R.plurals.genre_count_songs, genre.songCount, genre.songCount),
        modifier = Modifier.clip(RoundedCornerShape(DapRadius.Surface)).background(frostedPanelOr(Dap.surfaceRaised)),
        // B48-2: the genre's own image, else its custom icon, else its letter (not the music-note placeholder)
        leading = {
            GenreArt(genre, icon, Modifier.genreRowThumb().background(Dap.panelRaised), DapType.Row, Size(96, 96))
        },
        divider = false,
        onClick = onClick,
        minHeight = DapHeights.MediaRowTall,
    )
}

@Composable
private fun GenreTile(genre: Genre, icon: Int?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DapRadius.Surface))
        .background(frostedPanelOr(Dap.surfaceRaised)).clickable(onClick = onClick).padding(8.dp)) {
        // B48-2: the genre's own image cropped like an album tile, else its custom icon, else its letter
        GenreArt(genre, icon, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(DapRadius.Surface)), DapType.Heading, Size(360, 360))
        Spacer(Modifier.height(8.dp))
        Text(genre.name, style = DapType.Row, color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pluralStringResource(R.plurals.genre_count_songs, genre.songCount, genre.songCount), style = DapType.Caption, color = Dap.secondaryText)
    }
}
