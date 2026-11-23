package com.lostf1sh.pixelplayeross.presentation.screens.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.size.Size
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.GenreNames
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.coverMosaicRows
import com.lostf1sh.pixelplayeross.presentation.components.rememberArtworkLoadAllowed
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize

/** Quick fill stores the name as entered. Older callers may supply the route id. */
internal fun Genre.chosenIcon(icons: Map<String, Int>): Int? =
    icons[name] ?: icons[id] ?: icons.entries
        .filter { GenreNames.key(it.key) == GenreNames.key(name) }
        .minByOrNull { it.key }
        ?.value ?: iconResId

/** The same precedence in all three slots. Failed image files reveal the next available layer. */
@Composable
internal fun GenreArt(
    genre: Genre,
    customIcon: Int?,
    modifier: Modifier,
    letterStyle: TextStyle,
    imageTargetSize: Size,
    columns: Int = 1,
    featuredPhoto: String? = null,
) {
    val loadAllowed = rememberArtworkLoadAllowed()
    val icon = customIcon ?: genre.iconResId
    val selectedImage = genre.imagePath?.takeIf { loadAllowed && it.isNotBlank() }
    var selectedState by remember(selectedImage) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(modifier.background(Dap.panelRaised)) {
        if (!isGenreImageDrawable(selectedState)) {
            when {
                icon != null || !loadAllowed || selectedImage != null && selectedState !is AsyncImagePainter.State.Error ->
                    GenreInitial(genre, icon, Modifier.matchParentSize(), letterStyle)
                else -> AutomaticGenreArt(genre, featuredPhoto, columns, imageTargetSize, letterStyle, Modifier.matchParentSize())
            }
        }
        if (selectedImage != null) {
            GenreImageLayer(selectedImage, Modifier.matchParentSize(), imageTargetSize, onState = { selectedState = it })
        }
    }
}

@Composable
private fun AutomaticGenreArt(
    genre: Genre,
    photo: String?,
    columns: Int,
    photoSize: Size,
    letterStyle: TextStyle,
    modifier: Modifier,
) {
    var photoState by remember(photo) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val covers = genre.albumCoverUris
    Box(modifier) {
        if (!isGenreImageDrawable(photoState)) {
            if (covers.isEmpty()) GenreInitial(genre, null, Modifier.matchParentSize(), letterStyle)
            else if (photo == null || photoState is AsyncImagePainter.State.Error) {
                GenreCoverMosaic(covers.take(columns * if (columns == 1) 1 else 2), columns, Modifier.matchParentSize())
            }
        }
        if (photo != null) GenreImageLayer(photo, Modifier.matchParentSize(), photoSize, allowHardware = true, onState = { photoState = it })
    }
}

@Composable
private fun GenreCoverMosaic(covers: List<String>, columns: Int, modifier: Modifier) {
    val gap = 2.dp
    BoxWithConstraints(modifier.testTag(if (columns == 1) GENRE_ART_SINGLE_TAG else GENRE_ART_COLLAGE_TAG)) {
        if (covers.size == 3 && columns == 2) {
            val width = (maxWidth - gap) / 2
            val height = maxHeight
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                GenreCoverCell(covers[0], width, height, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (cover in covers.drop(1)) {
                        GenreCoverCell(cover, width, (height - gap) / 2, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        } else {
            val rows = coverMosaicRows(covers.size, columns)
            val width = maxWidth
            val cellHeight = (maxHeight - gap * (rows.size - 1)) / rows.size
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                var offset = 0
                for (count in rows) {
                    val cellWidth = (width - gap * (count - 1)) / count
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (index in offset until offset + count) {
                            GenreCoverCell(covers[index], cellWidth, cellHeight, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    offset += count
                }
            }
        }
    }
}

@Composable
private fun GenreCoverCell(model: String, width: Dp, height: Dp, modifier: Modifier) {
    val density = LocalDensity.current.density
    val target = remember(width, height, density) { genreCoverRequestSize(width.value, height.value, density) }
    Box(modifier.testTag(GENRE_ART_COVER_TAG)) {
        GenreImageLayer(model, Modifier.matchParentSize(), target)
    }
}

internal fun genreCoverRequestSize(width: Float, height: Float, density: Float): Size = Size(
    ArtworkRequestSize.sidePx(width, density).coerceAtMost(AlbumArtUtils.THUMBNAIL_EDGE_PX),
    ArtworkRequestSize.sidePx(height, density).coerceAtMost(AlbumArtUtils.THUMBNAIL_EDGE_PX),
)

/**
 * The picture of a genre drawn over its letter: invisible until Coil has it, so that a loading or failed image leaves the slot as the letter (B48-2) instead of the
 * placeholder drawable [SmartImage] draws in those states.
 */
@Composable
internal fun GenreImageLayer(
    model: String,
    modifier: Modifier,
    targetSize: Size,
    allowHardware: Boolean = false,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    var loaded by remember(model) { mutableStateOf(false) }
    SmartImage(
        model = model,
        contentDescription = null,
        modifier = modifier
            .testTag(if (loaded) GENRE_ART_READY_TAG else GENRE_ART_IMAGE_TAG)
            .graphicsLayer { alpha = if (loaded) 1f else 0f },
        contentScale = ContentScale.Crop,
        targetSize = targetSize,
        allowHardware = allowHardware,
        crossfadeDurationMillis = 0,
        onState = { state ->
            loaded = isGenreImageDrawable(state)
            onState?.invoke(state)
        },
    )
}

/** Only a loaded image is shown over the letter; loading and error states are not (their placeholder is the music note this look replaced). */
internal fun isGenreImageDrawable(state: AsyncImagePainter.State): Boolean = state is AsyncImagePainter.State.Success

@Composable
private fun GenreInitial(genre: Genre, icon: Int?, modifier: Modifier, letterStyle: TextStyle) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(icon), null, Modifier.size(28.dp).testTag(GENRE_ART_ICON_TAG),
                tint = Dap.primaryText,
            )
        } else {
            Text(genreLetter(genre.name), Modifier.testTag(GENRE_ART_LETTER_TAG), style = letterStyle, color = Dap.primaryText)
        }
    }
}

/** The letter a genre's name starts with, in capitals ("?" for a blank name). */
internal fun genreLetter(name: String): String = name.trim().take(1).uppercase().ifEmpty { "?" }

internal const val GENRE_ART_IMAGE_TAG = "genre-art-image"
internal const val GENRE_ART_LETTER_TAG = "genre-art-letter"
internal const val GENRE_ART_ICON_TAG = "genre-art-icon"

internal const val GENRE_ART_COLLAGE_TAG = "genre-art-collage"
internal const val GENRE_ART_SINGLE_TAG = "genre-art-single"
internal const val GENRE_ART_COVER_TAG = "genre-art-cover"

internal const val GENRE_ART_READY_TAG = "genre-art-ready"
