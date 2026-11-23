package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.musicinfo.Description
import com.lostf1sh.pixelplayeross.presentation.components.BackRowHeight
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreArt
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreImageLayer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreArtist
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapHeroTopScrim
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize

private val HeroHeight = 330.dp
private val TitleTop = 268.dp

@Composable
internal fun GenreHeader(
    genre: Genre,
    counts: String?,
    chosenIcon: Int?,
    featuredArtist: GenreArtist?,
    photo: String?,
    description: Description?,
    playLabel: String,
    shuffleLabel: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onArtist: (Long) -> Unit,
) {
    val hasHero = counts != null || !genre.imagePath.isNullOrBlank() || chosenIcon != null
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth()) {
        if (hasHero) {
            val width = LocalWindowInfo.current.containerSize.width
            val height = with(LocalDensity.current) { HeroHeight.roundToPx() }
            // The picture itself fades out (an alpha mask), so it melts into whatever the page draws behind it: the Cover glass is a
            // gradient, and a fade *to a colour* left a hard line where the header ended (owner's HONOR, 11-23).
            GenreArt(
                genre, chosenIcon,
                Modifier.fillMaxWidth().height(HeroHeight).testTag("genre-hero")
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
                DapType.Display, Size(width.coerceAtLeast(1), height), columns = 3, featuredPhoto = photo,
            )
            DapHeroTopScrim(Modifier.align(Alignment.TopCenter))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter)
                .padding(top = if (hasHero) TitleTop else statusInset + BackRowHeight),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.genre_eyebrow), style = DapType.ValueSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em), color = Dap.tertiaryText)
            Text(genre.name, Modifier.semantics { heading() }.testTag("genre-title"),
                style = DapType.Title.copy(fontSize = 44.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold),
                color = Dap.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (counts != null) {
                Text(counts, Modifier.testTag("genre-counts"), style = DapType.ValueSmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Dap.secondaryText)
                if (description != null) GenreDescription(description)
                if (featuredArtist != null) FeaturedArtistCard(featuredArtist, photo) { onArtist(featuredArtist.artistId) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(Dap.primaryText)
                            .semantics { contentDescription = playLabel }
                            .clickable(role = Role.Button, onClick = onPlay).padding(horizontal = 22.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DapIcon(DapIconKind.Play, tint = Dap.onFill, size = 18.dp)
                        Text(playLabel, style = DapType.Button.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Dap.onFill)
                    }
                    GenreDisc(DapIconKind.Shuffle, shuffleLabel, onShuffle, tonal = true)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Dap.primaryText.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
internal fun GenreDisc(kind: DapIconKind, label: String, onClick: () -> Unit, tonal: Boolean = false) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = label }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (tonal) 48.dp else 44.dp).clip(CircleShape)
                .background(if (tonal) Dap.panelRaised else Dap.background.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            DapIcon(kind, tint = Dap.primaryText, size = if (tonal) 20.dp else 22.dp)
        }
    }
}

@Composable
private fun GenreDescription(description: Description) {
    var expanded by remember(description) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("genre-description")) {
        Text(description.text, style = DapType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Dap.secondaryText,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.genre_wikipedia_credit), Modifier.weight(1f)
                .heightIn(min = 48.dp).clickable(role = Role.Button) { uri.openUri(description.pageUrl) }
                .padding(vertical = 12.dp),
                style = DapType.Note.copy(fontSize = 12.sp), color = Dap.tertiaryText)
            Text(stringResource(if (expanded) R.string.music_info_read_less else R.string.music_info_read_more),
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 12.dp),
                style = DapType.RowSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Dap.primaryText)
        }
    }
}

@Composable
private fun FeaturedArtistCard(artist: GenreArtist, photo: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("genre-featured-artist").padding(top = 2.dp).clip(RoundedCornerShape(16.dp)).background(Dap.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Dap.panelRaised), contentAlignment = Alignment.Center) {
            Text(artist.name.take(1).uppercase(), style = DapType.Row, color = Dap.secondaryText)
            if (photo != null) GenreImageLayer(photo, Modifier.fillMaxSize(), ArtworkRequestSize.forCell(40.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.genre_featured_artist),
                style = DapType.ValueSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.1.em), color = Dap.tertiaryText)
            Text(artist.name, style = DapType.RowSmall.copy(fontWeight = FontWeight.Bold), color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DapIcon(DapIconKind.ChevronRight, tint = Dap.secondaryText, size = 20.dp)
    }
}
