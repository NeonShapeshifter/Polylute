package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette

/** A legacy Material card joins the same shared veil as design-system cards. Plain themes keep their original surface role. */
@Composable
fun frostedPanelOr(plain: Color): Color = LocalDapPalette.current.let { it.glass?.panel ?: plain }
