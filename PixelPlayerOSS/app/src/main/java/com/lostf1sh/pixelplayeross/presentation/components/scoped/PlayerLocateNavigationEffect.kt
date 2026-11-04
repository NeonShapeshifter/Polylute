package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavHostController
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumLocateRequests
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * B56-4 / B57 2.4: the full player's title was tapped. The player is already closing; this opens **the song's album page** with that song highlighted and scrolled into view
 * ([AlbumLocateRequests]); only a song with no album falls back to the Library's Songs list ([Screen.LibraryLocate], B32-fix1). The Library's own locate button is unchanged.
 */
@Composable
internal fun PlayerLocateNavigationEffect(
    navController: NavHostController,
    sheetCollapsedTargetY: Float,
    sheetMotionController: SheetMotionController,
    playerViewModel: PlayerViewModel
) {
    val latestSheetCollapsedTargetY by rememberUpdatedState(sheetCollapsedTargetY)
    LaunchedEffect(navController) {
        playerViewModel.songLocateNavigationRequests.collectLatest { request ->
            sheetMotionController.snapCollapsed(latestSheetCollapsedTargetY)
            playerViewModel.collapsePlayerSheet()

            if (request.albumId > 0L) {
                // B57 2.4: the song's album page, the song highlighted and scrolled into view (the page takes this request; see AlbumLocateRequests)
                AlbumLocateRequests.request(request.albumId, request.songId)
                navController.navigateSafelyReplacing(
                    route = Screen.AlbumDetail.createRoute(request.albumId),
                    patternToPop = Screen.AlbumDetail.route
                )
            } else {
                // a song with no album id: the Library's Songs list, as in B56
                navController.navigateSafelyReplacing(
                    route = Screen.LibraryLocate.createRoute(request.songId),
                    patternToPop = Screen.LibraryLocate.route
                )
            }
        }
    }
}
