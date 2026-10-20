package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The Back affordance and the Back mode.
 *
 * A bar that draws its own Back chevron draws it in every mode, including Gestures, where the mode
 * says a control that exists only to perform Back must not exist. These assertions are about the
 * surfaces having stopped doing that, and about the ones that legitimately keep a bar having kept
 * the controls that justify it.
 */
class BackAffordanceBoundaryTest {

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    /** Source with its `//` comments removed, so assertions are about code and not about prose. */
    private fun code(relative: String) =
        source(relative).lines().joinToString("\n") { it.substringBefore("//") }

    // ---- The rule the conversions exist to satisfy ---------------------------------------------

    @Test
    fun `the affordance is shown in Buttons and Both and hidden in Gestures`() {
        assertThat(BackNavigationMode.BUTTONS.showsVisibleBackAffordance).isTrue()
        assertThat(BackNavigationMode.BOTH.showsVisibleBackAffordance).isTrue()
        assertThat(BackNavigationMode.GESTURES.showsVisibleBackAffordance).isFalse()
    }

    @Test
    fun `the overlay is what consults the mode`() {
        val overlay = code("presentation/components/DapOverlayBack.kt")
        assertThat(overlay).contains("showsVisibleBackAffordance")
        // Drawn only when the mode allows a visible control, and calling the shell's one Back
        // authority when it is.
        assertThat(overlay).contains("if (!LocalBackNavigationMode.current.showsVisibleBackAffordance) return")
    }

    // ---- Surfaces converted in this pass --------------------------------------------------------

    @Test
    fun `the converted back-only surfaces use fixed navigation and no longer draw a bar glyph`() {
        val converted = listOf(
            "presentation/screens/AboutScreen.kt",
            "presentation/screens/LicensesScreen.kt",
            "presentation/screens/DuplicateSongsScreen.kt",
            "presentation/screens/QuickFillScreen.kt",
            "presentation/components/BackupModuleSelectionDialog.kt",
        )
        converted.forEach { file ->
            val c = code(file)
            assertThat(c).contains(if (file.endsWith("BackupModuleSelectionDialog.kt")) "DapOverlayBack(" else "DapBackScaffold(")
            assertThat(c).doesNotContain("DapTopBar(")
        }
    }

    @Test
    fun `fixed navigation pages do not draw a second floating Back`() {
        listOf(
            "presentation/screens/AboutScreen.kt",
            "presentation/screens/LicensesScreen.kt",
            "presentation/screens/DuplicateSongsScreen.kt",
            "presentation/screens/QuickFillScreen.kt",
            "presentation/screens/HeadphoneProfileScreens.kt",
        ).forEach { file ->
            assertThat(code(file)).contains("DapBackScaffold(")
            assertThat(code(file)).doesNotContain("DapOverlayBack(")
        }
    }

    // ---- Surfaces that keep a bar because they carry real actions -------------------------------

    @Test
    fun `Artist and Playlist detail keep their overflow while Back obeys the mode`() {
        val artist = code("presentation/screens/ArtistDetailScreen.kt")
        // Two branches, both with the image action preserved.
        assertThat(artist).contains("DapBackScaffold(")
        assertThat(artist).contains("onBack = null")
        assertThat(artist).contains("DapOverflowButton(")

        val playlist = code("presentation/screens/PlaylistDetailScreen.kt")
        assertThat(playlist).contains("DapBackScaffold(")
        // The bar keeps both real actions — the sort action and the overflow.
        assertThat(playlist).contains("onBack = null")
        assertThat(playlist).contains("DapTextAction(")
        assertThat(playlist).contains("DapOverflowButton(")
    }

    @Test
    fun `Stats keeps its range and layer controls under a design system back scaffold`() {
        // B28: the screen is rebuilt on the same scaffold as the other pages, with the title, the Summary/Top switch and the range
        // picker in the page body, not a bar held open for one chevron.
        val stats = code("presentation/screens/StatsScreen.kt")
        assertThat(stats).doesNotContain("CollapsibleCommonTopBar(")
        assertThat(stats).contains("DapBackScaffold(")
        assertThat(stats).contains("DapScreenTitle(")
        assertThat(stats).contains("StatsTimeRange.entries")
        assertThat(stats).contains("StatsLayer.entries")
    }

    @Test
    fun `internal diagnostics are not churned`() {
        // Reached only from the maintenance panel; deliberately left, per the brief.
        assertThat(code("presentation/screens/MaintenanceScreen.kt")).contains("DapTopBar(")
        assertThat(code("presentation/screens/DeviceCapabilitiesScreen.kt"))
            .contains("CollapsibleCommonTopBar(")
    }

    @Test
    fun `the collapsible bar can decline to draw its own back`() {
        // Nullable rather than required, so a surface can keep the hero and move only the
        // affordance. The hero surfaces rely on it.
        val bar = code("presentation/components/CollapsibleCommonTopBar.kt")
        assertThat(bar).contains("onBackClick: (() -> Unit)? = null")
        assertThat(bar).contains("onBackClick?.let { onBack ->")

        listOf(
            "presentation/screens/AudioBookmarksScreen.kt",
            "presentation/screens/ArtistSettingsScreen.kt",
        ).forEach { file ->
            val c = code(file)
            assertThat(c).contains("CollapsibleCommonTopBar(")
            assertThat(c).contains("onBackClick = null")
            assertThat(c).contains(if (file.endsWith("ArtistSettingsScreen.kt")) "DapBackScaffold(" else "DapOverlayBack(")
        }
    }

    // ---- Regression: the blocker the freeze review found ----------------------------------------

    @Test
    fun `Track Details obeys the Back mode`() {
        // The freeze review found this surface still drawing a chevron in Gestures mode, which is
        // the one thing the mode says must not happen. Its bar was `DapTopBar`, whose back has no
        // notion of the mode, and that bar was also the only thing providing any top spacing — so
        // the glyph sat inside the status-bar band as well.
        val details = code("presentation/components/player/TrackDetailsSheet.kt")

        assertThat(details).doesNotContain("DapTopBar(")
        assertThat(details).contains("DapBackScaffold(")
        // The inset the bar never supplied, now taken by the content.
        assertThat(details).contains("statusBarsPadding()")
        assertThat(details).doesNotContain("DapOverlayBack(")
    }

    @Test
    fun `no surface in the app draws a bar back outside the mode`() {
        // The rule this whole series has been enforcing: a visible Back affordance is either the
        // overlay, or it is on a surface whose bar carries actions the brief keeps. Anything else
        // is a chevron that shows itself in Gestures mode.
        val allowed = setOf(
            // A bar kept for the actions it carries; its own back is null, so the chevron the
            // reader sees is the mode-aware overlay.
            "presentation/screens/ArtistDetailScreen.kt",
            "presentation/screens/PlaylistDetailScreen.kt",
            // Internal diagnostics, deliberately not churned.
            "presentation/screens/MaintenanceScreen.kt",
            // KNOWN RESIDUAL, recorded rather than fixed: these five bars each carry a real action
            // and still draw their own back, so they show a chevron in Gestures mode. The freeze
            // review scoped them out of mandatory conversion — the brief converts bars that hold
            // only a title and/or Back — and each needs the same substitution Artist and Playlist
            // already had: `onBack = null` plus a `DapOverlayBack` in the surrounding BoxScope.
            "presentation/components/QueueBottomSheet.kt",
            "presentation/components/EditSongSheet.kt",
            "presentation/components/ReorderPresetsSheet.kt",
            "presentation/components/FileExplorerBottomSheet.kt",
            "presentation/screens/EditTransitionScreen.kt",
            // The design-system definition and its preview, not a surface.
            "ui/ds/DapChrome.kt",
            "ui/ds/DapSurfacePreviews.kt",
        )
        val base = root.resolve("src/main/java/com/lostf1sh/pixelplayeross")
        val offenders = base.walkTopDown()
            .filter { it.extension == "kt" }
            // Comments stripped: a fix for a bar has to be able to name the bar it removed.
            .filter { file ->
                file.readText().lines().joinToString("\n") { it.substringBefore("//") }
                    .contains("DapTopBar(")
            }
            .map { it.relativeTo(base).path }
            .filter { it !in allowed }
            .toList()

        assertThat(offenders).isEmpty()
    }

    // ---- The remaining artist surfaces ----------------------------------------------------------

    @Test
    fun `Song Info, Queue and Audio Bookmarks do not draw raw artist metadata`() {
        listOf(
            "presentation/components/SongInfoBottomSheet.kt",
            "presentation/components/QueueBottomSheet.kt",
            "presentation/screens/AudioBookmarksScreen.kt",
        ).forEach { file ->
            val c = code(file)
            assertThat(c).contains("songArtistLabel(")
            // The raw field is no longer rendered directly anywhere on these surfaces.
            assertThat(c).doesNotContain("text = song.displayArtist")
            assertThat(c).doesNotContain("song.displayArtist.ifBlank")
        }
    }

    @Test
    fun `Audio Bookmarks has no hardcoded English placeholder`() {
        val c = code("presentation/screens/AudioBookmarksScreen.kt")
        assertThat(c).doesNotContain("\"Unknown artist\"")
        assertThat(c).doesNotContain("\"Unknown audio\"")
        // Its header title went through the same policy, with the localized screen title as the
        // fallback rather than an English literal.
        assertThat(c).contains("withoutPlaceholderMetadata()")
        assertThat(c).contains("R.string.audio_bookmarks_title")
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
