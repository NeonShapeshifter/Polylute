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

            val target = if (request.source == null) com.lostf1sh.pixelplayeross.presentation.navigation.listeningDestination(null, request.albumId, request.songId) { true } else playerViewModel.listeningDestination(request)
            if (target.pattern == Screen.AlbumDetail.route) {
                val albumId = target.route.substringAfterLast('/').toLong()
                AlbumLocateRequests.request(albumId, request.songId)
            } else if (target.pattern != Screen.LibraryLocate.route) {
                com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceLocateRequests.request(target.route, request.songId, request.source)
            }
            navController.navigateSafelyReplacing(route = target.route, patternToPop = target.pattern)
        }
    }
}
