package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFillController
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzRelease
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzReleaseCandidate
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzReleaseSource
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzReleaseTrack
import com.lostf1sh.pixelplayeross.presentation.components.EditAlbumContent
import com.lostf1sh.pixelplayeross.presentation.components.EditSongContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.MetadataBatchProgress
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B57 1.3: the song editor with its new Year / date field, and the album editor (new), idle, with a MusicBrainz diff, and saving; light and dark. Made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B57EditorRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun song(n: Int) = Song(
        id = "$n", title = if (n == 2) "Luzz" else "Track $n", artist = "Gemini Sound", artistId = 1, album = "Noche", albumId = 1, albumArtist = "Gemini Sound",
        path = "", contentUriString = "", albumArtUriString = null, duration = 200_000, genre = "Rock", trackNumber = n, discNumber = if (n > 4) 2 else 1,
        year = 2019, mimeType = null, bitrate = null, sampleRate = null,
    )

    private val release = MusicBrainzRelease(
        "rel", "Noche (Deluxe)", "Gemini Sound", "2019-05-03", listOf("indie rock"),
        (1..6).map { MusicBrainzReleaseTrack(if (it > 4) 2 else 1, if (it > 4) it - 4 else it, if (it == 2) "Luz" else "Track $it", null) },
    )

    private fun themed(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) { Box(Modifier.fillMaxSize()) { content() } }
    }

    private fun fillController(withDiff: Boolean): AlbumFillController {
        val source = object : MusicBrainzReleaseSource {
            override suspend fun searchReleases(album: String, artist: String) = emptyList<MusicBrainzReleaseCandidate>()
            override suspend fun lookupRelease(releaseId: String) = release
        }
        return AlbumFillController(source, CoroutineScope(Dispatchers.Unconfined)).also { controller ->
            if (withDiff) {
                controller.start(com.lostf1sh.pixelplayeross.presentation.components.initialAlbumFormForRender((1..6).map(::song)), listOf("rel"), "Gemini Sound")
                controller.toggle("TRACK_TITLE:2")
                controller.toggle("DATE")
            }
        }
    }

    private fun album(name: String, dark: Boolean, withDiff: Boolean = false, progress: MetadataBatchProgress? = null) {
        themed(dark) { EditAlbumContent((1..6).map(::song).toImmutableList(), fillController(withDiff), progress, onDismiss = {}, onSave = { _, _, _, _, _, _ -> }) }
        B57Renders.save(compose, name)
    }

    @Test fun album_editor_light() = album("editor-album-light", dark = false)
    @Test fun album_editor_dark() = album("editor-album-dark", dark = true)
    @Test fun album_editor_with_musicbrainz_diff_light() = album("editor-album-musicbrainz-light", dark = false, withDiff = true)
    @Test fun album_editor_with_musicbrainz_diff_dark() = album("editor-album-musicbrainz-dark", dark = true, withDiff = true)
    @Test fun album_editor_saving_dark() = album("editor-album-saving-dark", dark = true, progress = MetadataBatchProgress(done = 4, total = 12, failed = 1))

    private fun songSheet(name: String, dark: Boolean) {
        themed(dark) { EditSongContent(song = song(3), onDismiss = {}, onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> }) }
        B57Renders.save(compose, name)
    }

    // taller than a phone, so the whole form (with the new Year / date field) is in one picture
    @Config(qualifiers = "w360dp-h1500dp-xhdpi") @Test fun song_editor_light() = songSheet("editor-song-light", dark = false)
    @Config(qualifiers = "w360dp-h1500dp-xhdpi") @Test fun song_editor_dark() = songSheet("editor-song-dark", dark = true)
}
