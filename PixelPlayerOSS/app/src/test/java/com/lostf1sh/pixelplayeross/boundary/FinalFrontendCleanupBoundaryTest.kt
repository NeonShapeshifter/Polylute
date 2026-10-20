package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The rules this cleanup pass established, pinned so the next pass cannot quietly undo them.
 */
class FinalFrontendCleanupBoundaryTest {

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    /**
     * Source with its `//` comments removed.
     *
     * The comments in these files quote the very literals the assertions are about — a fix for a
     * hardcoded string has to be able to name the string. Asserting on the code, not the prose, is
     * what makes these checks mean what they say.
     */
    private fun code(relative: String) =
        source(relative).lines().joinToString("\n") { it.substringBefore("//") }

    private fun enStrings() = root.resolve("src/main/res/values/strings.xml").readText()
    private fun esStrings() = root.resolve("src/main/res/values-es/strings.xml").readText()

    private fun enScreens() = root.resolve("src/main/res/values/strings_screens.xml").readText()
    private fun esScreens() = root.resolve("src/main/res/values-es/strings_screens.xml").readText()

    // ---- B: user-visible copy is resource-backed, in both locales ------------------------------

    @Test
    fun `the player status line is resource-backed in both languages`() {
        listOf(
            "player_preparing_playback",
            "player_loading_audio",
        ).forEach { name ->
            assertThat(enStrings()).contains("name=\"$name\"")
            assertThat(esStrings()).contains("name=\"$name\"")
        }

        // They were English string literals, so a Spanish build showed a Spanish player with an
        // English status line in it.
        val shared = code("presentation/components/UnifiedPlayerSheetShared.kt")
        assertThat(shared).doesNotContain("\"Connecting to device")
        assertThat(shared).doesNotContain("\"Preparing playback")
        assertThat(shared).doesNotContain("\"Loading audio")
    }

    @Test
    fun `Home's mix labels are resource-backed in both languages`() {
        // "Today's Mix for you" is the historical example: a Spanish Home rendered a Spanish
        // "TU MIX" header above an English headline, because the header had a resource and the
        // headline did not. It and the queue name were string literals in HomeScreen.
        listOf("home_your_mix_heading", "home_your_mix_queue_name", "home_daily_mix_title").forEach { name ->
            assertThat(enScreens()).contains("name=\"$name\"")
            assertThat(esScreens()).contains("name=\"$name\"")
        }

        val home = code("presentation/screens/HomeScreen.kt")
        assertThat(home).doesNotContain("\"Today's Mix for you\"")
        assertThat(home).doesNotContain("\"Your Mix\"")
    }

    @Test
    fun `a hardcoded label does not come back to the song info sheet`() {
        assertThat(source("presentation/viewmodel/SongInfoBottomSheetViewModel.kt"))
            .doesNotContain("label = \"Path\"")
        assertThat(
            root.resolve("src/main/res/values/strings_components.xml").readText()
        ).contains("name=\"song_info_path_label\"")
        assertThat(
            root.resolve("src/main/res/values-es/strings_components.xml").readText()
        ).contains("name=\"song_info_path_label\"")
    }

    // ---- F: Genre Detail does not go back to the legacy header --------------------------------

    @Test
    fun `genre detail does not reintroduce the artwork wash or the floating disc`() {
        val genre = source("presentation/screens/GenreDetailScreen.kt")

        // The 80%-alpha wash of the genre's colour over the top of the screen, which Album and
        // Artist detail deliberately do not do.
        assertThat(genre).doesNotContain("verticalGradient")
        assertThat(genre).contains("DapOverflowButton(")
        assertThat(genre).doesNotContain("MediumFloatingActionButton(")
    }

    // ---- H: Back remains mode-aware outside the page viewport -------------------------------

    @Test
    fun `converted surfaces use the mode-aware back affordance`() {
        listOf(
            "presentation/screens/AlbumDetailScreen.kt",
            "presentation/components/EditMultipleSongsSheet.kt",
        ).forEach { file ->
            assertThat(code(file)).doesNotContain("DapTopBar(")
        }
        // The fixed navigation strip preserves the preference while keeping scrolling rows clear.
        assertThat(code("presentation/screens/AlbumDetailScreen.kt")).contains("DapBackScaffold(")
        assertThat(source("presentation/components/DapBackScaffold.kt"))
            .contains("showsVisibleBackAffordance")
    }

    // ---- I: a retired appearance value cannot deform the UI ------------------------------------

    @Test
    fun `retired appearance preferences read as constants`() {
        val prefs = source("data/preferences/UserPreferencesRepository.kt")

        listOf(
            "navBarCornerRadiusFlow",
            "navBarStyleFlow",
            "navBarCompactModeFlow",
            "carouselStyleFlow",
            "useSmoothCornersFlow",
        ).forEach { flow ->
            val declaration = prefs.lines().first { it.contains("val $flow") }
            assertThat(declaration).contains("flowOf(")
            assertThat(declaration).doesNotContain("dataStore")
        }

        // The keys, setters and backup participation stay, so an old backup still round-trips.
        listOf(
            "NAV_BAR_STYLE",
            "NAV_BAR_COMPACT_MODE",
            "NAV_BAR_CORNER_RADIUS",
            "CAROUSEL_STYLE",
            "USE_SMOOTH_CORNERS",
        ).forEach { key ->
            assertThat(prefs).contains("val $key =")
        }
        assertThat(prefs).contains("suspend fun setNavBarStyle")
        assertThat(prefs).contains("suspend fun setCarouselStyle")
    }

    @Test
    fun `the collage preference is not treated as orientation or as retired`() {
        // It configures Home artwork, not the screen, and the Home collage it fed was replaced —
        // so it is inert rather than frozen. It is deliberately still declared.
        val prefs = source("data/preferences/UserPreferencesRepository.kt")
        assertThat(prefs).contains("collageAutoRotateFlow")
        assertThat(prefs).contains("collagePatternFlow")
    }

    // ---- K: the owner-selected display name; the debug build is another app and says so (B35-fix1 R3) ------

    @Test
    fun `the release is named Polylute and the debug build Polylute Debug`() {
        val debug = root.resolve("src/debug/res/values/strings.xml").readText()

        // `translatable="false"` may sit between the name and the value (B14-4): the name is the same in every
        // language, which is what the attribute says. The assertion is about the value.
        assertThat(Regex("""name="app_name"[^>]*>Polylute Debug<""").containsMatchIn(debug)).isTrue()
        assertThat(Regex("""name="app_name"[^>]*>Polylute<""").containsMatchIn(enStrings())).isTrue()
        assertThat(enStrings()).doesNotContain("[D]")
    }

    // ---- A: no placeholder reaches the shared song row -----------------------------------------

    @Test
    fun `the shared song row draws labels rather than raw fields`() {
        val row = source("presentation/components/subcomps/EnhancedSongListItem.kt")

        assertThat(row).contains("songTitleLabel(song)")
        assertThat(row).contains("songArtistLabel(song)")
        // The artist line is drawn only when there is one — no `<unknown>` and no empty line.
        assertThat(row).contains("songArtistLabel(song)?.let {")
        val labels = source("presentation/components/MetadataLabels.kt")
        assertThat(labels).contains("stringResource(R.string.unknown_song_title)")
        assertThat(labels).contains("stringResource(R.string.unknown_artist)")
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
