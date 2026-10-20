package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzApplyResult
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzField
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.components.player.formatHz
import com.lostf1sh.pixelplayeross.presentation.components.player.formatKbps
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contracts the Details convergence has to keep.
 *
 * The *rate and route* truth is already pinned by `TrackDetailsTruthTest`; this covers what this
 * pass added or changed — the route-activity invariant, the MusicBrainz result semantics, the
 * shared formatting, and that there is one canonical details state.
 */
class SongDetailsConvergenceTest {

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

    // --- B: attachment or a candidate is not an active direct route -------------------------------

    @Test
    fun `a direct candidate is not an active direct route`() {
        // The distinction the whole OUTPUT section rests on: `desiredBackend` says what the engine
        // wants, `actualBackend` says what happened. A DAC being attached, eligible or negotiating
        // must never read as direct output.
        val candidates = listOf(
            AudioRouteTransition.DIRECT_ELIGIBLE,
            AudioRouteTransition.DIRECT_NEGOTIATING,
            AudioRouteTransition.ANDROID_ACTIVE,
            AudioRouteTransition.DIRECT_FAILED_FALLBACK,
        )

        candidates.forEach { transition ->
            val state = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = transition,
            )
            assertThat(state.isDirectActive).isFalse()
        }
    }

    @Test
    fun `direct is active only when the backend and the transition agree`() {
        val active = AudioRouteState(
            desiredBackend = AudioBackend.USB_DIRECT,
            actualBackend = AudioBackend.USB_DIRECT,
            transition = AudioRouteTransition.DIRECT_ACTIVE,
            confirmedOutputRateHz = 96_000,
        )

        assertThat(active.isDirectActive).isTrue()
        assertThat(active.confirmedOutputRateHz).isEqualTo(96_000)
    }

    @Test
    fun `a fallback reports no confirmed rate`() {
        // The engine nulls the rate on the fallback path; this asserts the shape the details
        // surface depends on, so "Output rate" cannot survive a failed direct claim.
        val fallenBack = AudioRouteState(
            desiredBackend = AudioBackend.USB_DIRECT,
            actualBackend = AudioBackend.ANDROID_AUDIO,
            transition = AudioRouteTransition.DIRECT_FAILED_FALLBACK,
            confirmedOutputRateHz = null,
        )

        assertThat(fallenBack.isDirectActive).isFalse()
        assertThat(fallenBack.confirmedOutputRateHz).isNull()
    }

    // --- H: MusicBrainz says what actually changed ------------------------------------------------

    @Test
    fun `a write that filled fields reports them`() {
        val result = MusicBrainzApplyResult(
            changedFields = setOf(MusicBrainzField.TITLE, MusicBrainzField.YEAR),
            identifiersChanged = true,
        )

        assertThat(result.changedAnything).isTrue()
        assertThat(result.changedFields).containsExactly(MusicBrainzField.TITLE, MusicBrainzField.YEAR)
    }

    @Test
    fun `a write that filled nothing and changed no identifier reports no change`() {
        // The case the old model could not express: the request succeeded, and the stored metadata
        // was already identical, so nothing a user would notice was written.
        val result = MusicBrainzApplyResult(
            changedFields = emptySet(),
            identifiersChanged = false,
        )

        assertThat(result.changedAnything).isFalse()
    }

    @Test
    fun `identifiers alone still count as a change`() {
        // The update writes the identifiers unconditionally, so a match that agrees with every
        // name can still have persisted something new.
        val result = MusicBrainzApplyResult(
            changedFields = emptySet(),
            identifiersChanged = true,
        )

        assertThat(result.changedAnything).isTrue()
    }

    @Test
    fun `the apply result mirrors the update's own fill rules`() {
        // The repository re-implements the DAO's CASE logic to compute the diff. If the SQL's
        // placeholder rules change, this fails rather than silently mislabeling.
        val dao = source("data/database/MusicDao.kt")
        val repo = source("data/musicbrainz/MusicBrainzRepository.kt")

        listOf("unknown title", "unknown artist", "unknown album").forEach { placeholder ->
            assertThat(dao).contains(placeholder)
            assertThat(repo).contains(placeholder)
        }
        assertThat(dao).contains("WHEN year <= 0")
        assertThat(repo).contains("before.year <= 0")
    }

    // --- F: one formatting implementation for a technical reading ---------------------------------

    @Test
    fun `sample rates use one convention`() {
        assertThat(formatHz(48_000)).isEqualTo("48 kHz")
        assertThat(formatHz(96_000)).isEqualTo("96 kHz")
        assertThat(formatHz(44_100)).isEqualTo("44.1 kHz")
        assertThat(formatHz(88_200)).isEqualTo("88.2 kHz")
    }

    @Test
    fun `bitrates are thousands-separated`() {
        assertThat(formatKbps(1_411_200)).isEqualTo("1,411 kbps")
        assertThat(formatKbps(2_699_000)).isEqualTo("2,699 kbps")
    }

    @Test
    fun `both details surfaces format rates through the shared helper`() {
        val songInfo = source("presentation/components/SongInfoBottomSheet.kt")

        // The second implementation is gone: Song Info used to always print one decimal, so the
        // same source read "48 kHz" in Track Details and "48.0 kHz" beside it.
        assertThat(songInfo).doesNotContain("""%.1f kHz""")
        assertThat(songInfo).contains("formatHz(it)")
        assertThat(songInfo).contains("formatKbps(it)")
    }

    // --- I/J: one canonical details state, no composable-side inference ---------------------------

    @Test
    fun `the canonical details surface is driven by the runtime view model`() {
        val overlay = source("presentation/components/UnifiedPlayerOverlaysLayer.kt")
        val sheet = source("presentation/components/player/TrackDetailsSheet.kt")

        // The DETAILS sibling resolves to the details surface, not to the action menu.
        assertThat(overlay).contains("SongInfoEntry.DETAILS")
        assertThat(overlay).contains("SecondaryView.TRACK_DETAILS")
        assertThat(overlay).contains("TrackDetailsSheet(")

        // The sheet renders state and owns no route reasoning of its own.
        assertThat(sheet).doesNotContain("audioRouteState")
        assertThat(sheet).doesNotContain("AudioManager")
        assertThat(sheet).doesNotContain("getAudioDevicesForAttributes")
    }

    @Test
    fun `there is exactly one source of route truth for the details surfaces`() {
        // SongInfoBottomSheet must not compute a route or a rate: it has no route state at all.
        val songInfo = source("presentation/components/SongInfoBottomSheet.kt")
        assertThat(songInfo).doesNotContain("audioRouteState")
        assertThat(songInfo).doesNotContain("confirmedRateHz")

        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine).contains("val audioRouteState: StateFlow<AudioRouteState>")
    }

    @Test
    fun `the details entry does not mount the song-options menu underneath it`() {
        // Reported from the device: with the details drawn in a dialog above the options menu,
        // backing out of the details revealed the menu — a surface the user never opened. The two
        // entries are separate surfaces, so the menu is mounted only for the actions entry.
        val overlay = source("presentation/components/UnifiedPlayerOverlaysLayer.kt")

        assertThat(overlay).contains("val showsSongActions = songInfoEntry == SongInfoEntry.ACTIONS")
        assertThat(overlay).contains("if (showsSongActions) {")

        // And closing the details leaves the overlay when there is nothing behind it, instead of
        // falling through to a menu.
        assertThat(overlay).contains("if (showsSongActions) secondaryView = null else onDismissSongInfo()")
    }

    // --- METADATA omitted rather than faked -------------------------------------------------------

    @Test
    fun `the metadata section omits rows with no value`() {
        val sheet = source("presentation/components/player/TrackDetailsSheet.kt")
        val group = sheet.substringAfter("private fun MetadataGroup(").substringBefore("\n/** A long opaque")

        // Every row is filtered through a null check before it is emitted, and the identifiers are
        // gated the same way — no placeholder strings reach the screen.
        assertThat(group).contains("mapNotNull")
        assertThat(group).contains("takeIf { it.isNotBlank() }")
        assertThat(group).doesNotContain("\"<unknown>\"")
        assertThat(group).doesNotContain("\"N/A\"")
    }
}
