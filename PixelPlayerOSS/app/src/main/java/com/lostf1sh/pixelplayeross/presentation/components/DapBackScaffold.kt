package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerStatusBarStyle

/**
 * Navigation occupies its own viewport: scrolling rows never pass beneath Back or system icons.
 *
 * **The content also stops above the navigation bar** (B29-L9). A pushed screen has no bottom bar of its own, and the shell's mini
 * player sits flush above the navigation bar, so the content used to run to the screen's edge and rows scrolled *through* the strip
 * between the mini player and the gesture bar. The inset is applied here, once, for every screen that uses the scaffold (Album, Artist,
 * Playlist, Genre, Stats, a collection, Settings, ...), and it is consumed, so a `navigationBarsPadding()` inside does not add it again.
 * A screen must therefore **not** add the navigation bar's height to its own end padding: the last row rests at the viewport's end
 * plus the mini player's height ([com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight]) and what air the screen wants.
 *
 * **Back is a plain chevron** (B33-U3): no box, no container, a 48 dp target at the same place on every screen.
 *
 * **[overHeaderArt]** is for a page whose header is a picture (Album, Artist, Genre): the picture runs to the top edge, under the status
 * bar, and the chevron floats over it where the row used to be, so there is no band across the top. The page then owns the status-bar
 * inset (its header is as tall as it was plus the bar, and it carries the short scrim from [DapHeroTopScrim]) and keeps clear of the
 * chevron: [BackRowHeight] is the room the row took below the bar. Every other page keeps the row.
 */
@Composable
fun DapBackScaffold(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    overHeaderArt: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    PixelPlayerStatusBarStyle(color = Dap.background)
    val back = LocalDapBackNavigation.current
    val showsBack = LocalBackNavigationMode.current.showsVisibleBackAffordance
    val backLabel = stringResource(R.string.auth_cd_back)
    val goBack = { if (onBack != null) onBack() else back.back() }
    if (overHeaderArt) {
        Box(modifier.fillMaxSize().background(Dap.background)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                content = content,
            )
            if (showsBack) {
                DapOverlayAction(
                    kind = DapIconKind.Chevron,
                    contentDescription = backLabel,
                    onClick = goBack,
                    onImage = true,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = DapSpacing.Md, vertical = DapSpacing.Xs),
                )
            }
        }
        return
    }
    Column(modifier.fillMaxSize().background(Dap.background).statusBarsPadding()) {
        if (showsBack) {
            Row(Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Md, vertical = DapSpacing.Xs)) {
                DapOverlayAction(
                    kind = DapIconKind.Chevron,
                    contentDescription = backLabel,
                    onClick = goBack,
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            content = content,
        )
    }
}

/** The room the Back row takes below the status bar: its 48 dp target and the 4 dp above and below it. */
val BackRowHeight = DapSizes.MinTouchTarget + DapSpacing.Xs * 2
