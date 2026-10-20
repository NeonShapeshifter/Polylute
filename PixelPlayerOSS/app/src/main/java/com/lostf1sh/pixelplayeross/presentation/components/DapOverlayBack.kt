package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapOverlayAction
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing

/**
 * The Back affordance for a screen that has no app bar.
 *
 * A screen whose title is ordinary page content does not need a bar to hold one chevron, and paying
 * a permanent row for it is what this pass removed. The chevron floats instead: it carries the
 * status-bar inset itself, stays where it is while the page scrolls, and is placed by the caller
 * with `Modifier.align(Alignment.TopStart)`.
 *
 * Two things are decided here rather than at each call site, because getting either wrong is how a
 * screen ends up with a second way to go back:
 *
 * - **Whether to draw at all.** In Gestures mode a control that exists only to perform Back is not
 *   drawn — that is the mode's whole meaning — and the edge swipe is what performs Back instead.
 * - **What it calls.** [onClick] is for the rare screen whose Back does more than go back, such as
 *   ending an authenticated session on the way out. Left null, it calls the shell's one Back
 *   authority, which is the same call the edge gesture and the system's own Back make.
 */
@Composable
fun DapOverlayBack(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    if (!LocalBackNavigationMode.current.showsVisibleBackAffordance) return
    val back = LocalDapBackNavigation.current
    DapOverlayAction(
        kind = DapIconKind.Chevron,
        contentDescription = stringResource(R.string.auth_cd_back),
        onClick = { if (onClick != null) onClick() else back.back() },
        modifier = modifier
            .statusBarsPadding()
            .padding(top = DapSpacing.Sm, start = DapSpacing.Md),
    )
}
