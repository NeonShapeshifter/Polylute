package com.lostf1sh.pixelplayeross.presentation.screens.search.components

import com.lostf1sh.pixelplayeross.utils.currentLocale
import androidx.annotation.OptIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.getNavigationBarHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.utils.GenreIconProvider
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.R
import kotlinx.collections.immutable.ImmutableList
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun GenreCategoriesGrid(
    genres: ImmutableList<Genre>,
    onGenreClick: (Genre) -> Unit,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    bottomContentPadding: androidx.compose.ui.unit.Dp? = null
) {
    if (genres.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_genres_available),
                style = DapType.Body,
                color = Dap.tertiaryText,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val systemNavBarHeight = getNavigationBarHeight()
    val customGenreIcons = playerViewModel.customGenreIcons.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
        context = kotlin.coroutines.EmptyCoroutineContext
    ).value

    val isGridView by playerViewModel.isGenreGridView.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = if (isGridView) GridCells.Fixed(2) else GridCells.Fixed(1),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(DapRadius.Surface)),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = bottomContentPadding ?: (28.dp + resolveNavBarOccupiedHeight(systemNavBarHeight, navBarCompactMode) + MiniPlayerHeight)
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // A mono tracked section header, not an oversized Material page title. This read
                // as the screen's own title competing with the search field above it.
                Text(
                    text = stringResource(R.string.browse_by_genre).uppercase(currentLocale()),
                    style = DapType.SectionHeader,
                    color = Dap.tertiaryText,
                )

                // A quiet glyph action. The filled circle and its animated corner radius were
                // the last Material control on this screen.
                Box(
                    modifier = Modifier
                        .size(DapSizes.MinTouchTarget)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .clickable(
                            onClickLabel = stringResource(R.string.cd_toggle_grid_list),
                            onClick = { playerViewModel.toggleGenreViewMode() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    DapIcon(
                        kind = DapIconKind.List,
                        tint = Dap.tertiaryText,
                        size = 18.dp
                    )
                }
            }
        }
        items(genres, key = { it.id }) { genre ->
            GenreCard(
                genre = genre,
                customIcons = customGenreIcons,
                onClick = { onGenreClick(genre) },
                isGridView = isGridView
            )
        }
    }
}

@Composable
private fun GenreCard(
    genre: Genre,
    customIcons: Map<String, Int>,
    onClick: () -> Unit,
    isGridView: Boolean
) {
    // A genre is a way into the library, not a category with its own colour. One placeholder
    // ground, one name, one glyph. No per-genre palette, no per-genre typography.
    val imageRes = remember(genre.id, customIcons) {
        customIcons[genre.id] ?: getGenreImageResource(genre.id)
    }
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isGridView) Modifier.aspectRatio(1f)
                    else Modifier.height(64.dp)
                )
                .clip(RoundedCornerShape(DapRadius.Surface))
                .background(Dap.surfaceRaised)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Dap.disabledText),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(DapSpacing.Sm))
        Text(
            text = genre.name,
            style = DapType.Note.copy(fontWeight = FontWeight.Medium),
            color = Dap.primaryText,
        )
    }
}
