package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass
import com.lostf1sh.pixelplayeross.ui.glass.SheetPresence
import com.lostf1sh.pixelplayeross.ui.glass.StillGlassSheetBody
import com.lostf1sh.pixelplayeross.ui.glass.glassSheetContainer
import com.lostf1sh.pixelplayeross.ui.glass.rememberStillGlassClock

/**
 * B42: the app's bottom sheet. It is Material 3's `ModalBottomSheet` with the parameters the app's sheets use (the same names, the same
 * defaults), and **the one place a sheet is opened**: a sheet that calls `ModalBottomSheet` itself stays opaque in "Cover glass"
 * (`GlassSheetBoundaryTest` fails the build for it).
 *
 * - **"Theme and accent" and "From album art"** ([LocalCoverGlass] is null): exactly Material's sheet, with [containerColor] and the rest as
 *   the caller gave them. Nothing here draws.
 * - **"Cover glass"** (there is a field on screen): the surface is transparent and the sheet carries **its own still copy of the glass**
 *   ([com.lostf1sh.pixelplayeross.ui.glass.SheetGlass]): the same field the app behind it shows, frozen at the phase it opened at, under a
 *   stronger veil, with the glass's ink. [containerColor] is not used; [contentColor] and [shape] are.
 *
 * One call to `ModalBottomSheet` serves both, and the body has the same structure in both, so a cover that goes away while a sheet is open
 * (a song without one starts) takes the glass out from under it and keeps the sheet's window and what it holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    val scene = LocalCoverGlass.current
    val clock = rememberStillGlassClock(scene)
    // B56-7: a sheet is over the player for as long as this is composed; what moves behind it (the live background, an animated cover) holds still
    SheetPresence.Hold()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = glassSheetContainer(containerColor, scene),
        contentColor = contentColor,
        tonalElevation = if (scene != null) 0.dp else tonalElevation,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
    ) {
        StillGlassSheetBody(scene, clock, content)
    }
}
