package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contracts the Lyrics chrome restructure has to keep.
 *
 * These are source assertions rather than UI tests, for the same reason the Settings ownership
 * tests are: the claims are about *which* callback a control is wired to and *whether* a second
 * authority exists, and a Compose test would need the whole player graph to ask that.
 *
 * A path that cannot be found fails loudly rather than passing vacuously.
 */
class LyricsChromeContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private val sheet by lazy { source("presentation/components/LyricsSheet.kt") }
    /** B22: the options sheet (`LyricsMenu.dc.html`) replaced the old overflow sheet. */
    private val moreSheet by lazy { source("presentation/components/subcomps/LyricsOptionsSheet.kt") }

    // --- A: one keep-screen-on lifecycle owner ---------------------------------------------------

    @Test
    fun `keep screen on has exactly one lifecycle observer path`() {
        // Guard against a vacuous pass: the mechanism must still exist.
        // B11-2 added `isPlaying` to the keys: the one owner now also releases the screen on pause.
        assertThat(sheet).contains("DisposableEffect(keepScreenOn, isPlaying, lifecycleOwner)")

        // The block was written twice, identically. Both copies keyed on the same pair, so they
        // were created and disposed together and both registered a LifecycleObserver on the same
        // owner: two observers per ON_STOP, two DataStore writes, two competing view owners.
        val effects = Regex("""DisposableEffect\(keepScreenOn, isPlaying, lifecycleOwner\)""").findAll(sheet).count()
        assertThat(effects).isEqualTo(1)

        val observers = Regex("""lifecycleOwner\.lifecycle\.addObserver\(""").findAll(sheet).count()
        assertThat(observers).isEqualTo(1)
    }

    @Test
    fun `the screen-off receiver is preserved as its own mechanism`() {
        // Not part of the duplication: a lifecycle stop and a screen-off broadcast are different
        // events, and the receiver covers screen-off while the activity stays resumed.
        assertThat(sheet).contains("Intent.ACTION_SCREEN_OFF")
        assertThat(sheet).contains("registerReceiver")
    }

    @Test
    fun `keep screen on stays session-scoped and is not written from Settings`() {
        // It resets itself on stop and on screen-off, so presenting it as a persistent preference
        // in Settings would be a control that silently reverts.
        val settings = source("presentation/screens/SettingsCategoryScreen.kt")
        assertThat(settings).doesNotContain("keep_screen_on_lyrics")
        assertThat(sheet).contains("keep_screen_on_lyrics")
    }

    // --- B: the global destructive reset is not reachable here -----------------------------------

    @Test
    fun `the global imported-lyrics reset is not reachable from the lyrics surface`() {
        assertThat(sheet).doesNotContain("resetAllLyrics")
        assertThat(moreSheet).doesNotContain("resetAllLyrics")

        // It lives on the System page of Settings (B32-Q6: it was Maintenance's behind a PIN, and there is no PIN now), asks before it
        // runs, and is reached only through that page's wiring.
        val system = source("presentation/settings/SystemSettings.kt")
        assertThat(system).contains("setcat_reset_imported_lyrics_title")
        assertThat(source("presentation/settings/SettingsActionsWiring.kt")).contains("vm.resetAllLyrics()")
        val settings = source("presentation/screens/SettingsCategoryScreen.kt")
        assertThat(settings).doesNotContain("resetAllLyrics")
        assertThat(source("presentation/screens/MaintenanceScreen.kt")).doesNotContain("resetAllLyrics")
    }

    // --- C: the per-song action stays per-song ---------------------------------------------------

    @Test
    fun `the imported-lyrics reset on this surface is the per-song one`() {
        // The row's callback must reach the current-song reset, which takes the song id — not the
        // repository-wide delete.
        assertThat(moreSheet).contains("onResetLyrics")
        assertThat(sheet).contains("onResetLyrics = resetLyricsForCurrentSong")

        // And the wording says so: the confirmation is about this song, not the library.
        assertThat(moreSheet).contains("lyrics_more_dialog_reset_title")
        assertThat(moreSheet).contains("lyrics_more_dialog_reset_message")
        val en = sourceFile("strings_components.xml")
        assertThat(en).contains("reset the lyrics for this song")
    }

    // --- D: display preferences share the declared key owner --------------------------------------

    @Test
    fun `alignment, translation and romanization are written through the repository`() {
        // The lyrics sheet used to write these three keys itself with raw `context.dataStore.edit`
        // calls, bypassing the repository that declares and validates them — a second writer of the
        // same key with different rules. They are callbacks now.
        assertThat(sheet).doesNotContain("""stringPreferencesKey("lyrics_alignment")] =""")
        assertThat(sheet).doesNotContain("""booleanPreferencesKey("show_lyrics_translation")] =""")
        assertThat(sheet).doesNotContain("""booleanPreferencesKey("show_lyrics_romanization")] =""")

        assertThat(sheet).contains("onLyricsAlignmentChange: (String) -> Unit")
        assertThat(sheet).contains("onShowLyricsTranslationChange: (Boolean) -> Unit")
        assertThat(sheet).contains("onShowLyricsRomanizationChange: (Boolean) -> Unit")

        // And they end at the repository through the ViewModel.
        val player = source("presentation/viewmodel/PlayerViewModel.kt")
        assertThat(player).contains("userPreferencesRepository.setLyricsAlignment(")
        assertThat(player).contains("userPreferencesRepository.setShowLyricsTranslation(")
        assertThat(player).contains("userPreferencesRepository.setShowLyricsRomanization(")
    }

    @Test
    fun `the lyrics surface still reads the same keys the repository declares`() {
        // Reads are fine from the sheet — that is what makes a change made in Settings appear here.
        // What matters is that the key strings are the ones the repository owns.
        val repo = source("data/preferences/UserPreferencesRepository.kt")
        listOf("lyrics_alignment", "show_lyrics_translation", "show_lyrics_romanization")
            .forEach { key ->
                assertThat(repo).contains("\"$key\"")
                assertThat(sheet).contains("\"$key\"")
            }
    }

    // --- E: synced/static stays session state -----------------------------------------------------

    @Test
    fun `synced versus static is session state and was not turned into a preference`() {
        // It is derived from the lyrics each time they change and persisted nowhere.
        assertThat(sheet).contains("var showSyncedLyrics by remember(lyrics)")

        val repo = source("data/preferences/UserPreferencesRepository.kt")
        assertThat(repo).doesNotContain("show_synced_lyrics")
        assertThat(repo).doesNotContain("lyrics_synced_view")

        // B22: the design's options sheet has no synced/static switch, so there is nothing to change it from.
        assertThat(moreSheet).doesNotContain("onShowSyncedLyricsChange")
    }

    // --- F/G: no second Settings authority, no Settings button ------------------------------------

    @Test
    fun `there is no dedicated Settings button in the primary lyrics chrome`() {
        // The brief asked for one to be removed. There is no such control in this tree — the only
        // occurrences of the word on the surface are prose. This pins that it does not come back.
        listOf(sheet, moreSheet).forEach { text ->
            assertThat(text).doesNotContain("""stringResource(R.string.settings)""")
            assertThat(text).doesNotContain("""label = "Settings"""")
            assertThat(text).doesNotContain("""text = "Settings"""")
        }
    }

    @Test
    fun `the lyrics surface does not resolve settings state of its own`() {
        // A second authority would show up as the sheet (or its overflow) holding settings state.
        listOf(sheet, moreSheet).forEach { text ->
            assertThat(text).doesNotContain("SettingsViewModel")
            assertThat(text).doesNotContain("hiltViewModel")
        }
    }

    // --- H: the primary chrome still drives real playback ------------------------------------------

    @Test
    fun `the compact strip still invokes the existing playback callbacks`() {
        // Guard against a vacuous pass: the strip must exist.
        assertThat(sheet).contains("LyricsPlaybackSeekBar(")

        assertThat(sheet).contains("onPlayPause()")
        assertThat(sheet).contains("onSeekTo = onSeekTo")
        assertThat(sheet).contains("onSeekPreviewChange = { previewSeekPositionMs = it }")

        // Back is the system Back (predictive) and the player header's Lyrics button; the lyrics have no header of their own.
        assertThat(sheet).contains("onBack = onBackClick")
        // Prev/next stay on the swipe gesture, which is unchanged.
        assertThat(sheet).contains("if (dragOffset > 0) onPrev() else onNext()")
    }

    @Test
    fun `the lyrics viewport is not an overlay any more`() {
        // The floating pill sat above the list with a 130 dp gradient scrim to fade the text it
        // covered. Both are gone; the header is a sibling of the list.
        assertThat(sheet).doesNotContain("headerAnimation")
        assertThat(sheet).doesNotContain("LyricsTrackInfo")
        // B22: and no header of its own at all: the player's header stays above the lyrics.
        assertThat(sheet).doesNotContain("LyricsHeader(")

        // No scrim remains to hide lyric lines.
        assertThat(sheet).doesNotContain("130.dp")
    }

    /** A file directly under a `values/` directory. */
    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/res/values", name),
            File("src/main/res/values", name),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate values/$name from ${File(".").absolutePath}")
        return file.readText()
    }
}
