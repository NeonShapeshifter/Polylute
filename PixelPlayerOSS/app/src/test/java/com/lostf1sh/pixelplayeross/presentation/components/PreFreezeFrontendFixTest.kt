package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contracts the pre-freeze frontend fix has to keep.
 *
 * Source assertions rather than UI tests, for the same reason the other contract tests in this
 * package are: the claims are about *which primitive* a control is built from and *whether* a
 * second implementation exists, and a Compose test would need the whole player graph to ask that.
 *
 * A path that cannot be found fails loudly rather than passing vacuously.
 */
class PreFreezeFrontendFixTest {

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

    /**
     * [source] with comments removed.
     *
     * The "no longer built from X" claims would otherwise match the prose that *describes* what was
     * removed — a comment saying "this used to be a HorizontalFloatingToolbar" contains the very
     * token the assertion forbids. These tests are about the code.
     */
    private fun code(relative: String): String {
        val text = source(relative)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i++ }
                    else if (c == '"') inString = false
                    i++
                }
                c == '"' -> { inString = true; out.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private val queue by lazy { source("presentation/components/QueueBottomSheet.kt") }

    // --- 1: the queue More affordance --------------------------------------------------------------

    @Test
    fun `the queue more affordance is the shared overflow primitive`() {
        // Guard against a vacuous pass: the affordance must still exist and still be wired.
        // B18-R5: the row travels with the song, so "remove from list" in the menu acts on this occurrence.
        assertThat(queue).contains("onMoreOptionsClick = { onSongInfoClick(song, queueIndex) }")
        assertThat(queue).contains("DapOverflowButton(")
    }

    @Test
    fun `the queue more affordance is not a squashed icon button`() {
        // Was `FilledIconButton(Modifier.size(36.dp).padding(end = 14.dp))`. The padding sat after
        // the size, so it ate into the sized box: the touch area collapsed to ~22 dp and sat to the
        // right of the dots it drew, on top of the row's own play gesture.
        assertThat(queue).doesNotContain(".size(36.dp)\n                                .padding(end = 14.dp)")
        assertThat(queue).doesNotContain("mvContainerColor")
        assertThat(queue).doesNotContain("mvContentColor")
    }

    @Test
    fun `the shared overflow primitive carries a full touch target around a compact glyph`() {
        val chrome = source("ui/ds/DapChrome.kt")
        val button = chrome.substringAfter("fun DapOverflowButton(").substringBefore("/**\n * The standard bar")

        // A 48 dp target with the glyph centred inside it — which is the whole fix.
        assertThat(button).contains("size(DapSizes.MinTouchTarget)")
        assertThat(button).contains("contentAlignment = Alignment.Center")
        assertThat(button).contains("DapIcon(")

        val theme = source("ui/ds/DapTheme.kt")
        assertThat(theme).contains("val MinTouchTarget = 48.dp")
    }

    // --- 3: Track Details reachable from list actions ----------------------------------------------

    /** The ordinary song-action hosts that must offer Track details. */
    private val listHosts = listOf(
        "presentation/screens/LibraryScreen.kt",
        "presentation/screens/SearchScreen.kt",
        "presentation/screens/AlbumDetailScreen.kt",
        "presentation/screens/ArtistDetailScreen.kt",
        "presentation/screens/PlaylistDetailScreen.kt",
        "presentation/screens/RecentlyPlayedScreen.kt",
        "presentation/screens/AutoCollectionDetailScreen.kt",
        "presentation/screens/GenreDetailScreen.kt",
        "presentation/components/DailyMixSection.kt",
    )

    @Test
    fun `every list host offers the details row and mounts the canonical host`() {
        listHosts.forEach { host ->
            val text = source(host)
            assertThat(text).contains("onViewTrackDetails = { showTrackDetails = true }")
            assertThat(text).contains("TrackDetailsHost(")
        }
    }

    @Test
    fun `there is exactly one details implementation and the host reuses it`() {
        val host = source("presentation/components/player/TrackDetailsHost.kt")

        // The same sheet and the same view model the player uses — not a second surface.
        assertThat(host).contains("TrackDetailsSheet(")
        assertThat(host).contains("TrackDetailsViewModel")
        // And no technical value is recomputed here.
        assertThat(host).doesNotContain("AudioMetaUtils")
        assertThat(host).doesNotContain("sampleRate")
        assertThat(host).doesNotContain("bitrate")
    }

    @Test
    fun `the details overlay only accepts a live reading for its own song`() {
        // LiveAudioFormat.from returns null unless the metadata describes this song, so opening
        // details for something that is not playing cannot borrow the playing track's format.
        val vm = source("presentation/viewmodel/TrackDetailsViewModel.kt")
        val from = vm.substringAfter("fun from(").substringBefore("private const val NO_FORMAT")

        assertThat(from).contains("if (metadata.mediaId != songId) return null")
        assertThat(from).contains("durationMs.takeIf { it > 0 }")
    }

    @Test
    fun `the details state self-clears with the action sheet`() {
        // Every host keys the flag on the sheet's own visibility, so no dismissal path can leave
        // it set and reopen the details by itself next time the sheet appears.
        listHosts.forEach { host ->
            val text = source(host)
            assertThat(text).contains("var showTrackDetails by remember(")
        }
    }

    // --- 2: EditSongSheet on the design system ------------------------------------------------------

    private val edit by lazy { source("presentation/components/EditSongSheet.kt") }

    @Test
    fun `the edit sheet saves through the design system's buttons`() {
        assertThat(edit).contains("DapPrimaryButton(")
        assertThat(edit).contains("DapSecondaryButton(")
        assertThat(edit).contains("stringResource(R.string.action_save)")
        assertThat(edit).contains("stringResource(R.string.cancel)")
    }

    @Test
    fun `the edit sheet is not built from material cards and coloured blocks`() {
        val editCode = code("presentation/components/EditSongSheet.kt")

        // The float toolbar holding a filled tonal Cancel and a filled Save.
        assertThat(editCode).doesNotContain("HorizontalFloatingToolbar")
        assertThat(editCode).doesNotContain("FloatingToolbarDefaults")

        // The cover-art card: an elevated Surface with a smooth corner and a scrim gradient.
        assertThat(editCode).doesNotContain("tonalElevation")
        assertThat(editCode).doesNotContain("Brush.verticalGradient")
        assertThat(editCode).doesNotContain("AbsoluteSmoothCornerShape")

        // The delete cover art action was an errorContainer filled button, and the save/cancel pair
        // were filled tonal buttons.
        assertThat(editCode).doesNotContain("errorContainer")
        assertThat(editCode).doesNotContain("FilledTonalButton")
        assertThat(editCode).doesNotContain("FilledTonalIconButton")

        // OutlinedTextField is the Material input this form was made of.
        assertThat(editCode).doesNotContain("OutlinedTextField")
    }

    @Test
    fun `destructive edit actions keep the established destructive treatment`() {
        assertThat(edit).contains("DapDestructiveAction(")
        assertThat(edit).contains("stringResource(R.string.edit_song_delete_cover_art)")
    }

    @Test
    fun `the edit form keeps every field and the save payload it always had`() {
        // Guard against a restyle quietly dropping a field.
        listOf(
            "song_field_title", "song_field_artist", "song_field_album", "song_field_album_artist",
            "song_field_genre", "song_field_composer", "song_field_track_number",
            "song_field_disc_number", "song_field_replaygain_track_db",
            "song_field_replaygain_album_db", "lyrics",
        ).forEach { field ->
            assertThat(edit).contains("R.string.$field")
        }

        // The payload is unchanged: same order, same trims, same track-number fallback.
        assertThat(edit).contains("val resolvedTrackNumber = trackNumber.toIntOrNull() ?: song.trackNumber")
        assertThat(edit).contains("val resolvedDiscNumber = discNumber.toIntOrNull()")
        assertThat(edit).contains("title.trim(),")
        assertThat(edit).contains("editedCoverArt")
    }

    @Test
    fun `the artwork actions and the hydration path are intact`() {
        assertThat(edit).contains("PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)")
        assertThat(edit).contains("CoverArtCropperDialog(")
        assertThat(edit).contains("CoverArtUpdate(isDeletion = true)")
        assertThat(edit).contains("AudioMetadataReader.read(file, readArtwork = false)")
        // The embedded-metadata read still only fills lyrics that are blank and still reads composer
        // and ReplayGain unconditionally.
        assertThat(edit).contains("if (lyrics.isBlank())")
        assertThat(edit).contains("embeddedMetadata?.composer?.takeIf { it.isNotBlank() }?.let { composer = it }")
    }
}
