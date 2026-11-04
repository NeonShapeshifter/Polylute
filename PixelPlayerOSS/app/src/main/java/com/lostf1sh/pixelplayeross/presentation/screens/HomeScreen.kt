package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.suggestionSeed
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.LocalDapMode
import com.lostf1sh.pixelplayeross.presentation.components.*
import com.lostf1sh.pixelplayeross.presentation.model.homeMixSource
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HomeOverviewViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController, paddingValuesParent: PaddingValues, playerViewModel: PlayerViewModel,
    overview: HomeOverviewViewModel = hiltViewModel(),
) {
    val mixes by playerViewModel.homeMixes.collectAsStateWithLifecycle()
    val listening by overview.state.collectAsStateWithLifecycle()
    val artists by overview.recentArtists.collectAsStateWithLifecycle()
    val openToken by overview.openToken.collectAsStateWithLifecycle()
    val dap=LocalDapMode.current
    val needsFallback=mixes.isReady && mixes.yours.isEmpty() && mixes.daily.isEmpty() && !mixes.dailyHidden
    val fallbackPreview = if(needsFallback) {
        val flow=remember(overview) { overview.fallbackPreview() }
        flow.collectAsStateWithLifecycle(null).value
    } else emptyList()
    val daily=remember(mixes.daily) { mixes.daily.map { it.song }.toImmutableList() }
    val suggestions=remember(mixes.yours,mixes.daily,fallbackPreview) {
        homeMixSource(mixes.yours.map { it.song },mixes.daily.map { it.song },fallbackPreview.orEmpty()).toImmutableList()
    }
    val seed=suggestionSeed("home:${mixes.epochDay}:$openToken:${mixes.previewGeneration}")
    val preview=when {
        dap && needsFallback -> com.lostf1sh.pixelplayeross.data.collections.auto.suggestionPreview(suggestions,suggestionSeed("fallback:${mixes.epochDay}:${mixes.previewGeneration}"))
        dap -> mixes.preview.map { it.song }
        else -> overview.suggestionPicker.pick(suggestions,seed)
    }
    val source=remember { ListenSource(ListenSourceKind.HOME_MIX,"your_mix") }
    val name=stringResource(R.string.home_your_mix_queue_name)
    val recentName=stringResource(R.string.presentation_batch_b_queue_recently_played)
    val recent=remember(listening.recent) { recentCarouselOrder(listening.recent).toImmutableList() }
    val recentQueue=remember(recent) { recent.map { it.song } }
    val player by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentSong to it.isShuffleEnabled }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null to false)
    val compact by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottom=if(player.first != null) MiniPlayerHeight else 0.dp
    val gradient=if(player.first == null) 0.dp else resolveMainScreenBottomGradientHeight(compact,MiniPlayerHeight)
    val status=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val benchmark=androidx.activity.compose.LocalActivity.current?.intent?.getBooleanExtra("is_benchmark",false) == true
    val hasGenerated=mixes.isReady
    val loaded=com.lostf1sh.pixelplayeross.presentation.model.homeContentLoaded(hasGenerated,listening.loaded,needsFallback,fallbackPreview != null)
    var refreshing by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    ReportDrawnWhen { loaded || benchmark }

    PullToRefreshBox(isRefreshing=refreshing,onRefresh={
        if(!refreshing) scope.launch {
            refreshing=true
            try { playerViewModel.refreshSuggestionsPreview().join();overview.refresh() }
            finally { refreshing=false }
        }
    },modifier=Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Dap.page).verticalScroll(rememberScrollState()).testTag("home-scroll")
            .padding(top=status+DapSpacing.Sm,bottom=paddingValuesParent.calculateBottomPadding()+HomeLayout.BottomContentGap+bottom)) {
            DapBrandHeader(Modifier.padding(start=DapSpacing.Gutter,end=DapSpacing.Gutter-DapIconButtonEdgeInset),trailing={
                DapIconButton(DapIconKind.Gear,stringResource(R.string.presentation_batch_d_open_settings_cd),{navController.navigateSafely(Screen.Settings.route)})
            })
            Spacer(Modifier.height(HomeLayout.WordmarkToMixGap))
            if(!loaded) {
                DapSkeletonList(count=3,showArt=true,modifier=Modifier.padding(horizontal=DapSpacing.Gutter).testTag("home-skeleton"))
            } else {
                if(suggestions.isNotEmpty()) {
                    Column(verticalArrangement=Arrangement.spacedBy(DapSpacing.Md),modifier=Modifier.testTag("home-suggestions")) {
                        YourMixHeader(stringResource(R.string.home_your_mix_heading),player.second,
                            onPlayShuffled={ playerViewModel.playSongsShuffled(suggestions,name,startAtZero=true,source=source) },
                            onOpen={navController.navigateSafely(Screen.Suggestions.route)},
                            subtitle=if(mixes.fallback || needsFallback) stringResource(R.string.home_suggestions_library_caption) else null)
                        Row(Modifier.fillMaxWidth().padding(horizontal=DapSpacing.Gutter),horizontalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
                            preview.forEach { song -> DapAlbumGridItem(songTitleLabel(song),songArtistLabel(song),Modifier.weight(1f),
                                artwork={OptimizedAlbumArt(song.albumArtUriString,songTitleLabel(song),Modifier.fillMaxSize(),targetSize=com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize.forColumns(preview.size,DapSpacing.Gutter*2,DapSpacing.Md))},
                                onClick={playerViewModel.showAndPlaySong(song,suggestions,name,source=source)}) }
                        }
                    }
                } else if(daily.isEmpty() && (!mixes.dailyHidden || listening.catalog.isEmpty)) {
                    YourMixEmptyPlaceholder { playerViewModel.refreshLibraryFromHome();playerViewModel.forceUpdateDailyMix();overview.refresh() }
                }
                if(daily.isNotEmpty()) {
                    Spacer(Modifier.height(HomeLayout.SectionGap))
                    DailyMixSection(daily,playerViewModel,
                        onClickOpen={navController.navigateSafely(Screen.AutoCollection.createRoute("daily_mix"))},
                        onNavigateToAlbum={navController.navigateSafelyReplacing(Screen.AlbumDetail.createRoute(it.albumId),Screen.AlbumDetail.route)},
                        onNavigateToArtist={navController.navigateSafelyReplacing(Screen.ArtistDetail.createRoute(it.artistId),Screen.ArtistDetail.route)},
                        onNavigateToGenre={song -> song.genre?.let { navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it,"UTF-8"))) }})
                }
                if(recent.isNotEmpty()) {
                    Spacer(Modifier.height(HomeLayout.SectionGap))
                    RecentlyPlayedSection(recent,{song ->playerViewModel.playSongs(recentQueue,song,recentName,source=ListenSource(ListenSourceKind.RECENT))},
                        {navController.navigateSafely(Screen.RecentlyPlayed.route)},currentSongId=player.first?.id,
                        contentPadding=PaddingValues(horizontal=DapSpacing.Gutter))
                }
                if(artists.isNotEmpty()) {
                    Spacer(Modifier.height(HomeLayout.SectionGap))
                    RecentArtistsSection(artists,{navController.navigateSafely(Screen.ArtistDetail.createRoute(it.id))})
                }
                if(mixes.suggestedAlbums.isNotEmpty()) {
                    Spacer(Modifier.height(HomeLayout.SectionGap))
                    SuggestedAlbumsSection(mixes.suggestedAlbums,{navController.navigateSafely(Screen.AlbumDetail.createRoute(it.id))})
                }
                if(listening.overview != null) {
                    Spacer(Modifier.height(HomeLayout.SectionGap))
                    StatsOverviewCard(modifier=Modifier.testTag("home-stats-card"),summary=listening.overview,onClick={navController.navigateSafely(Screen.Stats.route)})
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(status).background(Dap.bar))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(gradient).background(Brush.verticalGradient(
            colorStops=arrayOf(0f to Color.Transparent,.2f to Color.Transparent,.8f to Dap.fadeEdge,1f to Dap.fadeEdge))))
    }
}

internal object HomeLayout {
    val SectionGap=DapSpacing.SectionGap
    val WordmarkToMixGap=DapSpacing.Sm
    val BottomContentGap=DapSpacing.GroupGap
    fun gapBefore(sectionKey:String):Dp=if(sectionKey == "your_mix_header") WordmarkToMixGap else SectionGap
}

@Composable
internal fun YourMixEmptyPlaceholder(onRefresh:()->Unit) {
    if(!com.lostf1sh.pixelplayeross.LocalMusicAccessGranted.current) return
    DapEmptyState(stringResource(R.string.home_empty_placeholder_title),stringResource(R.string.home_empty_placeholder_subtitle),
        modifier=Modifier.padding(horizontal=DapSpacing.Gutter,vertical=DapSpacing.GroupGap),
        action={DapSecondaryButton(stringResource(R.string.home_empty_placeholder_refresh),onRefresh)})
}

@Composable
fun YourMixHeader(song:String,isShuffleEnabled:Boolean=false,onPlayShuffled:()->Unit,onOpen:(()->Unit)?=null,subtitle:String?=null) {
    Column(Modifier.fillMaxWidth().padding(horizontal=DapSpacing.Gutter)) {
        DapFeatureHeader(song,subtitle=subtitle,onClick=onOpen,trailing={
            DapRoundPlayAction(onPlayShuffled,kind=DapIconKind.Shuffle,size=DapSizes.MinTouchTarget,
                iconSize=DapSpacing.Gutter,contentDescription=stringResource(R.string.cd_shuffle_play))
        })
        if(isShuffleEnabled) DapState(DapStateKind.Selected,stringResource(R.string.shortcut_shuffle_short))
    }
}
