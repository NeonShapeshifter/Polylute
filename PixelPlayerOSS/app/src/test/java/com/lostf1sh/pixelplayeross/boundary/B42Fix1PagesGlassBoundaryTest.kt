package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B42-fix1, item 8: "the details are not opaque at all, you can't read it" (B41, "Cover glass"). The audit of everything that can draw over
 * the full player, pinned:
 *
 *  1. **The player's pages are pages, not windows.** Menu, Lyrics and Details are slots of the player's pager; nothing in the files that
 *     build the player opens a window, so there is nothing "over" the player to be see-through: a page is drawn on the player's own glass.
 *  2. **A window that has no copy of the glass behind it fills itself with a solid colour**: the windows left out of the glass and the small
 *     dialogs use no role that is see-through over the glass (the page's fill, the panels, the fade edge, Material's background).
 *  3. **No other kind of window** opens over the player unaccounted for: `BasicAlertDialog` is one small card, `Popup` is the Back gesture's
 *     indicator, and no `AlertDialog` overrides its container with a see-through one.
 *  4. The lyrics' dimmed inks over the glass are the glass's own (`B42Fix1PagesGlassTest` measures them at AA on the pixels).
 */
class B42Fix1PagesGlassBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val main: File = root.resolve("src/main/java/com/lostf1sh/pixelplayeross")

    private fun sources(): List<File> = main.walkTopDown().filter { it.extension == "kt" }.toList()
    private fun relative(file: File) = file.relativeTo(main).invariantSeparatorsPath

    private fun code(file: File): String {
        val withoutStrings = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"").replace(file.readText(), "\"\"")
        val withoutBlocks = Regex("""/\*[\s\S]*?\*/""").replace(withoutStrings, "")
        return withoutBlocks.lines().joinToString("\n") { it.substringBefore("//") }
    }

    private fun code(path: String) = code(main.resolve(path))

    private fun callsOf(name: String): Map<String, Int> =
        sources().associate { relative(it) to Regex("""(^|[^A-Za-z0-9_.])$name\(""", RegexOption.MULTILINE).findAll(code(it)).count() }
            .filterValues { it > 0 }

    private val windows = listOf("Dialog", "AlertDialog", "BasicAlertDialog", "ModalBottomSheet", "GlassModalBottomSheet", "Popup", "DropdownMenu")

    // ── 1 ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the files that build the player open no window of their own, so nothing is drawn over its pages`() {
        for (file in listOf(
            "presentation/components/player/FullPlayerScreen.kt", "presentation/components/player/PlayerPages.kt",
            "presentation/components/player/PlayerCoverSurface.kt", "presentation/components/player/PlayerLyricsEntry.kt",
            "presentation/components/player/FullPlayerContent.kt", "presentation/components/player/TrackDetailsSheet.kt",
            "presentation/components/player/PlayerControlsSurface.kt", "presentation/components/player/PlayerTrackInfoSurface.kt",
        )) {
            val text = code(file)
            for (window in windows) assertWithMessage("$file opens $window").that(Regex("""(^|[^A-Za-z0-9_.])$window\(""", RegexOption.MULTILINE).containsMatchIn(text)).isFalse()
        }
    }

    @Test
    fun `the lyrics page opens no sheet and no window but one small alert, and the details page is the body without a window`() {
        val lyrics = code("presentation/components/LyricsSheet.kt")
        for (window in windows - "AlertDialog") assertWithMessage("LyricsSheet opens $window").that(Regex("""(^|[^A-Za-z0-9_.])$window\(""", RegexOption.MULTILINE).containsMatchIn(lyrics)).isFalse()
        assertThat(Regex("""(^|[^A-Za-z0-9_.])AlertDialog\(""", RegexOption.MULTILINE).findAll(lyrics).count()).isEqualTo(1)

        val host = code("presentation/components/player/TrackDetailsHost.kt")
        val page = host.substringAfter("internal fun TrackDetailsPage(").substringBefore("\n}\n")
        assertThat(page).contains("TrackDetailsBody(")
        for (window in windows) assertThat(page).doesNotContain("$window(")
        // the window form (library and search) still carries its copy of the glass
        assertThat(host).contains("GlassWindowBackdrop(LocalCoverGlass.current)")
    }

    // ── 2 ───────────────────────────────────────────────────────────────────────────────────────────

    /** The windows with no copy of the glass behind them: the ones left out of it and the small dialogs (the lists of `GlassSheetBoundaryTest`). */
    private val withoutTheGlass = listOf(
        "presentation/components/EditSongSheet.kt", "presentation/components/EditMultipleSongsSheet.kt",
        "presentation/screens/CreatePlaylistScreen.kt",
        "presentation/components/BackupModuleSelectionDialog.kt", "presentation/settings/BackupDialogs.kt",
        "presentation/components/PlaylistCreationDialogs.kt", "presentation/settings/BackupTransferProgress.kt",
        "presentation/components/subcomps/FetchLyricsDialog.kt", "presentation/components/PlaylistContainer.kt",
    )

    private val seeThroughOverTheGlass = listOf(
        Regex("""\bDap\.(page|panel|panelRaised|fadeEdge)\b"""),
        Regex("""colorScheme\.background\b"""),
        Regex("""(containerColor|color)\s*=\s*(androidx\.compose\.ui\.graphics\.)?Color\.Transparent"""),
    )

    @Test
    fun `a window with no glass behind it uses no role that is see-through over the glass`() {
        for (file in withoutTheGlass) {
            val text = code(file)
            for (role in seeThroughOverTheGlass) assertWithMessage("$file uses ${role.pattern}").that(role.find(text)?.value).isNull()
        }
    }

    // ── 3 ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no kind of window opens over the player that this audit did not see`() {
        assertThat(callsOf("BasicAlertDialog")).isEqualTo(mapOf("presentation/components/PlaylistContainer.kt" to 1))
        assertThat(callsOf("Popup")).isEqualTo(mapOf(
            "presentation/components/ModalInputBridge.kt" to 1,
            "presentation/components/MusicInfoPageFeedback.kt" to 1,
            "presentation/settings/MusicInfoSettingsSection.kt" to 1,
            // B56 (#42): the one overflow menu. It replaces seven Material DropdownMenus, which opened the same kind of window unseen by this audit.
            "ui/ds/DapMenu.kt" to 1,
        ))
        for (path in listOf("presentation/components/MusicInfoPageFeedback.kt", "presentation/settings/MusicInfoSettingsSection.kt")) {
            assertThat(code(path)).contains("PopupProperties(focusable = false)")
        }
        // the overflow menu is the one popup that takes focus: a tap outside and system Back dismiss it, and it is as big as its options
        assertThat(code("ui/ds/DapMenu.kt")).contains("PopupProperties(focusable = true)")
        // the Back indicator is a small arrow, not a page
        val bridge = code("presentation/components/ModalInputBridge.kt")
        assertThat(bridge).contains("PopupProperties(focusable = false)")
    }

    @Test
    fun `no alert dialog gives its container a see-through colour`() {
        val seeThrough = Regex("""containerColor\s*=\s*(Color\.Transparent|Dap\.page|Dap\.panel|Dap\.panelRaised|MaterialTheme\.colorScheme\.background)""")
        for (file in sources()) {
            val text = code(file)
            for (call in Regex("""(^|[^A-Za-z0-9_.])(Basic)?AlertDialog\(""", RegexOption.MULTILINE).findAll(text)) {
                val head = text.substring(call.range.first, minOf(text.length, call.range.first + 1_800))
                assertWithMessage("${relative(file)} AlertDialog at ${call.range.first}").that(seeThrough.containsMatchIn(head)).isFalse()
            }
        }
    }

    // ── 4 ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the details' notes are the glass's tertiary ink over the glass, and the disabled grey is only the fallback`() {
        val details = code("presentation/components/player/TrackDetailsSheet.kt")
        assertThat(details).contains("private fun noteInk(): Color = if (Dap.glass != null) Dap.tertiaryText else Dap.disabledText")
        assertThat(Regex("""color = noteInk\(\)""").findAll(details).count()).isEqualTo(3)
        // the one place that names the disabled ink is the fallback; the "Off" of a state the row shows is the design system's own
        assertThat(Regex("""Dap\.disabledText""").findAll(details).count()).isEqualTo(1)
    }

    @Test
    fun `the lyrics dim their text with the glass's own inks, and the one place that scales the content colour is the fallback`() {
        val lyrics = code("presentation/components/LyricsSheet.kt")
        assertThat(lyrics).contains("private fun dimmedLyricInk(alpha: Float, onGlass: Color): Color =")
        assertThat(lyrics).contains("if (Dap.glass != null) onGlass else LocalContentColor.current.copy(alpha = alpha)")
        assertThat(lyrics).contains("dimmedLyricInk(alpha = 0.45f, onGlass = Dap.tertiaryText)")
        assertThat(lyrics).contains("dimmedLyricInk(alpha = 0.7f, onGlass = Dap.secondaryText)")
        // what remains: the helper's fallback, and the three dots that wait for the first line (not text)
        assertThat(Regex("""LocalContentColor\.current\.copy\(alpha""").findAll(lyrics).count()).isEqualTo(2)
    }
}
