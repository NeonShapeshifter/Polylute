package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lostf1sh.pixelplayeross.ui.ds.Dap

/**
 * A loading skeleton.
 *
 * The design's loading state is a **static** block in bg/2 — no shimmer, no sweep, no motion.
 * Loading must not be the loudest thing on a quiet screen, and a screen that animates while the
 * library resolves reads as busier than the library itself.
 *
 * The name is kept so the existing call sites stay unchanged.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Dap.surfaceRaised))
}
