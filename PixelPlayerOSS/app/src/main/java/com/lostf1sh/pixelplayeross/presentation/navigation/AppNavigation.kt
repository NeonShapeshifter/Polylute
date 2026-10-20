package com.lostf1sh.pixelplayeross.presentation.navigation

import DelimiterConfigScreen
import com.lostf1sh.pixelplayeross.presentation.screens.WordDelimiterConfigScreen
import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lostf1sh.pixelplayeross.presentation.screens.AlbumDetailScreen
import com.lostf1sh.pixelplayeross.presentation.screens.AudioBookmarkFolderScreen
import com.lostf1sh.pixelplayeross.presentation.screens.AudioBookmarksScreen
import com.lostf1sh.pixelplayeross.presentation.screens.ArtistDetailScreen
import com.lostf1sh.pixelplayeross.presentation.screens.ArtistSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.AutoCollectionDetailScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SmartCollectionsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.KnownDacsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.DacProfileScreen
import com.lostf1sh.pixelplayeross.presentation.screens.KnownHeadphonesScreen
import com.lostf1sh.pixelplayeross.presentation.screens.HeadphoneProfileScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SafetyMaxVolumeScreen
import com.lostf1sh.pixelplayeross.presentation.screens.EditTransitionScreen
import com.lostf1sh.pixelplayeross.presentation.screens.LicensesScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SpecialThanksScreen
import com.lostf1sh.pixelplayeross.presentation.screens.GenreDetailScreen
import com.lostf1sh.pixelplayeross.presentation.screens.MaintenanceScreen
import com.lostf1sh.pixelplayeross.presentation.screens.PaletteStyleSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.PlaylistDetailScreen
import com.lostf1sh.pixelplayeross.presentation.screens.RecentlyPlayedScreen

import com.lostf1sh.pixelplayeross.presentation.screens.AboutScreen
import com.lostf1sh.pixelplayeross.presentation.screens.StatsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.DuplicateSongsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsCategoryScreen
import com.lostf1sh.pixelplayeross.presentation.screens.EqualizerScreen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.components.ScreenWrapper

@OptIn(UnstableApi::class)
@SuppressLint("UnrememberedGetBackStackEntry")
@Composable
fun DetailNavigation(
    playerViewModel: PlayerViewModel,
    navController: NavHostController,
    onOpenSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceControlViewModel: DeviceControlViewModel = hiltViewModel()
    NavHost(
        navController = navController,
        startDestination = DAP_DETAIL_HOST_ROUTE,
        modifier = modifier,
    ) {
            composable(DAP_DETAIL_HOST_ROUTE) {
                Box(Modifier.fillMaxSize())
            }
            // B32-Q2: Stats (or a collection) asks the Library to show a song. Nothing is drawn here: the shell shows the Library root while
            // this is the top of the stack, the Library goes to its Songs tab and finds the song, and Back pops this and shows the page below.
            composable(
                route = Screen.LibraryLocate.route,
                arguments = listOf(navArgument("songId") { type = NavType.StringType }),
                enterTransition = { androidx.compose.animation.EnterTransition.None },
                exitTransition = { androidx.compose.animation.ExitTransition.None },
                popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                popExitTransition = { androidx.compose.animation.ExitTransition.None },
            ) { backStackEntry ->
                val songId = backStackEntry.arguments?.getString("songId").orEmpty()
                LibraryLocateEffect(songId = songId, playerViewModel = playerViewModel)
            }
            composable(
                Screen.Settings.route,
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    // Back goes through the shell's one authority rather than the nav controller, so
                    // the visible control, the edge gesture and the system's own Back are the same
                    // call and cannot disagree about what is in front of them.
                    val back = LocalDapBackNavigation.current
                    SettingsScreen(
                        navController = navController,
                        deviceControlViewModel = deviceControlViewModel,
                        onNavigationIconClick = { back.back() }
                    )
                }
            }
            composable(
                route = Screen.SettingsCategory.route,
                arguments = listOf(
                    navArgument("categoryId") { type = NavType.StringType },
                ),
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    val categoryId = backStackEntry.arguments?.getString("categoryId")
                    if (categoryId != null) {
                        SettingsCategoryScreen(
                            categoryId = categoryId,
                            navController = navController,
                            playerViewModel = playerViewModel,
                            deviceControlViewModel = deviceControlViewModel,
                        )
                    }
                }
            }
            composable(
                route = Screen.PaletteStyle.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    PaletteStyleSettingsScreen(
                        playerViewModel = playerViewModel,
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.SmartCollections.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    SmartCollectionsScreen(
                        playerViewModel = playerViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                route = Screen.AutoCollection.route,
                arguments = listOf(navArgument("collectionKey") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                // The route carries the collection's stored key. A key this build does not know (a back stack from another version)
                // opens the page's own "no such collection" state instead of crashing on the way back in.
                val collectionKey = backStackEntry.arguments?.getString("collectionKey").orEmpty()
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    AutoCollectionDetailScreen(
                        collectionKey = collectionKey,
                        navController = navController,
                        playerViewModel = playerViewModel,
                    )
                }
            }
            composable(
                Screen.RecentlyPlayed.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    RecentlyPlayedScreen(
                        playerViewModel = playerViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.Stats.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    StatsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel,
                    )
                }
            }
            composable(
                route = Screen.Duplicates.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    DuplicateSongsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")
                val playlistViewModel: PlaylistViewModel = hiltViewModel()
                if (playlistId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            playerViewModel = playerViewModel,
                            playlistViewModel = playlistViewModel,
                            onBackClick = { navController.popBackStack() },
                            onDeletePlayListClick = { navController.popBackStack() },
                            navController = navController
                        )
                    }
                }
            }

            composable(
                route = Screen.GenreDetail.route,
                arguments = listOf(navArgument("genreId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val genreId = backStackEntry.arguments?.getString("genreId")
                if (genreId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        GenreDetailScreen(
                            navController = navController,
                            genreId = genreId,
                            playerViewModel = playerViewModel
                        )
                    }
                } else {
                    Text(stringResource(R.string.nav_error_genre_id_missing), modifier = Modifier)
                }
            }
            composable(
                route = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId")
                if (albumId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        AlbumDetailScreen(
                            albumId = albumId,
                            navController = navController,
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
            composable(
                route = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("artistId")
                if (artistId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        ArtistDetailScreen(
                            artistId = artistId,
                            navController = navController,
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
            composable(
                route = Screen.EditTransition.route,
                arguments = listOf(navArgument("playlistId") {
                    type = NavType.StringType
                    nullable = true
                }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    EditTransitionScreen(navController = navController)
                }
            }
            composable(
                Screen.Licenses.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    LicensesScreen(navController = navController)
                }
            }
            composable(
                Screen.SpecialThanks.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    SpecialThanksScreen(navController = navController)
                }
            }
            composable(
                Screen.About.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    AboutScreen(
                        navController = navController,
                        viewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.Maintenance.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    // B32-Q6: an ordinary page, reached from Settings, System. There is no PIN and no session to gate it on any more
                    // (the session is now only the window in which Lock Task is lifted for an Android surface), and the page lifts nothing.
                    MaintenanceScreen(
                        onExit = { navController.popBackStack() },
                        // Device diagnostics is an engineering tool, so it is opened from inside Maintenance rather than from Settings.
                        onOpenDiagnostics = {
                            navController.navigateSafely(Screen.DeviceCapabilities.route)
                        },
                        playerViewModel = playerViewModel,
                    )
                }
            }
            composable(
                Screen.KnownDacs.route,
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    KnownDacsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { profileId ->
                            navController.navigateSafely(Screen.DacProfile.createRoute(profileId))
                        },
                    )
                }
            }
            composable(
                Screen.DacProfile.route,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    DacProfileScreen(
                        profileId = backStackEntry.arguments?.getString("profileId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        // The Change action opens the existing known-headphones list, which is the
                        // same flow Settings uses and the same one the DAC-to-headphone
                        // association is already persisted through. There is no second
                        // association system to introduce here.
                        onChangeHeadphone = {
                            navController.navigateSafely(Screen.KnownHeadphones.route)
                        },
                    )
                }
            }
            composable(
                Screen.KnownHeadphones.route,
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    KnownHeadphonesScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { profileId ->
                            navController.navigateSafely(Screen.HeadphoneProfile.createRoute(profileId))
                        },
                    )
                }
            }
            composable(
                Screen.HeadphoneProfile.route,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    HeadphoneProfileScreen(
                        profileId = backStackEntry.arguments?.getString("profileId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenEqualizer = { profileId ->
                            navController.navigateSafely(Screen.Equalizer.createRoute(profileId))
                        },
                        onOpenSafetyMax = { profileId ->
                            navController.navigateSafely(Screen.SafetyMaxVolume.createRoute(profileId))
                        },
                    )
                }
            }
            composable(
                Screen.SafetyMaxVolume.route,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                enterTransition = { settingsEnterTransition() },
                exitTransition = { settingsExitTransition() },
                popEnterTransition = { settingsPopEnterTransition() },
                popExitTransition = { settingsPopExitTransition() },
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    SafetyMaxVolumeScreen(
                        profileId = backStackEntry.arguments?.getString("profileId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(
                route = Screen.ArtistSettings.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    ArtistSettingsScreen(navController = navController)
                }
            }
            composable(
                Screen.DelimiterConfig.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    DelimiterConfigScreen(navController = navController)
                }
            }
            composable(
                Screen.WordDelimiterConfig.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    WordDelimiterConfigScreen(navController = navController)
                }
            }
            composable(
                route = Screen.Equalizer.route,
                arguments = listOf(navArgument("headphoneProfileId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    // B30: the DAC's own EQ when a writable DAC's EQ was read, else the software equalizer (decided before either exists).
                    com.lostf1sh.pixelplayeross.presentation.screens.EqualizerRoute(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                route = Screen.DeviceCapabilities.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    com.lostf1sh.pixelplayeross.presentation.screens.DeviceCapabilitiesScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                Screen.AudioBookmarks.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                AudioBookmarksScreen(
                    navController = navController,
                    playerViewModel = playerViewModel,
                    onOpenSidebar = onOpenSidebar
                )
            }
            composable(
                Screen.AudioBookmarkFolder.route,
                arguments = listOf(navArgument("songId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                AudioBookmarkFolderScreen(
                    songId = backStackEntry.arguments?.getString("songId").orEmpty(),
                    navController = navController,
                    playerViewModel = playerViewModel
                )
            }
    }
}

/** Holds the request for the Library while this destination is on the stack, and takes it back when it is left (Back, or a tab pressed). */
@Composable
internal fun LibraryLocateEffect(songId: String, playerViewModel: PlayerViewModel) {
    androidx.compose.runtime.LaunchedEffect(songId) { if (songId.isNotBlank()) playerViewModel.requestLibraryLocate(songId) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { playerViewModel.clearLibraryLocate() } }
}
