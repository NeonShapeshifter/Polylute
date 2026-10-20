package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing

import android.content.Intent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.CollagePattern
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButtonEdgeInset
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.presentation.components.AlbumArtCollage
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixSection
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.OptimizedAlbumArt
import com.lostf1sh.pixelplayeross.presentation.components.RecentlyPlayedSection
import com.lostf1sh.pixelplayeross.presentation.components.RecentlyPlayedSectionMinSongsToShow
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.StatsOverviewCard
import com.lostf1sh.pixelplayeross.presentation.components.resolveMainScreenBottomGradientHeight
import com.lostf1sh.pixelplayeross.presentation.model.collectRecentlyPlayedSongIds
import com.lostf1sh.pixelplayeross.presentation.model.mapRecentlyPlayedSongs
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.PlayingEqIcon
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import java.util.Locale
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import coil.size.Size
import com.lostf1sh.pixelplayeross.ui.ds.DapRoundPlayAction

private const val HomeLoadingPlaceholderMinDurationMillis = 1200L

/**
 * How long to wait after Home's content arrives before asking the list to pre-compose the item
 * below the fold. Long enough that the screen has settled and the first frames are done with.
 */
private const val HomePrefetchNudgeDelayMillis = 1500L

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onOpenSidebar: () -> Unit
) {
    val context = LocalContext.current
    val isBenchmarkMode = remember {
        (context as? android.app.Activity)?.intent?.getBooleanExtra("is_benchmark", false) ?: false
    }
    val statsViewModel: StatsViewModel = hiltViewModel()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val dailyMixSongs by playerViewModel.dailyMixSongs.collectAsStateWithLifecycle()
    val curatedYourMixSongs by playerViewModel.yourMixSongs.collectAsStateWithLifecycle()
    val homeMixPreviewSongs by playerViewModel.homeMixPreviewSongs.collectAsStateWithLifecycle()
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val usesFallbackHomeMix = remember(curatedYourMixSongs, dailyMixSongs) {
        curatedYourMixSongs.isEmpty() && dailyMixSongs.isEmpty()
    }
    val yourMixSongs = remember(curatedYourMixSongs, dailyMixSongs, homeMixPreviewSongs) {
        when {
            curatedYourMixSongs.isNotEmpty() -> curatedYourMixSongs
            dailyMixSongs.isNotEmpty() -> dailyMixSongs
            else -> homeMixPreviewSongs
        }
    }
    var homePlaceholderRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var hasHomeLoadingMinimumElapsed by rememberSaveable(homePlaceholderRefreshGeneration) {
        mutableStateOf(false)
    }

    LaunchedEffect(homePlaceholderRefreshGeneration, yourMixSongs.isEmpty()) {
        if (yourMixSongs.isEmpty()) {
            hasHomeLoadingMinimumElapsed = false
            delay(HomeLoadingPlaceholderMinDurationMillis)
            hasHomeLoadingMinimumElapsed = true
        } else {
            hasHomeLoadingMinimumElapsed = true
        }
    }

    val shouldShowYourMixLoadingPlaceholder = yourMixSongs.isEmpty() && !hasHomeLoadingMinimumElapsed
    val recentSongIds = remember(playbackHistory) {
        collectRecentlyPlayedSongIds(
            playbackHistory = playbackHistory,
            maxItems = 64
        )
    }
    val recentlyPlayedSourceSongsInitialValue = remember(recentSongIds) {
        if (recentSongIds.isEmpty()) persistentListOf<Song>() else null
    }
    val recentlyPlayedSourceSongs by remember(recentSongIds, playerViewModel) {
        playerViewModel.observeSongs(recentSongIds)
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = recentlyPlayedSourceSongsInitialValue)
    val latestRecentlyPlayedSongs = remember(playbackHistory, recentlyPlayedSourceSongs) {
        val sourceSongs = recentlyPlayedSourceSongs ?: return@remember emptyList()
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = sourceSongs,
            maxItems = 64
        )
    }
    var recentlyPlayedSongs by rememberSaveable { mutableStateOf(latestRecentlyPlayedSongs) }
    val latestRecentlyPlayedSongsState = rememberUpdatedState(latestRecentlyPlayedSongs)

    LaunchedEffect(latestRecentlyPlayedSongs, lifecycleOwner) {
        val isHomeVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (recentlyPlayedSongs.isEmpty() || !isHomeVisible) {
            recentlyPlayedSongs = latestRecentlyPlayedSongs
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recentlyPlayedSongs = latestRecentlyPlayedSongsState.value
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val recentlyPlayedQueue = remember(recentlyPlayedSongs) {
        recentlyPlayedSongs.map { it.song }.toImmutableList()
    }

    ReportDrawnWhen {
        yourMixSongs.isNotEmpty() || hasHomeLoadingMinimumElapsed || isBenchmarkMode
    }

    // Both of Home's mix labels were English string literals, so a Spanish Home showed a Spanish
    // "TU MIX" header above an English "Today's Mix for you" headline, and queued the mix under an
    // English name. The header itself already had a resource; these two did not.
    val yourMixSong: String = stringResource(R.string.home_your_mix_heading)
    val yourMixQueueName: String = stringResource(R.string.home_your_mix_queue_name)

    val currentSong by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong }
    }.collectAsStateWithLifecycle(initialValue = null)

    val isShuffleEnabled by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState
            .map { it.isShuffleEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val bottomPadding = if (currentSong != null) MiniPlayerHeight else 0.dp
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode, MiniPlayerHeight)

    LocalContext.current

    val homeStatsOverview by statsViewModel.homeOverview.collectAsStateWithLifecycle()

    // The page owns the status-bar inset now that it owns its own title: there is no app bar to
    // hold it, and the first line of content must not sit under the system clock.
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // PHASE 14.0H — Home is a bounded, retained surface, so three pieces of the LazyColumn era are
    // gone with it:
    //
    //  * the manual ON_PAUSE save / scrollToItem restore. `rememberScrollState()` is already
    //    saveable, so the scroll position survives exactly as well without a hand-rolled pair of
    //    remembered ints and an index that a LazyListState needed and a Column does not.
    //  * the 1 px `dispatchRawDelta(+1/-1)` priming nudge. It existed to force a measure pass so
    //    the LazyColumn would prefetch the item below the fold, because once Home settled it drew
    //    zero frames and never ran another measure. Home now composes every section once and keeps
    //    it, so there is no "item below the fold" to prime — and it was a LazyListState call, which
    //    is why it could not simply be left in place.

    androidx.compose.runtime.SideEffect {
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Home carries no app bar. The wordmark is the first thing on the page and behaves like
        // everything else on the page: it scrolls away. It is deliberately not sticky, does not
        // shrink into a toolbar, and leaves nothing of itself behind. The one global action rides
        // in the title row and scrolls away with it.
        // Each section's content is built once and handed to its lazy item. The bodies are
        // unchanged from the shipped screen; only the hosting moved out of the DSL.
            val secHomeTitle: @Composable () -> Unit = {
                // The page title, with Home's one global action at its end. The gear used to float
                // over the page in a translucent box, so the page showed through it as it scrolled
                // and it sat off the gutter. In the title row it scrolls away with the title, like
                // the rest of the page; the end padding puts the glyph, not its target, on the gutter.
                com.lostf1sh.pixelplayeross.ui.ds.DapBrandHeader(
                    modifier = Modifier.padding(
                        start = DapSpacing.Gutter,
                        end = DapSpacing.Gutter - DapIconButtonEdgeInset,
                    ),
                    trailing = {
                        DapIconButton(
                            kind = DapIconKind.Gear,
                            contentDescription = stringResource(R.string.presentation_batch_d_open_settings_cd),
                            onClick = { navController.navigateSafely(Screen.Settings.route) },
                        )
                    },
                )
            
            }
            val secYourMixPlaceholder: @Composable () -> Unit = {
                        if (shouldShowYourMixLoadingPlaceholder) {
                            YourMixLoadingPlaceholder()
                        } else {
                            YourMixEmptyPlaceholder(
                                onRefresh = {
                                    homePlaceholderRefreshGeneration++
                                    settingsViewModel.refreshLibrary()
                                    playerViewModel.forceUpdateDailyMix()
                                }
                            )
                        }
                    
            }
            val secYourMixHeader: @Composable () -> Unit = {
                        YourMixHeader(
                            song = yourMixSong,
                            isShuffleEnabled = isShuffleEnabled,
                            onPlayShuffled = {
                                if (usesFallbackHomeMix) {
                                    playerViewModel.shuffleAllSongs(queueName = yourMixQueueName)
                                } else {
                                    playerViewModel.playSongsShuffled(
                                        songsToPlay = yourMixSongs,
                                        queueName = yourMixQueueName,
                                        startAtZero = true,
                                        source = ListenSource(ListenSourceKind.HOME_MIX, "your_mix"),
                                    )
                                }
                            }
                        )
                    
            }
            val secMixCovers: @Composable () -> Unit = {
                        // Three covers, the same size as any other grid cell in the product. The
                        // mix is named by the block above it; this is just the way in.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DapSpacing.Gutter),
                            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)
                        ) {
                            yourMixSongs.take(3).forEach { song ->
                                DapAlbumGridItem(
                                    title = songTitleLabel(song),
                                    subtitle = songArtistLabel(song),
                                    modifier = Modifier.weight(1f),
                                    artwork = {
                                        OptimizedAlbumArt(
                                            uri = song.albumArtUriString,
                                            title = songTitleLabel(song),
                                            // These cells are a third of the screen wide, so the
                                            // 2048 default was asking for an image eight times
                                            // larger than the cell could ever show — and larger
                                            // than the extracted file (capped at 1536) can serve.
                                            // The Library grid already asks for 256.
                                            targetSize = Size(256, 256),
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    },
                                    onClick = {
                                        if (usesFallbackHomeMix) {
                                            playerViewModel.showAndPlaySongFromLibrary(
                                                song,
                                                queueName = yourMixQueueName
                                            )
                                        } else {
                                            playerViewModel.showAndPlaySong(song, yourMixSongs, yourMixQueueName, source = ListenSource(ListenSourceKind.HOME_MIX, "your_mix"))
                                        }
                                    }
                                )
                            }
                        }
                    
            }
            val secDailyMixSection: @Composable () -> Unit = {
                        DailyMixSection(
                            songs = dailyMixSongs,
                            onClickOpen = {
                                navController.navigateSafely(
                                    Screen.AutoCollection.createRoute(
                                        com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId.DAILY_MIX.key,
                                    ),
                                )
                            },
                            onNavigateToAlbum = { song ->
                                navController.navigateSafelyReplacing(
                                    route = Screen.AlbumDetail.createRoute(song.albumId),
                                    patternToPop = Screen.AlbumDetail.route
                                )
                            },
                            onNavigateToArtist = { song ->
                                navController.navigateSafelyReplacing(
                                    route = Screen.ArtistDetail.createRoute(song.artistId),
                                    patternToPop = Screen.ArtistDetail.route
                                )
                            },
                            onNavigateToGenre = { song ->
                                song.genre?.let {
                                    navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
                                }
                            },
                            playerViewModel = playerViewModel
                        )
                    
            }
            val secRecentlyPlayedSection: @Composable () -> Unit = {
                        RecentlyPlayedSection(
                            songs = remember(recentlyPlayedSongs) { recentlyPlayedSongs.toImmutableList() },
                            onSongClick = { song ->
                                if (recentlyPlayedQueue.isNotEmpty()) {
                                    playerViewModel.playSongs(
                                        songsToPlay = recentlyPlayedQueue,
                                        startSong = song,
                                        queueName = "Recently Played",
                                        source = ListenSource(ListenSourceKind.RECENT)
                                    )
                                }
                            },
                            onOpenAllClick = {
                                navController.navigateSafely(Screen.RecentlyPlayed.route)
                            },
                            themeStateHolder = playerViewModel.themeStateHolder,
                            currentSongId = currentSong?.id,
                            contentPadding = PaddingValues(horizontal = DapSpacing.Gutter)
                        )
                    
            }
            val secListeningStatsPreview: @Composable () -> Unit = {
                        StatsOverviewCard(
                            summary = homeStatsOverview,
                            onClick = { navController.navigateSafely(Screen.Stats.route) }
                        )
                    
            }
            // PHASE 14.0H — one section list, consumed by both hosts, so the LAZY and
            // RETAINED arms cannot render different content. (This list already existed
            // here as a buildList whose result was never read; it is now the actual source.)
            val homeSections = buildList {
                add("home_title" to secHomeTitle)
                if (yourMixSongs.isEmpty()) {
                    add("your_mix_placeholder" to secYourMixPlaceholder)
                } else {
                    add("your_mix_header" to secYourMixHeader)
                }
                if (yourMixSongs.isNotEmpty()) add("mix_covers" to secMixCovers)
                if (dailyMixSongs.isNotEmpty()) add("daily_mix_section" to secDailyMixSection)
                if (recentlyPlayedSongs.size >= RecentlyPlayedSectionMinSongsToShow) {
                    add("recently_played_section" to secRecentlyPlayedSection)
                }
                if (homeStatsOverview != null) add("listening_stats_preview" to secListeningStatsPreview)
            }
            val homeContentPadding = PaddingValues(
                top = statusBarInset + DapSpacing.Sm,
                bottom = paddingValuesParent.calculateBottomPadding() + 38.dp + bottomPadding
            )
            // Home is retained rather than lazy. Its scroll range is about one viewport beyond
            // the screen and its content is a handful of bounded sections, so LazyColumn's
            // bargain — dispose what leaves the viewport, recompose it on return — bought
            // nothing and cost the whole defect: every re-entry of an expensive section was a
            // from-scratch composition on the frame the finger moved. Measured with the Part Y/Z
            // gesture, switching the host alone took the worst drag frame from 32.9 / 34.3 ms to
            // 10.6 / 9.4 ms, with zero frames over 16.7 ms and zero recompositions during the
            // drag, at a cost of about 6.9 MB PSS. This is specific to this bounded surface;
            // Library's list is long and stays lazy.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Dap.background)
                    .verticalScroll(rememberScrollState())
                    .padding(homeContentPadding),
            ) {
                homeSections.forEachIndexed { index, (key, content) ->
                    if (index > 0) Spacer(Modifier.height(HomeLayout.gapBefore(key)))
                    content()
                }
            }

        // The status bar gets the page colour. Without it the scrolled page ran under the system
        // clock and icons; the page itself still scrolls away, only this strip stays.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarInset)
                .background(Dap.background)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(bottomGradientHeight)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to Dap.background,
                            1.0f to Dap.background
                        )
                    )
                )
        ) {

        }
    }
}

/**
 * The vertical rhythm of Home's sections (B32-Q1). Every section is 24 dp from the one above it, except Today's mix: its heading sits
 * closer to the wordmark, which is one line of branding and not a section of content, so the gap between them is about a third
 * shorter than it was (the wordmark itself is 15 % smaller, see `DapBrandMaxWidth`, which on its own would have *widened* the gap by
 * leaving the mark further from the bottom of its 48 dp row).
 */
internal object HomeLayout {
    val SectionGap = 24.dp

    /** What the gap before the mix heading is: the section gap less what the owner asked to take off the wordmark's. */
    val WordmarkToMixGap = 7.dp

    fun gapBefore(sectionKey: String): Dp = if (sectionKey == "your_mix_header") WordmarkToMixGap else SectionGap
}

@Composable
private fun YourMixLoadingPlaceholder() {
    // Static skeletons in bg/2 while the mix resolves. Nothing spins.
    DapSkeletonList(
        count = 3,
        showArt = true,
        modifier = Modifier.padding(horizontal = DapSpacing.Gutter)
    )
}

@Composable
private fun YourMixEmptyPlaceholder(
    onRefresh: () -> Unit
) {
    DapEmptyState(
        title = stringResource(R.string.home_empty_placeholder_title),
        message = stringResource(R.string.home_empty_placeholder_subtitle),
        modifier = Modifier.padding(
            horizontal = DapSpacing.Gutter,
            vertical = DapSpacing.GroupGap
        ),
        action = {
            DapSecondaryButton(
                label = stringResource(R.string.home_empty_placeholder_refresh),
                onClick = onRefresh
            )
        }
    )
}

/**
 * Today's mix, as a section rather than a hero: the mix's own name at heading size and one filled action. No fixed-height
 * box, no oversized corner radius, no FAB.
 *
 * B25-S7: **one header.** A tracked "TU MIX" label used to sit over "Tu mix de hoy", saying the same thing twice; the
 * heading is the header, and the shuffle disc rides on its row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YourMixHeader(
    song: String,
    isShuffleEnabled: Boolean = false,
    onPlayShuffled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter)
    ) {
        // The action rides on the title's own row rather than below it.
        //
        // It used to sit on a line of its own under the heading, which cost a full button's height
        // of vertical space between the heading and the artwork and left the shuffle action reading
        // as a separate section. On the row, the title takes what is left after the button, so the
        // two can meet but never overlap at any width or font scale.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = song,
                style = DapType.Heading,
                color = Dap.primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DapSpacing.Md))
            // A round shuffle action rather than a labelled pill. The word was the largest thing on
            // the header for the smallest decision on it; the glyph carries the same meaning at a
            // glance, and the description below keeps the name for a screen reader.
            DapRoundPlayAction(
                onClick = onPlayShuffled,
                kind = DapIconKind.Shuffle,
                size = DapSizes.MinTouchTarget,
                iconSize = 20.dp,
                contentDescription = stringResource(R.string.cd_shuffle_play),
            )
        }
        // The status line keeps its own row, directly under the title it qualifies and no taller
        // than the words in it.
        if (isShuffleEnabled) {
            Spacer(Modifier.height(DapSpacing.Xs))
            DapState(
                kind = DapStateKind.Selected,
                label = stringResource(R.string.shortcut_shuffle_short)
                    .uppercase(currentLocale())
            )
        }
    }
}


@Composable
fun SongListItemFavs(
    modifier: Modifier = Modifier,
    cardCorners: Dp = 12.dp,
    title: String,
    artist: String,
    albumArtUrl: String?,
    isPlaying: Boolean,
    isCurrentSong: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (isCurrentSong) colors.primaryContainer.copy(alpha = 0.46f) else colors.surfaceContainer
    val contentColor = if (isCurrentSong) colors.primary else colors.onSurface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cardCorners),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(0.9f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = albumArtUrl,
                    contentDescription = stringResource(R.string.cd_album_art_for_title, title),
                    contentScale = ContentScale.Crop,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist, style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (isCurrentSong) {
                PlayingEqIcon(
                    modifier = Modifier
                        .weight(0.1f)
                        .padding(start = 8.dp)
                        .size(width = 18.dp, height = 16.dp),
                    color = colors.primary,
                    isPlaying = isPlaying
                )
            }
        }
    }
}
