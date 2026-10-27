package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B42-fix1, items 1 and 2, over the source: what must not be there any more, and what the pages are made of.
 *
 *  - **No restart-song gesture, no action, no UI, no string** (item 2). The lyrics view used to carry a drag that, past a threshold, called
 *    `onPrev()` (a swipe one way) or `onNext()` (the other) and slid a big accent button with a Skip icon in from the side of the screen: the
 *    "big button that restarts the song" the owner found legacy. Nothing of it remains, anywhere. What stays is the standard rule of the Previous
 *    button itself ([com.lostf1sh.pixelplayeross.presentation.viewmodel.PreviousTrackPolicy]: in its first seconds a song restarts instead of going
 *    back), which has nothing to do with a gesture.
 *  - **The lyrics page has no tabs, no handle and no "Up next"**; the "A continuación" strip is Player's alone.
 *  - **The options are one body** drawn as a sheet or as the Menu page; **the details are one binding** for the window and the page.
 *  - **The pager is lazy and says nothing**: no tab row, no indicator, no dots; the cover's swipe is its own.
 */
class B42Fix1PlayerPagesBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val main: File = root.resolve("src/main/java/com/lostf1sh/pixelplayeross")
    private val res: File = root.resolve("src/main/res")

    private fun source(relative: String): String = main.resolve(relative).readText()
    private fun sources(): List<File> = main.walkTopDown().filter { it.extension == "kt" }.toList()
    private fun relative(file: File) = file.relativeTo(main).invariantSeparatorsPath

    /** The code of a file with its comments left out: what these tests are about is what is called, not what is said. */
    private fun code(text: String): String {
        // string literals first: a "audio/*" is not the start of a block comment
        val withoutStrings = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"").replace(text, "\"\"")
        val withoutBlocks = Regex("""/\*[\s\S]*?\*/""").replace(withoutStrings, "")
        return withoutBlocks.lines().joinToString("\n") { it.substringBefore("//") }
    }

    // ── item 2: the restart-song gesture is gone ─────────────────────────────────────────────────────

    @Test
    fun the_lyrics_view_has_no_swipe_to_skip_and_no_big_button() {
        val lyrics = code(source("presentation/components/LyricsSheet.kt"))
        for (gone in listOf(
            "swipeProgress", "isSwipeActive", "hasTriggeredAction", "dragOffset", "overlayTranslation", "swipeThreshold",
            "onPrev", "onNext", "SkipPrevious", "SkipNext", "detectDragGestures", "onBackClick", "PredictiveBackHandler",
            "respectSystemBackEdges",
        )) {
            assertThat(lyrics).doesNotContain(gone)
        }
    }

    @Test
    fun no_source_slides_a_skip_button_in_from_the_side_of_the_screen() {
        val offenders = sources().filter { file ->
            val text = code(file.readText())
            // the overlay: a drag's progress driving a translation of a Skip icon's box; or the call it committed with
            text.contains("swipeProgress") || text.contains("if (dragOffset > 0) onPrev() else onNext()")
        }.map(::relative)
        assertThat(offenders).isEmpty()
        // and the lyrics view's old entry in the player's header does not exist either
        assertThat(sources().filter { code(it.readText()).contains("PlayerLyricsLayer") }.map(::relative)).isEmpty()
    }

    @Test
    fun there_is_no_restart_song_string_in_any_language() {
        val files = res.walkTopDown().filter { it.name.startsWith("strings") && it.extension == "xml" }.toList()
        assertThat(files).isNotEmpty()
        for (file in files) {
            val names = Regex("""name="([a-z0-9_]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
            val restarts = names.filter { "restart_song" in it || "restart_track" in it || "restart_playback" in it }
            assertThat(restarts).isEmpty()
        }
    }

    @Test
    fun the_standard_rule_of_the_previous_button_is_what_stays() {
        val policy = source("presentation/viewmodel/PreviousTrackPolicy.kt")
        assertThat(policy).contains("object PreviousTrackPolicy")
        // the player's Previous goes through it, on the same position the button decides with
        assertThat(source("presentation/components/player/PlayerSkipCoordinator.kt")).contains("PreviousTrackPolicy")
        assertThat(source("presentation/components/player/PlayerActionsWiring.kt")).contains("previousDecisionPositionMs")
    }

    // ── item 2: what the lyrics page does not draw ───────────────────────────────────────────────────

    @Test
    fun the_lyrics_page_draws_no_tabs_no_handle_and_no_up_next() {
        val lyrics = code(source("presentation/components/LyricsSheet.kt"))
        for (absent in listOf("TabRow", "Tab(", "DapTabUnderline", "DragHandle", "QueueFoldedStrip", "queue_title_next", "player_queue_peek_open")) {
            assertThat(lyrics).doesNotContain(absent)
        }
        val entry = code(source("presentation/components/player/PlayerLyricsEntry.kt"))
        assertThat(entry).doesNotContain("QueueFoldedStrip")
    }

    @Test
    fun the_up_next_strip_belongs_to_the_player_page_only() {
        val queue = code(source("presentation/components/QueueSurface.kt"))
        assertThat(queue).contains("LocalPlayerPageSignal.current")
        assertThat(queue).contains("pageSignal?.onPlayerPage == false")
        // the screen is what says which page it is on, and puts it back when it goes
        val screen = code(source("presentation/components/player/FullPlayerScreen.kt"))
        assertThat(screen).contains("SideEffect { signal.page = page }")
        assertThat(screen).contains("onDispose { signal.page = PlayerPage.Start }")
        // the lyrics page keeps clear of the navigation bar only, not of a strip that is not there
        val lyricsPage = screen.substringAfter("PlayerPage.LYRICS -> PlayerLyricsPage(").substringBefore("PlayerPage.DETAILS")
        assertThat(lyricsPage).doesNotContain("QueuePeekHeight")
    }

    // ── item 1: the pager ────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_pager_is_lazy_claims_the_back_edges_and_draws_no_indicator() {
        val pages = code(source("presentation/components/player/PlayerPages.kt"))
        val screen = code(source("presentation/components/player/FullPlayerScreen.kt"))
        val pager = screen.substringAfter("HorizontalPager(").substringBefore("PlayerPage.entries[index]")
        assertThat(pager).contains("beyondViewportPageCount = 0")
        assertThat(pager).contains(".respectSystemBackEdgesForHorizontalDrag()")
        assertThat(pager).contains("CustomAccessibilityAction(optionsLabel)")
        assertThat(pager).contains("CustomAccessibilityAction(lyricsLabel)")
        assertThat(pager).contains("CustomAccessibilityAction(detailsLabel)")
        for (indicator in listOf("TabRow", "ScrollableTabRow", "PagerIndicator", "HorizontalPagerIndicator", "DotsIndicator", "TabRowDefaults")) {
            assertThat(screen).doesNotContain(indicator)
            assertThat(pages).doesNotContain(indicator)
        }
        // pages are in this order, and the player opens on Player
        assertThat(pages).contains("MENU,\n    PLAYER,\n    LYRICS,\n    DETAILS;")
        assertThat(pages).contains("val Start = PLAYER")
    }

    @Test
    fun the_cover_keeps_its_own_swipe_and_the_page_is_told_where_it_starts() {
        val cover = code(source("presentation/components/player/PlayerCoverSurface.kt"))
        assertThat(cover).contains(".keepHorizontalSwipeOnThisSurface()")
        val pages = code(source("presentation/components/player/PlayerPages.kt"))
        // what the cover's pager cannot use is taken, not passed on to the player's pager
        assertThat(pages).contains("override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(available.x, 0f)")
        assertThat(pages).contains("override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(available.x, 0f)")
        // the queue drag is on the player page only
        val screen = code(source("presentation/components/player/FullPlayerScreen.kt"))
        assertThat(screen).contains("if (!isFullyExpanded || !onPlayerPage()) return@awaitEachGesture")
    }

    @Test
    fun the_header_icons_jump_to_their_pages_and_back_is_the_pagers() {
        val screen = code(source("presentation/components/player/FullPlayerScreen.kt"))
        assertThat(screen).contains("pages.goTo(PlayerPage.DETAILS)")
        assertThat(screen).contains("lyricsEntry.open()")
        assertThat(screen).contains("BackHandler(enabled = offPlayer) { pages.back() }")
        // the old route to the details (a window opened from the header) is not wired from the player any more
        assertThat(code(source("presentation/components/player/FullPlayerUiModels.kt"))).doesNotContain("onShowDetails")
        assertThat(code(source("presentation/components/player/PlayerActionsWiring.kt"))).doesNotContain("onShowDetails")
    }

    // ── item 1: one body for the options, one binding for the details ────────────────────────────────

    @Test
    fun the_options_are_one_body_in_a_sheet_or_on_the_menu_page() {
        val sheet = code(source("presentation/components/SongInfoBottomSheet.kt"))
        assertThat(sheet).contains("val optionsColumn: @Composable (Modifier) -> Unit")
        assertThat(Regex("""optionsColumn\(""").findAll(sheet).count()).isEqualTo(2)
        assertThat(Regex("""\bGlassModalBottomSheet\(""").findAll(sheet).count()).isEqualTo(1)
        assertThat(sheet).contains("enum class SongOptionsPresentation { SHEET, PAGE }")

        // the menu page is the same layer, with the same wiring, asked for as a page: the player's actions are called from one place
        val content = code(source("presentation/components/player/FullPlayerContent.kt"))
        assertThat(content).contains("UnifiedPlayerSongInfoLayer(")
        assertThat(content).contains("presentation = SongOptionsPresentation.PAGE")
        assertThat(content).contains("onShowTrackDetails = { pages.goTo(PlayerPage.DETAILS) }")
        // and the page has no copy of the options' wiring: what a row does is called from the layer the sheet uses, and from nowhere in the player
        for (file in listOf("FullPlayerContent.kt", "FullPlayerScreen.kt", "PlayerPages.kt")) {
            val text = code(source("presentation/components/player/$file"))
            for (call in listOf("addSongToQueue(", "addSongNextToQueue(", "deleteFromDevice(", "editSongMetadata(", "toggleFavoriteSpecificSong(")) {
                assertThat(text).doesNotContain(call)
            }
        }
    }

    @Test
    fun the_details_window_and_the_details_page_share_one_binding() {
        val host = code(source("presentation/components/player/TrackDetailsHost.kt"))
        assertThat(Regex("""rememberTrackDetailsBinding\(""").findAll(host).count()).isEqualTo(3) // the definition, the window, the page
        assertThat(host).contains("TrackDetailsBody(")
        val sheet = code(source("presentation/components/player/TrackDetailsSheet.kt"))
        assertThat(sheet).contains("internal fun TrackDetailsBody(")
        // the page takes the playing song, so it follows playback
        assertThat(code(source("presentation/components/player/FullPlayerContent.kt"))).contains("TrackDetailsPage(song = song, playerViewModel = playerViewModel)")
    }
}
