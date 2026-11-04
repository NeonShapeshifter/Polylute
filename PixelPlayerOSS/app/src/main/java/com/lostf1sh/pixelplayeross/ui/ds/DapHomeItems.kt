package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** Home's section heading. The whole row is the navigation target; a trailing command owns its own tap. */
@Composable
fun DapFeatureHeader(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, maxTitleLines: Int = 2,
) {
    Row(modifier.fillMaxWidth().heightIn(min=DapSizes.MinTouchTarget)
        .then(if(onClick != null) Modifier.clickable(role=Role.Button,onClick=onClick) else Modifier),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(DapSpacing.Sm)) {
        Column(Modifier.weight(1f)) {
            Text(title,style=DapType.Heading,color=Dap.primaryText,maxLines=maxTitleLines,overflow=TextOverflow.Ellipsis)
            if(subtitle != null) Text(subtitle,style=DapType.Caption,color=Dap.secondaryText)
        }
        if(onClick != null) DapChevron()
        trailing?.invoke()
    }
}

/** A larger media row fitted to an equal-width two-column tile. */
@Composable
fun DapRecentTile(title: String, artist: String?, modifier: Modifier=Modifier, active: Boolean=false, artwork: @Composable () -> Unit, onClick: () -> Unit) {
    Row(modifier.heightIn(min=DapSizes.ThumbFeature + DapSpacing.Lg*2)
        .clip(RoundedCornerShape(DapRadius.Surface)).background(Dap.panel).clickable(role=Role.Button,onClick=onClick)
        .padding(DapSpacing.Sm),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(DapSpacing.Sm)) {
        Box(Modifier.size(DapSizes.ThumbFeature).clip(RoundedCornerShape(DapRadius.Surface))) { artwork() }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(DapSpacing.Xs)) {
            Text(title,style=DapType.RowSmall,color=if(active) LocalDapAccent.current else Dap.primaryText,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(artist != null) Text(artist,style=DapType.Caption,color=Dap.secondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** B57 3.1: one small card of the Recently played carousel: a square cover over its title and artist. The width is the caller's; the cover takes all of it. */
@Composable
fun DapRecentCard(title: String, artist: String?, modifier: Modifier=Modifier, active: Boolean=false, artwork: @Composable () -> Unit, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(DapRadius.Surface)).clickable(role=Role.Button,onClick=onClick),verticalArrangement=Arrangement.spacedBy(DapSpacing.Xs)) {
        // the cover's own backdrop is the glass's panel over the cover glass (never a flat grey slab), the plain panel otherwise
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(DapRadius.Surface)).background(com.lostf1sh.pixelplayeross.ui.glass.frostedPanelOr(Dap.panel))) { artwork() }
        Text(title,style=DapType.RowSmall,color=if(active) LocalDapAccent.current else Dap.primaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(artist != null) Text(artist,style=DapType.Caption,color=Dap.secondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
fun DapArtistGridItem(title: String, modifier: Modifier=Modifier, artwork: @Composable () -> Unit, onClick: () -> Unit) {
    Column(modifier.clickable(role=Role.Button,onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(DapSpacing.Sm)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)) { artwork() }
        Text(title,style=DapType.Note,color=Dap.primaryText,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
    }
}
