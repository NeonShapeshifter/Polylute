package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B42: which sheets are glass in "Cover glass", which are not, and why: pinned, so a sheet added tomorrow cannot silently stay opaque.
 *
 *  - **A bottom sheet is opened in one place** (`GlassModalBottomSheet`); nothing else calls Material's `ModalBottomSheet`. The sheets that
 *    call it are exactly the ones listed here: a new one fails this test until it is added (and so is drawn on its own copy of the glass).
 *  - **A window of its own that is a whole screen** (a full-screen `Dialog`) is one of three things, each listed by file: it **carries the
 *    glass** (`GlassWindowBackdrop`), it is **left out** with the reason, or it is a **small dialog** (a card in the middle of the screen: opaque,
 *    tinted by the cover, as it was). A new `Dialog` in a file that is not listed fails this test.
 *  - **The side drawer** is the one drawer of the app (a `ModalNavigationDrawer` from the left edge), left out and opaque.
 *  - Small dialogs (`AlertDialog`), dropdowns and menus are never glass: nothing here touches them.
 */
class GlassSheetBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val main: File = root.resolve("src/main/java/com/lostf1sh/pixelplayeross")

    private fun sources(): List<File> = main.walkTopDown().filter { it.extension == "kt" }.toList()
    private fun relative(file: File) = file.relativeTo(main).invariantSeparatorsPath

    /** The file's code, comments left out: what these tests are about is what is called, not what is said. */
    private fun code(file: File): String {
        // string literals first: a "audio/*" is not the start of a block comment (B42-fix1: it swallowed a sheet call that came after it in a file)
        val withoutStrings = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"").replace(file.readText(), "\"\"")
        val withoutBlocks = Regex("""/\*[\s\S]*?\*/""").replace(withoutStrings, "")
        return withoutBlocks.lines().joinToString("\n") { it.substringBefore("//") }
    }

    private fun callsOf(name: String): Map<String, Int> =
        sources().associate { relative(it) to Regex("""(^|[^A-Za-z0-9_.])$name\(""", RegexOption.MULTILINE).findAll(code(it)).count() }
            .filterValues { it > 0 }

    // ── bottom sheets ───────────────────────────────────────────────────────────────────────────────

    /** Every bottom sheet of the app, by file, with how many it opens. The queue is not a bottom sheet window: it is in [queue]. */
    private val bottomSheets = mapOf(
        "presentation/components/AlbumMultiSelectionOptionSheet.kt" to 1,
        "presentation/components/CustomPresetsSheet.kt" to 1,
        "presentation/components/LibrarySortBottomSheet.kt" to 1,
        "presentation/components/MultiSelectionBottomSheet.kt" to 1,
        "presentation/components/player/PlayerArtistPickerBottomSheet.kt" to 1,
        "presentation/components/PlaylistBottomSheet.kt" to 1,
        "presentation/components/PlaylistMultiSelectionBottomSheet.kt" to 1,
        "presentation/components/ReorderTabsSheet.kt" to 1,
        "presentation/components/SongInfoBottomSheet.kt" to 1,
        "presentation/components/SongPickerBottomSheet.kt" to 1,
        "presentation/components/subcomps/LyricsOptionsSheet.kt" to 1,
        "presentation/components/TimerOptionsBottomSheet.kt" to 1,
        "presentation/screens/AudioBookmarksScreen.kt" to 1,
        "presentation/screens/DacEqualizerScreen.kt" to 2,
        "presentation/screens/LibraryScreen.kt" to 1,
        "presentation/screens/PlaylistDetailScreen.kt" to 1,
        "presentation/screens/SettingsComponents.kt" to 1,
        "presentation/settings/MaintenanceTools.kt" to 1,
    )

    @Test
    fun `no sheet opens Material's own bottom sheet, the app has one place that does`() {
        val direct = callsOf("ModalBottomSheet").filterKeys { it != "presentation/components/GlassModalBottomSheet.kt" }
        assertThat(direct).isEmpty()
        assertThat(callsOf("ModalBottomSheet")).containsKey("presentation/components/GlassModalBottomSheet.kt")
    }

    @Test
    fun `the bottom sheets are exactly these nineteen, in these files`() {
        val actual = callsOf("GlassModalBottomSheet").filterKeys { it != "presentation/components/GlassModalBottomSheet.kt" }
        assertThat(actual).isEqualTo(bottomSheets)
        assertThat(actual.values.sum()).isEqualTo(19)
    }

    @Test
    fun `the one bottom sheet is the platform's with the parameters the app's sheets use, and its glass is the shared one`() {
        val wrapper = code(main.resolve("presentation/components/GlassModalBottomSheet.kt"))
        // one call to Material's sheet serves both modes, so a cover that goes away does not rebuild the sheet's window
        assertThat(Regex("""\bModalBottomSheet\(""").findAll(wrapper).count()).isEqualTo(1)
        for (parameter in listOf("onDismissRequest", "modifier", "sheetState", "shape", "containerColor", "contentColor", "tonalElevation", "dragHandle", "contentWindowInsets")) {
            assertThat(wrapper).contains(parameter)
        }
        assertThat(wrapper).contains("LocalCoverGlass.current")
        assertThat(wrapper).contains("StillGlassSheetBody(")
        assertThat(wrapper).contains("rememberStillGlassClock(")
        assertThat(wrapper).contains("glassSheetContainer(")
        // it draws no field of its own and builds none
        for (forbidden in listOf("CoverGlassFields", "fieldFor", "GlassFieldMath", "GlassCrossfade(")) assertThat(wrapper).doesNotContain(forbidden)
    }

    // ── the queue ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the queue carries its own copy of the glass, and its surface is the glass when there is one`() {
        val sheet = code(main.resolve("presentation/components/QueueBottomSheet.kt"))
        assertThat(sheet).contains("QueueGlass(coverGlass")
        assertThat(sheet).contains("glassSheetContainer(Dap.surface, coverGlass)")
        // its bottom bar is a panel on the glass (the surface, as it was, without it)
        assertThat(sheet).contains(".background(Dap.panel)")
        val glass = code(main.resolve("presentation/components/QueueGlass.kt"))
        assertThat(glass).contains("StillGlassBackdrop(")
        assertThat(glass).contains("frozenCopy()")
    }

    // ── windows of their own that are a whole screen ────────────────────────────────────────────────

    /** Full-screen windows that carry the glass: their file puts a copy of it behind the page. */
    private val carryingTheGlass = mapOf(
        "presentation/components/MusicInfoDownloadsScreen.kt" to 1,
        "presentation/components/FileExplorerBottomSheet.kt" to 1,
        "presentation/components/UnifiedPlayerOverlaysLayer.kt" to 1,
        "presentation/components/player/TrackDetailsHost.kt" to 1,
        "presentation/components/QueueBottomSheet.kt" to 1,
        "presentation/screens/QuickFillScreen.kt" to 1,
    )

    /**
     * Full-screen windows left out, and why: each is an editor or a settings page with an opaque `Scaffold` of its own and several fills
     * beside it (the field, the bar, the cards), opaque and legible in "Cover glass" as they were; giving them the glass is redrawing the
     * page, not wiring it, and half of that is worse than none (B42 rule 10).
     */
    private val leftOut = mapOf(
        "presentation/components/EditSongSheet.kt" to 2,               // the tag editor, and the cover cropper inside it (a tool over a picture)
        "presentation/components/EditMultipleSongsSheet.kt" to 1,      // the batch tag editor
        "presentation/screens/CreatePlaylistScreen.kt" to 2,           // create and edit a playlist
        "presentation/components/BackupModuleSelectionDialog.kt" to 1, // choose what a restore brings back
        "presentation/settings/BackupDialogs.kt" to 2,                 // the backup's export and import pages
    )

    /** Small dialogs: a card over a dimmed app, opaque and tinted by the cover, as it was. */
    private val small = mapOf(
        "presentation/components/PlaylistCreationDialogs.kt" to 2,
        "presentation/settings/BackupTransferProgress.kt" to 1,
        "presentation/components/subcomps/FetchLyricsDialog.kt" to 1,
    )

    @Test
    fun `every window of its own that is a whole screen is accounted for, and a new one fails until it is listed`() {
        val actual = callsOf("Dialog")
        val expected = (carryingTheGlass.keys + leftOut.keys + small.keys).associateWith { file ->
            carryingTheGlass[file] ?: leftOut[file] ?: small[file]!!
        }
        assertThat(actual).isEqualTo(expected)
        // no file is in two lists
        assertThat(carryingTheGlass.keys.intersect(leftOut.keys)).isEmpty()
        assertThat(carryingTheGlass.keys.intersect(small.keys)).isEmpty()
        assertThat(leftOut.keys.intersect(small.keys)).isEmpty()
    }

    @Test
    fun `the side drawer is the one drawer of the app, left out and opaque, and a second one fails until it is listed`() {
        // a drawer from the side is not a bottom sheet and is not a window of its own: it keeps its opaque surface, tinted by the cover
        val only = mapOf("presentation/components/AppSidebarDrawer.kt" to 1)
        assertThat(callsOf("ModalNavigationDrawer")).isEqualTo(only)
        assertThat(callsOf("ModalDrawerSheet")).isEqualTo(only)
        assertThat(code(main.resolve("presentation/components/AppSidebarDrawer.kt"))).doesNotContain("GlassWindowBackdrop")
    }

    @Test
    fun `a window that carries the glass puts its copy behind the page, and the ones left out or small do not pretend to`() {
        for (file in carryingTheGlass.keys) {
            val text = code(main.resolve(file))
            assertThat(text).contains("GlassWindowBackdrop(")
        }
        for (file in leftOut.keys + small.keys) {
            val text = code(main.resolve(file))
            assertThat(text).doesNotContain("GlassWindowBackdrop(")
            assertThat(text).doesNotContain("StillGlass")
        }
    }

    @Test
    fun `the hosts of the player's secondary views and of quick fill give the page their glass, which is what keeps it from being see-through`() {
        // DapBackScaffold and DapScreen fill with the page (nothing over the glass), so a page hosted in a window of its own needs a copy behind it
        val secondary = code(main.resolve("presentation/components/UnifiedPlayerOverlaysLayer.kt"))
        assertThat(secondary).contains("GlassWindowBackdrop(LocalCoverGlass.current)")
        assertThat(secondary).contains("content()")
        val details = code(main.resolve("presentation/components/player/TrackDetailsHost.kt"))
        assertThat(details).contains("GlassWindowBackdrop(LocalCoverGlass.current)")
        val quickFill = code(main.resolve("presentation/screens/QuickFillScreen.kt"))
        assertThat(quickFill).contains("GlassWindowBackdrop(LocalCoverGlass.current)")
        val saveQueue = code(main.resolve("presentation/components/QueueBottomSheet.kt"))
        assertThat(saveQueue).contains("GlassWindowBackdrop(coverGlass)")
        val explorer = code(main.resolve("presentation/components/FileExplorerBottomSheet.kt"))
        assertThat(explorer).contains("GlassWindowBackdrop(coverGlass)")
    }

    @Test
    fun `a surface made transparent over the glass keeps the content colour it gave its text`() {
        // Surface and Scaffold take their text colour from the container colour; Transparent gives them none, so each says it
        val credits = code(main.resolve("presentation/components/player/CreditsSheet.kt"))
        assertThat(credits).contains("contentColor = MaterialTheme.colorScheme.onSurface")
        val explorer = code(main.resolve("presentation/components/FileExplorerBottomSheet.kt"))
        assertThat(Regex("""contentColor = contentColorFor\(MaterialTheme\.colorScheme\.surfaceContainerLow\)""").findAll(explorer).count()).isEqualTo(2)
        val queue = code(main.resolve("presentation/components/QueueBottomSheet.kt"))
        assertThat(queue).contains("contentColor = contentColorFor(Dap.surface)")
        assertThat(queue).contains("contentColor = contentColorFor(MaterialTheme.colorScheme.surface)")
    }

    @Test
    fun `the design system's own sheet surface gives way to the glass, so a sheet built on it is not an opaque slab on its own glass`() {
        val chrome = code(main.resolve("ui/ds/DapChrome.kt"))
        assertThat(chrome).contains(".background(glassSheetContainer(Dap.surface, LocalCoverGlass.current))")
    }

    // ── the shared piece draws one thing in one way ─────────────────────────────────────────────────

    @Test
    fun `the root, the lock screen and every sheet's copy draw the field through the same stack`() {
        val backdrop = code(main.resolve("ui/glass/CoverGlassBackdrop.kt"))
        val still = code(main.resolve("ui/glass/StillGlass.kt"))
        assertThat(Regex("""GlassFieldStack\(""").findAll(backdrop).count()).isAtLeast(2)   // its definition and the root's use
        assertThat(still).contains("GlassFieldStack(scene.crossfade, clock, onFieldDrawn)")
        // the stack is the only place that reads the cross-fade's fields: a sheet cannot draw a different transition
        assertThat(still).doesNotContain("crossfade.current")
        assertThat(still).doesNotContain("crossfade.previous")
        assertThat(still).doesNotContain("crossfade.blend")
    }
}
