package com.lostf1sh.pixelplayeross.presentation.navigation

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.lostf1sh.pixelplayeross.data.preferences.LaunchTab
import com.lostf1sh.pixelplayeross.presentation.screens.HomeScreen
import com.lostf1sh.pixelplayeross.presentation.screens.LibraryScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SearchScreen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel

internal const val DAP_DETAIL_HOST_ROUTE = "__dap_root_shell__"

internal enum class DapRootTab(val route: String) {
    HOME(Screen.Home.route),
    SEARCH(Screen.Search.route),
    LIBRARY(Screen.Library.route);

    companion object {
        fun fromRouteOrHome(route: String?): DapRootTab =
            entries.firstOrNull { it.route == route } ?: HOME

        fun fromLaunchTabOrHome(launchTab: String?): DapRootTab = when (launchTab) {
            LaunchTab.SEARCH -> SEARCH
            LaunchTab.LIBRARY -> LIBRARY
            else -> HOME
        }
    }
}

/**
 * Owns the three immediate DAP roots outside Navigation Compose. Each root is
 * composed exactly once; only the selected root is visible and interactive.
 * SaveableStateHolder preserves its UI state across process recreation, while
 * DetailNavigation is a separate layer that owns the actual back stack.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DapRootShell(
    selectedRootRouteState: State<String?>,
    navController: NavHostController,
    paddingValues: PaddingValues,
    playerViewModel: PlayerViewModel,
    isDetailVisible: Boolean,
    onSearchBarActiveChange: (Boolean) -> Unit,
    onOpenSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootStateHolder = rememberSaveableStateHolder()
    val selectedRootTab = DapRootTab.fromRouteOrHome(selectedRootRouteState.value)

    Box(modifier = modifier.fillMaxSize()) {
        DapRootTab.entries.forEach { rootTab ->
            val isSelected = rootTab == selectedRootTab
            val rootModifier = Modifier
                .fillMaxSize()
                .zIndex(if (isSelected) 1f else 0f)
                .alpha(if (isSelected) 1f else 0f)
                // Only the selected root takes touches, and it takes all of them: alpha 0 does not
                // stop input, so a touch on an empty part of the visible root used to fall through
                // to a hidden root underneath and press one of its rows.
                .then(if (isSelected) Modifier.opaqueToTouch() else Modifier)
                .then(
                    if (!isSelected || isDetailVisible) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    }
                )

            rootStateHolder.SaveableStateProvider(rootTab.route) {
                key(rootTab) {
                    Box(modifier = rootModifier) {
                        when (rootTab) {
                            DapRootTab.HOME -> HomeScreen(
                                navController = navController,
                                paddingValuesParent = paddingValues,
                                playerViewModel = playerViewModel,
                                settingsViewModel = hiltViewModel(key = "dap-root-home-settings"),
                                onOpenSidebar = onOpenSidebar,
                            )

                            DapRootTab.SEARCH -> SearchScreen(
                                paddingValues = paddingValues,
                                playerViewModel = playerViewModel,
                                playlistViewModel = hiltViewModel<PlaylistViewModel>(
                                    key = "dap-root-search-playlists"
                                ),
                                navController = navController,
                                onSearchBarActiveChange = onSearchBarActiveChange,
                            )

                            DapRootTab.LIBRARY -> LibraryScreen(
                                navController = navController,
                                playerViewModel = playerViewModel,
                                playlistViewModel = hiltViewModel<PlaylistViewModel>(
                                    key = "dap-root-library-playlists"
                                ),
                                libraryViewModel = hiltViewModel<LibraryViewModel>(
                                    key = "dap-root-library"
                                ),
                                songInfoBottomSheetViewModel = hiltViewModel<SongInfoBottomSheetViewModel>(
                                    key = "dap-root-library-song-info"
                                ),
                            )
                        }
                    }
                }
            }
        }

        DetailNavigation(
            playerViewModel = playerViewModel,
            navController = navController,
            onOpenSidebar = onOpenSidebar,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (isDetailVisible) 2f else 0f)
                // Same rule for a detail page over the roots: a touch on an empty part of it (the
                // space around Maintenance's PIN field, a page margin) must not reach the root
                // drawn beneath. With no detail open the layer is empty and lets touches through.
                .then(if (isDetailVisible) Modifier.opaqueToTouch() else Modifier),
        )
    }
}

/**
 * Makes a layer the end of the line for touches inside it.
 *
 * Compose hit-tests overlapping siblings from the top and stops at the first one that has a pointer
 * handler at the touch position. A page's background has none, so a touch on an empty area used to
 * continue to whatever layer was below. This puts a handler under the whole layer. It only observes
 * — nothing is consumed — so the layer's own content handles its touches exactly as before.
 */
private fun Modifier.opaqueToTouch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
