package com.lostf1sh.pixelplayeross.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumTrackEdit
import com.lostf1sh.pixelplayeross.presentation.viewmodel.resolveBatchTrackFields
import com.lostf1sh.pixelplayeross.presentation.viewmodel.summariseBatchFailures
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * B57 1.3, "Fill from MusicBrainz" in the album editor. Everything here is pure or runs against a fake release source: no request leaves the machine, and nothing
 * here can write a file (the controller has no editor in it), which is the point: the diff is a suggestion until Save.
 */
class B57AlbumFillTest {
    private fun form(vararg tracks: AlbumFormTrack, album: String = "Noche", albumArtist: String = "", date: String = "", genre: String = "") =
        AlbumFormState(album, albumArtist, date, genre, tracks.toList())

    private val release = MusicBrainzRelease(
        releaseId = "rel-1", title = "Noche (Deluxe)", artist = "Gemini Sound", date = "2019-05-03", genres = listOf("indie rock", "pop"),
        tracks = listOf(
            MusicBrainzReleaseTrack(1, 1, "Cine", "rec-1"),
            MusicBrainzReleaseTrack(1, 2, "Luz", "rec-2"),
            MusicBrainzReleaseTrack(2, 1, "Cierre", "rec-3"),
        ),
    )

    @Test
    fun `a diff lists only what differs, field by field, and names the tracks by song`() {
        val state = form(
            AlbumFormTrack("a", "cine", 1, null), // same title apart from case: no title proposal, the slot matches
            AlbumFormTrack("b", "Luzz", 2, null), // a typo MusicBrainz corrects
            AlbumFormTrack("c", "Cierre", 1, 2),
            albumArtist = "Gemini Sound", date = "2019",
        )
        val proposals = AlbumFill.propose(state, release)
        val byId = proposals.associateBy { it.id }
        assertThat(byId.keys).containsExactly("ALBUM", "DATE", "GENRE", "TRACK_TITLE:a", "TRACK_TITLE:b")
        assertThat(byId.getValue("ALBUM").proposed).isEqualTo("Noche (Deluxe)")
        assertThat(byId.getValue("DATE").proposed).isEqualTo("2019-05-03")
        assertThat(byId.getValue("GENRE").proposed).isEqualTo("Indie Rock")
        assertThat(byId.getValue("TRACK_TITLE:b").current).isEqualTo("Luzz")
    }

    @Test
    fun `nothing changes until the boxes are checked and only the checked fields are applied`() {
        val state = form(AlbumFormTrack("a", "Cine", 1, null), AlbumFormTrack("b", "Luzz", 2, null))
        val proposals = AlbumFill.propose(state, release)
        assertThat(AlbumFill.apply(state, proposals, emptySet())).isEqualTo(state)
        val picked = AlbumFill.apply(state, proposals, setOf("TRACK_TITLE:b", "DATE"))
        assertThat(picked.tracks.map { it.title }).containsExactly("Cine", "Luz").inOrder()
        assertThat(picked.date).isEqualTo("2019-05-03")
        assertThat(picked.album).isEqualTo("Noche") // not checked: untouched
        assertThat(picked.genre).isEmpty()
    }

    @Test
    fun `a song that matches no release track, or a slot another song took, gets no proposal`() {
        val state = form(AlbumFormTrack("a", "Cine", 1, null), AlbumFormTrack("dup", "Cine again", 1, null), AlbumFormTrack("x", "Not on the release", 9, null))
        val matches = AlbumFill.matchTracks(state.tracks, release.tracks)
        assertThat(matches.keys).containsExactly("a")
        assertThat(AlbumFill.propose(state, release).none { it.songId == "x" || it.songId == "dup" }).isTrue()
    }

    @Test
    fun `a song with no track number is matched by its title only when the title is unique`() {
        val twin = release.copy(tracks = release.tracks + MusicBrainzReleaseTrack(2, 2, "Cine", null))
        val state = form(AlbumFormTrack("a", "Luz", 0, null), AlbumFormTrack("b", "Cine", 0, null))
        val matches = AlbumFill.matchTracks(state.tracks, twin.tracks)
        assertThat(matches.keys).containsExactly("a") // "Cine" appears twice on this release: not offered
        assertThat(matches.getValue("a").position).isEqualTo(2)
    }

    @Test
    fun `the release id the files carry is the most common one`() {
        assertThat(AlbumFill.knownReleaseId(listOf("r1", null, "r2", "r1", ""))).isEqualTo("r1")
        assertThat(AlbumFill.knownReleaseId(listOf(null, null))).isNull()
    }

    // ── the controller, over a fake source ──────────────────────────────────────────────────────────

    private class FakeSource(private val releases: Map<String, MusicBrainzRelease?>, private val found: List<MusicBrainzReleaseCandidate> = emptyList(), private val failure: Exception? = null) : MusicBrainzReleaseSource {
        val calls = mutableListOf<String>()
        override suspend fun searchReleases(album: String, artist: String): List<MusicBrainzReleaseCandidate> { calls += "search:$album:$artist"; failure?.let { throw it }; return found }
        override suspend fun lookupRelease(releaseId: String): MusicBrainzRelease? { calls += "lookup:$releaseId"; failure?.let { throw it }; return releases[releaseId] }
    }

    private fun controller(source: MusicBrainzReleaseSource) = AlbumFillController(source, CoroutineScope(Dispatchers.Unconfined))
    private val twoTracks get() = form(AlbumFormTrack("a", "Cine", 1, null), AlbumFormTrack("b", "Luzz", 2, null))

    @Test
    fun `with the release id in the files it looks that release up and never searches`() {
        val source = FakeSource(mapOf("rel-1" to release))
        val c = controller(source)
        c.start(twoTracks, listOf("rel-1", "rel-1", null), "Gemini Sound")
        val state = c.state.value as AlbumFillState.Diff
        assertThat(source.calls).containsExactly("lookup:rel-1")
        assertThat(state.accepted).isEmpty() // nothing is pre-checked
        c.toggle("TRACK_TITLE:b")
        assertThat((c.state.value as AlbumFillState.Diff).accepted).containsExactly("TRACK_TITLE:b")
        c.setAll(true)
        assertThat((c.state.value as AlbumFillState.Diff).accepted).hasSize(state.proposals.size)
        c.dismiss()
        assertThat(c.state.value).isEqualTo(AlbumFillState.Idle)
    }

    @Test
    fun `without a release id it searches by album and artist and lets the listener pick`() {
        val candidates = listOf(
            MusicBrainzReleaseCandidate("rel-1", "Noche", "Gemini Sound", "2019", 12, "XW", null, 100),
            MusicBrainzReleaseCandidate("rel-2", "Noche", "Gemini Sound", "2021", 14, "ES", "reissue", 90),
        )
        val source = FakeSource(mapOf("rel-2" to release), found = candidates)
        val c = controller(source)
        c.start(twoTracks, listOf(null, null), "Gemini Sound")
        assertThat((c.state.value as AlbumFillState.PickRelease).candidates).hasSize(2)
        assertThat(source.calls).containsExactly("search:Noche:Gemini Sound")
        c.choose(candidates[1], twoTracks)
        assertThat(c.state.value).isInstanceOf(AlbumFillState.Diff::class.java)
        assertThat(source.calls).containsExactly("search:Noche:Gemini Sound", "lookup:rel-2").inOrder()
    }

    @Test
    fun `no release, nothing different and a failed request each end in their own state`() {
        val none = controller(FakeSource(emptyMap()))
        none.start(twoTracks, listOf(null), "x")
        assertThat(none.state.value).isEqualTo(AlbumFillState.NoMatch)

        val same = controller(FakeSource(mapOf("r" to MusicBrainzRelease("r", "Noche", "", null, emptyList(), emptyList()))))
        same.start(twoTracks, listOf("r"), "x")
        assertThat(same.state.value).isEqualTo(AlbumFillState.NoMatch)

        val offline = controller(FakeSource(emptyMap(), failure = IOException("offline")))
        offline.start(twoTracks, listOf(null), "x")
        assertThat(offline.state.value).isEqualTo(AlbumFillState.Failed("offline"))
    }

    // ── the response shapes ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a release search response becomes candidates, best score first`() {
        val json = JSONObject(
            """{"releases":[
              {"id":"b","score":80,"title":"Noche","track-count":10,"date":"2020","country":"ES","artist-credit":[{"name":"Gemini","joinphrase":" & "},{"name":"Sound"}]},
              {"id":"a","score":100,"title":"Noche","track-count":12,"disambiguation":"deluxe","artist-credit":[{"artist":{"name":"Gemini Sound"}}]},
              {"title":"no id"}]}"""
        )
        val found = MusicBrainzApiService.parseReleaseSearch(json)
        assertThat(found.map { it.releaseId }).containsExactly("a", "b").inOrder()
        assertThat(found[0].artist).isEqualTo("Gemini Sound")
        assertThat(found[1].artist).isEqualTo("Gemini & Sound")
        assertThat(found[0].disambiguation).isEqualTo("deluxe")
    }

    @Test
    fun `a release lookup response becomes tracks by disc and position, with genres by count`() {
        val json = JSONObject(
            """{"id":"rel-1","title":"Noche","date":"2019-05-03","artist-credit":[{"name":"Gemini Sound"}],
              "genres":[{"name":"pop","count":1},{"name":"indie rock","count":5}],
              "media":[{"position":1,"tracks":[{"position":1,"title":"Cine","recording":{"id":"r1"}},{"position":2,"title":"","recording":{"id":"r2","title":"Luz"}}]},
                       {"position":2,"tracks":[{"position":1,"title":"Cierre"}]}]}"""
        )
        val parsed = MusicBrainzApiService.parseRelease(json)!!
        assertThat(parsed.genres).containsExactly("indie rock", "pop").inOrder()
        assertThat(parsed.tracks.map { Triple(it.disc, it.position, it.title) }).containsExactly(Triple(1, 1, "Cine"), Triple(1, 2, "Luz"), Triple(2, 1, "Cierre")).inOrder()
    }

    // ── what a whole-album save writes per track, and how a failure is reported ───────────────────────

    private fun song(id: String, title: String, track: Int, disc: Int?) = Song(
        id = id, title = title, artist = "A", artistId = 1, album = "Noche", albumId = 1, path = "", contentUriString = "", albumArtUriString = null,
        duration = 1, trackNumber = track, discNumber = disc, mimeType = null, bitrate = null, sampleRate = null,
    )

    @Test
    fun `the album editor's own track values win over the batch's, which win over the song's`() {
        val s = song("1", "Old", 5, 1)
        assertThat(resolveBatchTrackFields(AlbumTrackEdit("New", 2, null), "Batch", 9, 3, s)).isEqualTo(Triple("New", 2, null))
        assertThat(resolveBatchTrackFields(null, "Batch", 9, 3, s)).isEqualTo(Triple("Batch", 9, 3))
        assertThat(resolveBatchTrackFields(null, null, null, null, s)).isEqualTo(Triple("Old", 5, 1))
    }

    @Test
    fun `a failure is reported with at most two distinct reasons`() {
        assertThat(summariseBatchFailures(listOf("Permission denied", "", "Permission denied", "File not found", "Disk full"))).isEqualTo("Permission denied; File not found")
        assertThat(summariseBatchFailures(listOf("", " "))).isNull()
    }
}
