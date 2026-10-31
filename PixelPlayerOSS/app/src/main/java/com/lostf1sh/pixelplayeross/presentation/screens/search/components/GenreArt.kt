package com.lostf1sh.pixelplayeross.presentation.screens.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.size.Size
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme

/**
 * B48-2: what a genre looks like wherever it has a picture slot (a grid tile, a list row, the genre page's header), in one place.
 *
 * Three layers, the first one that exists wins: the **image the listener chose** for the genre ([Genre.imagePath]), cropped to the slot; else the **icon the listener
 * chose** ([customIcon], a drawable from the quick-fill's custom genres); else the **letter** the genre's name starts with. The letter is always drawn underneath the
 * image, and the image is only visible once it has loaded: while it loads, or if its file is gone, the slot shows the letter and never the music-note placeholder
 * ([SmartImage]'s own, which at the header's size is a huge cropped note).
 */
@Composable
internal fun GenreArt(
    genre: Genre,
    customIcon: Int?,
    modifier: Modifier,
    letterStyle: TextStyle,
    imageTargetSize: Size,
) {
    Box(modifier) {
        GenreInitial(genre, customIcon, Modifier.fillMaxSize(), letterStyle)
        val image = genre.imagePath
        if (!image.isNullOrBlank()) {
            GenreImageLayer(image, Modifier.fillMaxSize(), imageTargetSize)
        }
    }
}

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
) {
    var loaded by remember(model) { mutableStateOf(false) }
    SmartImage(
        model = model,
        contentDescription = null,
        modifier = modifier
            .testTag(GENRE_ART_IMAGE_TAG)
            .graphicsLayer { alpha = if (loaded) 1f else 0f },
        contentScale = ContentScale.Crop,
        targetSize = targetSize,
        allowHardware = allowHardware,
        crossfadeDurationMillis = 0,
        onState = { state -> loaded = isGenreImageDrawable(state) },
    )
}

/** Only a loaded image is shown over the letter; loading and error states are not (their placeholder is the music note this look replaced). */
internal fun isGenreImageDrawable(state: AsyncImagePainter.State): Boolean = state is AsyncImagePainter.State.Success

/**
 * The slot's own content without an image: the custom icon when there is one, else the first letter of the genre's name. [colored] fills the slot with the genre's palette colour
 * (the B44 tile did before the frosted panel; every caller now passes false and sits on its own ground).
 */
@Composable
internal fun GenreInitial(genre: Genre, icon: Int?, modifier: Modifier, letterStyle: TextStyle = DapType.Heading, colored: Boolean = false) {
    val dark = LocalPixelPlayerDarkTheme.current
    val color = remember(genre.darkColorHex, genre.lightColorHex, dark) {
        runCatching { Color(android.graphics.Color.parseColor(if (dark) genre.darkColorHex else genre.lightColorHex)) }.getOrNull()
    } ?: Dap.surfaceRaised
    val ink = if (!colored) Dap.primaryText else if (color.luminance() > 0.179f) Color.Black else Color.White
    Box(modifier.clip(RoundedCornerShape(DapRadius.Surface)).background(if (colored) color else Color.Transparent), contentAlignment = Alignment.Center) {
        if (icon != null) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(28.dp).testTag(GENRE_ART_ICON_TAG), tint = ink)
        } else {
            Text(genreLetter(genre.name), Modifier.testTag(GENRE_ART_LETTER_TAG), style = letterStyle, color = ink)
        }
    }
}

/** The letter a genre's name starts with, in capitals ("?" for a blank name). */
internal fun genreLetter(name: String): String = name.trim().take(1).uppercase().ifEmpty { "?" }

/** The thumbnail slot of a list row: [DapSizes.ThumbRow] square on the raised ground, like every other row's cover. */
internal fun Modifier.genreRowThumb(): Modifier =
    this.size(DapSizes.ThumbRow).clip(RoundedCornerShape(4.dp))

internal const val GENRE_ART_IMAGE_TAG = "genre-art-image"
internal const val GENRE_ART_LETTER_TAG = "genre-art-letter"
internal const val GENRE_ART_ICON_TAG = "genre-art-icon"
