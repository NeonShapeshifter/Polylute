package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.LocalMusicAccessGranted
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.*
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.presentation.LocalDapMode
import com.lostf1sh.pixelplayeross.presentation.components.*
import com.lostf1sh.pixelplayeross.presentation.model.*
import com.lostf1sh.pixelplayeross.presentation.navigation.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import io.mockk.*
import java.io.File
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h805dp-xhdpi")
abstract class B55HomeRenderBase {
    @get:Rule val compose=createComposeRule()
    private val now=Kit.at(2026,11,2,20)
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val lang get()=context.resources.configuration.locales[0].language
    private val out get()=File(System.getenv("B55_RENDERS") ?: "/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B55-gates/png-after/home",lang)
    private val songs=SyntheticHistory.songs(800).mapIndexed { index,s -> s.copy(title="Canción ${index+1}",
        artist="Artista ${index%16}",artists=listOf(ArtistRef((index%16).toLong(),"Artista ${index%16}",true)),dateAdded=index.toLong()) }
    private data class World(val mixes:HomeMixes,val listening:HomeListeningState,val artists:List<Artist>)
    private fun world(noHistory:Boolean=false,emptySuggestions:Boolean=false):World {
        val catalog=Kit.catalog(songs,firstSeen=songs.associate { it.libraryItemId to now-300*Kit.DAY })
        val ledger=Kit.ledger(if(noHistory) emptyList() else SyntheticHistory.events(3000,800,now,70),catalog)
        val collections=AutoCollectionEngine.compute(ledger,now,Kit.zone)
        val day=AutoCollectionRepository.localEpochDay(now,Kit.zone)
        val daily=AutoCollectionEngine.dailyMix(ledger,collections,day).items
        val yours=when {
            emptySuggestions -> emptyList()
            noHistory -> catalog.items.sortedByDescending { it.song.dateAdded }.take(48)
            else -> AutoCollectionEngine.dailyMix(ledger,collections,day,yours=true,excluding=daily.mapTo(hashSetOf()) { it.identity }).items
        }
        val albums=SuggestedAlbums.build(ledger,now,day,collections.getValue(AutoCollectionId.ON_REPEAT).items.mapTo(hashSetOf()) { it.identity }).albums
        val recent=recentListening(ledger,now)
        val credits=recentArtistCredits(recent)
        val overview=listOf(StatsTimeRange.WEEK,StatsTimeRange.MONTH,StatsTimeRange.YEAR,StatsTimeRange.ALL).asSequence()
            .map { StatsEngine.report(ledger,StatsPeriod.Preset(it),now,Kit.zone,StatsEngine.Detail.OVERVIEW) }.firstOrNull { !it.isEmpty }
        return World(HomeMixes(true,daily,yours,day,suggestionPreview(yours,7),albums,noHistory),
            HomeListeningState(true,overview,recent,credits,now,Kit.zone,catalog),credits.map { Artist(it.id,it.name,50) })
    }
    private lateinit var player:PlayerViewModel
    private lateinit var nav:NavController
    private fun show(world:World,ready:Boolean=true,dap:Boolean=false,withBars:Boolean=true) {
        player=mockk(relaxed=true)
        every { player.homeMixes } returns MutableStateFlow(if(ready) world.mixes else HomeMixes.Initial)
        every { player.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { player.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { player.selectedSongForInfo } returns MutableStateFlow(null)
        every { player.navBarCompactMode } returns MutableStateFlow(false)
        val overview=mockk<HomeOverviewViewModel>(relaxed=true)
        every { overview.state } returns MutableStateFlow(world.listening)
        every { overview.recentArtists } returns MutableStateFlow(world.artists)
        every { overview.openToken } returns MutableStateFlow(7L)
        every { overview.suggestionPicker } returns SongSuggestionPicker()
        every { overview.fallbackPreview() } returns flowOf(world.listening.catalog.items.sortedByDescending { it.song.dateAdded }.take(48).map { it.song })
        nav=mockk(relaxed=true)
        val entry=mockk<NavBackStackEntry>(relaxed=true)
        every { nav.currentBackStackEntry } returns entry
        every { entry.lifecycle.currentState } returns androidx.lifecycle.Lifecycle.State.RESUMED
        var view:android.view.View?=null
        compose.setContent {
            view=LocalView.current
            DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
                CompositionLocalProvider(LocalMusicAccessGranted provides true,LocalDapMode provides dap,
                    LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    Box(Modifier.fillMaxSize().background(Dap.page)) {
                        HomeScreen(nav,PaddingValues(bottom=if(withBars) DapHeights.Nav else 0.dp),player,overview)
                        if(withBars) DapBottomNav(listOf(DapNavItem(R.string.tab_home,DapIconKind.Home),DapNavItem(R.string.search,DapIconKind.Search),
                            DapNavItem(R.string.tab_library,DapIconKind.Library)),0,{},modifier=Modifier.align(Alignment.BottomCenter).height(DapHeights.Nav))
                    }
                }
            }
        }
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(view!!,WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,74,0,0)).build()) }
        compose.waitForIdle()
    }
    private fun save(name:String) {
        compose.waitForIdle();out.mkdirs()
        val bitmap=B42Renders.screen(compose)
        assertThat(bitmap.width).isEqualTo(720);assertThat(bitmap.height).isEqualTo(1610)
        File(out,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @After fun cleanup() { unmockkAll() }
    @Test fun `full Home with history from top to bottom`() {
        val w=world();show(w);save("home-top")
        compose.onNodeWithTag("recent-carousel").performScrollTo();save("home-recent")
        compose.onNodeWithTag("home-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,100000f) }
        save("home-bottom")
        verify(exactly=0) { player.homeMixPreviewSongs }
    }
    @Test fun `Home without history has Suggestions and albums but no recent sections`() {
        show(world(noHistory=true));save("home-no-history")
        compose.onNodeWithTag("recent-artists").assertDoesNotExist();compose.onNodeWithTag("recent-carousel").assertDoesNotExist()
    }
    @Test fun `empty Suggestions does not repeat Daily Mix`() {
        show(world(emptySuggestions=true));save("home-suggestions-empty")
        compose.onNodeWithTag("home-suggestions").assertDoesNotExist()
    }
    @Test fun `Home skeleton stays until the mixes have generated`() {
        show(world(),ready=false);save("home-loading")
        compose.onNodeWithTag("home-skeleton").assertIsDisplayed();compose.onNodeWithText(context.getString(R.string.home_empty_placeholder_title)).assertDoesNotExist()
    }
    @Test fun `a Suggestions cover plays the entire page order with the historical source key`() {
        val w=world();show(w,dap=true)
        val song=w.mixes.preview.first().song
        compose.onNode(hasClickAction() and hasText(song.title)).performClick()
        verify { player.showAndPlaySong(song,w.mixes.yours.map { it.song },context.getString(R.string.home_your_mix_queue_name),source=ListenSource(ListenSourceKind.HOME_MIX,"your_mix")) }
    }
    @Test fun `recent artists and suggested albums open their actual pages`() {
        val w=world();show(w)
        compose.onNodeWithTag("recent-artists").performScrollTo()
        val artist=w.artists.first()
        compose.onNode(hasText(artist.name) and hasClickAction() and hasAnyAncestor(hasTestTag("recent-artists"))).performClick()
        verify { nav.navigate(Screen.ArtistDetail.createRoute(artist.id),any<NavOptionsBuilder.()->Unit>()) }
        compose.onNodeWithTag("suggested-albums").performScrollTo()
        val album=w.mixes.suggestedAlbums.first()
        compose.onNode(hasText(album.title) and hasClickAction() and hasAnyAncestor(hasTestTag("suggested-albums"))).performClick()
        verify { nav.navigate(Screen.AlbumDetail.createRoute(album.id),any<NavOptionsBuilder.()->Unit>()) }
    }
    @Test fun `section headers open their pages and the Suggestions shuffle owns its own action`() {
        val w=world();show(w)
        // B57-fix1 1 changes this on purpose: the Daily Mix header has its own round Shuffle button (the second on the page; the first is Your mix's), its title is text, and "See all" is the one
        // control that opens the page.
        val shuffles=compose.onAllNodesWithContentDescription(context.getString(R.string.cd_shuffle_play))
        shuffles[0].performClick()
        verify { player.playSongsShuffled(w.mixes.yours.map { it.song },context.getString(R.string.home_your_mix_queue_name),startAtZero=true,source=ListenSource(ListenSourceKind.HOME_MIX,"your_mix")) }
        verify(exactly=0) { nav.navigate(Screen.Suggestions.route,any<NavOptionsBuilder.()->Unit>()) }
        compose.onNodeWithText(context.getString(R.string.home_your_mix_heading)).performClick()
        verify { nav.navigate(Screen.Suggestions.route,any<NavOptionsBuilder.()->Unit>()) }
        compose.onNodeWithText(context.getString(R.string.home_daily_mix_title)).performClick()
        verify(exactly=0) { nav.navigate(Screen.AutoCollection.createRoute("daily_mix"),any<NavOptionsBuilder.()->Unit>()) }
        shuffles[1].performClick()
        verify { player.playSongsShuffled(w.mixes.daily.map { it.song },context.getString(R.string.presentation_batch_g_daily_mix_queue_name),startAtZero=true,source=ListenSource(ListenSourceKind.HOME_MIX,"daily_mix")) }
        verify(exactly=0) { nav.navigate(Screen.AutoCollection.createRoute("daily_mix"),any<NavOptionsBuilder.()->Unit>()) }
        compose.onNodeWithText(context.getString(R.string.presentation_batch_g_daily_mix_see_all,context.getString(R.string.collection_name_daily_mix))).performClick()
        verify { nav.navigate(Screen.AutoCollection.createRoute("daily_mix"),any<NavOptionsBuilder.()->Unit>()) }
        compose.onNodeWithTag("home-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,100000f) }
        compose.onNodeWithText(context.getString(R.string.presentation_batch_g_stats_overview_title)).performClick()
        verify(exactly=1) { nav.navigate(Screen.Stats.route,any<NavOptionsBuilder.()->Unit>()) }
    }

    @Test fun `render the Recently played grid`() {
        val w=world()
        compose.setContent { DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.page).padding(top=37.dp)) {
                RecentlyPlayedSection(w.listening.recent.take(6).toImmutableList(),{},{},contentPadding=PaddingValues(horizontal=DapSpacing.Gutter))
            }
        } }
        save("recent-carousel")
    }
    @Test fun `render Recent artists`() {
        val w=world()
        compose.setContent { DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.page).padding(top=37.dp)) { RecentArtistsSection(w.artists,{}) }
        } }
        save("recent-artists")
    }
    @Test fun `render Suggested albums`() {
        val w=world()
        compose.setContent { DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.page).padding(top=37.dp)) { SuggestedAlbumsSection(w.mixes.suggestedAlbums,{}) }
        } }
        save("suggested-albums")
    }
    @Test fun `render Monday listening stats`() {
        val w=world()
        compose.setContent { DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
            Column(Modifier.fillMaxSize().background(Dap.page).padding(top=37.dp)) { StatsOverviewCard(summary=w.listening.overview,onClick={}) }
        } }
        save("stats-monday")
    }
    @Test
    @Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h2400dp-xhdpi")
    fun `render the whole Home as an auxiliary tall export`() {
        show(world(),withBars=false)
        val bottom=compose.onNodeWithTag("home-stats-card").fetchSemanticsNode().boundsInRoot.bottom.toInt()+48
        val bitmap=B42Renders.screen(compose)
        val cropped=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bottom.coerceAtMost(bitmap.height))
        out.mkdirs();File(out,"home-full.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG,100,it) }
        cropped.recycle();bitmap.recycle()
    }

    @Test fun `render the grid artists albums Monday stats and Suggestions page`() {
        val w=world()
        compose.setContent { DapTheme(baseTheme=DapBaseTheme.Graphite,accent=DapAccent.Sage) {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                SuggestionsPage(w.mixes.yours.map { it.song },true,false,{},{},{})
            }
        } }
        save("suggestions-page")
    }
}
@Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h805dp-xhdpi")
class B55HomeRenderSpanishTest:B55HomeRenderBase()
@Config(sdk=[35],application=Application::class,qualifiers="en-w360dp-h805dp-xhdpi")
class B55HomeRenderEnglishTest:B55HomeRenderBase()
