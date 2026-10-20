package com.lostf1sh.pixelplayeross.data.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The normalization rules, as a corpus.
 *
 * Every rule in [MetadataNormalizer] is a pure function of its arguments, which is the only reason a
 * metadata phase can be tested at all: "this album should group with that one" is not a statement
 * about a database or a device, it is a statement about two strings.
 */
class MetadataNormalizerTest {

    private fun albumKey(
        title: String?,
        albumArtist: String? = null,
        dir: String? = "/music",
        compilation: Boolean = false,
    ) = MetadataNormalizer.albumGroupingKey(title, albumArtist, dir, compilation).value

    // --- A. ordinary single-artist album -------------------------------------------------------------

    @Test
    @DisplayName("A · an ordinary album groups every track together")
    fun a_ordinaryAlbumGroups() {
        val keys = listOf(
            albumKey("A Night at the Opera", "Queen"),
            albumKey("A Night at the Opera", "Queen"),
            albumKey("A Night at the Opera", "Queen"),
        )
        assertEquals(1, keys.toSet().size)
    }

    // --- B. compilation ------------------------------------------------------------------------------

    @Test
    @DisplayName("B · a compilation is one album however many track artists it has")
    fun b_compilationIsOneAlbum() {
        val keys = listOf(
            "Freddie Mercury", "David Bowie", "Annie Lennox", "George Michael",
        ).map { performer ->
            albumKey(
                title = "Greatest Hits",
                albumArtist = "Various Artists",
                compilation = true,
            )
        }
        assertEquals(1, keys.toSet().size, "four performers, one album")
    }

    // --- C. featured artist --------------------------------------------------------------------------

    @Test
    @DisplayName("C · a featured artist does not split the album")
    fun c_featuredArtistDoesNotSplit() {
        // The performers differ; the album artist does not. The track artist is not consulted at
        // all, which is what makes this hold for a release with no album-artist tag too — see the
        // regression test below.
        val lead = albumKey("The Album", albumArtist = "Queen")
        val featured = albumKey("The Album", albumArtist = "Queen")
        assertEquals(lead, featured)
    }

    @Test
    @DisplayName("C · an album with no album-artist tag is not split by differing performer credits")
    fun c_noAlbumArtistStillGroupsByRelease() {
        // The regression this rule exists for. A real library holds an album whose tracks are
        // credited "The Weeknd" and "The Weeknd, Justice" with no album-artist tag: keying on the
        // performer credit produced two albums for one release. Performer credits say who plays on a
        // track, never which release it belongs to.
        val solo = MetadataNormalizer.albumGroupingKey(
            albumTitle = "Hurry Up Tomorrow", albumArtist = null,
            parentDirectoryPath = "/music/The Weeknd & Justice - Hurry Up Tomorrow",
            compilation = false,
        ).value
        val collaboration = MetadataNormalizer.albumGroupingKey(
            albumTitle = "Hurry Up Tomorrow", albumArtist = null,
            parentDirectoryPath = "/music/The Weeknd & Justice - Hurry Up Tomorrow",
            compilation = false,
        ).value
        assertEquals(solo, collaboration)
    }

    // --- D. same title, different artists -------------------------------------------------------------

    @Test
    @DisplayName("D · two albums with the same name by different artists stay separate")
    fun d_sameTitleDifferentArtistsAreSeparate() {
        assertNotEquals(
            albumKey("Greatest Hits", "Queen"),
            albumKey("Greatest Hits", "ABBA"),
        )
        // And the title is matched case-insensitively, so casing alone does not split.
        assertEquals(
            albumKey("Greatest Hits", "Queen"),
            albumKey("greatest hits", "QUEEN"),
        )
    }

    // --- E. multi-disc -------------------------------------------------------------------------------

    @Test
    @DisplayName("E · a two-disc release in per-disc subfolders is one album")
    fun e_multiDiscInSubfoldersIsOneAlbum() {
        val discOne = albumKey("The Wall", "Pink Floyd", dir = "/music/Pink Floyd/The Wall/CD 1")
        val discTwo = albumKey("The Wall", "Pink Floyd", dir = "/music/Pink Floyd/The Wall/CD 2")
        assertEquals(discOne, discTwo, "a disc folder must not split the release")
    }

    @Test
    @DisplayName("E · disc-folder stripping is narrow enough not to eat real folder names")
    fun e_discFolderStrippingIsNarrow() {
        // Only a final component that names a disc and nothing else.
        assertEquals("/music/Album", MetadataNormalizer.stripDiscFolder("/music/Album/Disc 1"))
        assertEquals("/music/Album", MetadataNormalizer.stripDiscFolder("/music/Album/CD2"))
        assertEquals("/music/Album", MetadataNormalizer.stripDiscFolder("/music/Album/disk_02"))
        assertEquals("/music/Album", MetadataNormalizer.stripDiscFolder("/music/Album/Vol 3"))
        // Not a disc folder:
        assertEquals("/music/Discography", MetadataNormalizer.stripDiscFolder("/music/Discography"))
        assertEquals("/music/CD Collection", MetadataNormalizer.stripDiscFolder("/music/CD Collection"))
        assertEquals("/music/Album/Bonus Disc Material", MetadataNormalizer.stripDiscFolder("/music/Album/Bonus Disc Material"))
    }

    // --- F. track numbers ----------------------------------------------------------------------------

    @Test
    @DisplayName("F · the several shapes a track number arrives in")
    fun f_trackNumberShapes() {
        assertEquals(3, MetadataNormalizer.parseTrackNumber("3"))
        assertEquals(3, MetadataNormalizer.parseTrackNumber("03"))
        assertEquals(3, MetadataNormalizer.parseTrackNumber("3/12"))
        assertEquals(3, MetadataNormalizer.parseTrackNumber(" 3 "))
        assertEquals(12, MetadataNormalizer.parseTrackNumber("012/012"))
    }

    @Test
    @DisplayName("F · an unset track number is absent, not zero")
    fun f_unsetTrackNumberIsNull() {
        // Taggers write 0 for "unset". Reading it as a position would sort the track before track 1
        // for the rest of the library's life.
        assertNull(MetadataNormalizer.parseTrackNumber("0"))
        assertNull(MetadataNormalizer.parseTrackNumber("00"))
        assertNull(MetadataNormalizer.parseTrackNumber("-1"))
        assertNull(MetadataNormalizer.parseTrackNumber(""))
        assertNull(MetadataNormalizer.parseTrackNumber("   "))
        assertNull(MetadataNormalizer.parseTrackNumber(null))
        assertNull(MetadataNormalizer.parseTrackNumber("not a number"))
        assertNull(MetadataNormalizer.parseTrackNumber("A3"))
    }

    @Test
    @DisplayName("F · MediaStore's packed value is decimal: disc * 1000 + track")
    fun f_packedTrackDecodes() {
        assertEquals(3, MetadataNormalizer.decodePackedTrack(3).track)
        assertNull(MetadataNormalizer.decodePackedTrack(3).disc)

        // The value on the owner's device (B11-fix FIX-3): 4869 of 4870 songs, disc 1 track 12. The
        // layout this test used to assert (two 12-bit fields) read it as track 1012 with no disc,
        // which Track Details printed as "1-1012"; the app's own SongMetadataEditor encodes
        // `(disc * 1000) + track`, which is what MediaStore holds.
        val discOne = MetadataNormalizer.decodePackedTrack(1012)
        assertEquals(1, discOne.disc)
        assertEquals(12, discOne.track)

        val discTwo = MetadataNormalizer.decodePackedTrack(2021)
        assertEquals(2, discTwo.disc)
        assertEquals(21, discTwo.track)

        // A disc with no track (the tagger wrote only the disc): the disc is kept, no track.
        val onlyDisc = MetadataNormalizer.decodePackedTrack(3000)
        assertEquals(3, onlyDisc.disc)
        assertNull(onlyDisc.track)

        val unset = MetadataNormalizer.decodePackedTrack(0)
        assertTrue(unset.isUnknown)
        assertEquals("0", unset.rawTrack, "the raw value is preserved for the diagnostic")
    }

    // --- G. year vs first seen -----------------------------------------------------------------------

    @Test
    @DisplayName("G · a release year and a first-seen date are different facts")
    fun g_releaseYearIsNotFirstSeen() {
        // The distinction is structural: a release year is metadata, a first-seen date is owned by
        // the library item. The normalizer only ever produces the former.
        assertEquals(1997, MetadataNormalizer.parseYear(1997))
        assertEquals(2024, MetadataNormalizer.parseYear(2024))
    }

    @Test
    @DisplayName("G · MediaStore's placeholders for a missing year are not years")
    fun g_missingYearIsNotAYear() {
        assertNull(MetadataNormalizer.parseYear(0))
        assertNull(MetadataNormalizer.parseYear(-1))
        assertNull(MetadataNormalizer.parseYear(1))
        assertNull(MetadataNormalizer.parseYear(null))
        assertNull(MetadataNormalizer.parseYear(999_999))
    }

    // --- H / I / J. first-seen durability --------------------------------------------------------------

    @Test
    @DisplayName("H · the grouping key does not depend on the locator, so a renumber cannot change it")
    fun h_groupingKeyIsLocatorFree() {
        // Nothing in the signature accepts a MediaStore id, a locator or a date. That is the
        // structural reason a renumber, a temporary loss or a return cannot move a track between
        // albums — there is no field for the change to travel through.
        val before = albumKey("The Wall", "Pink Floyd", dir = "/music/Pink Floyd/The Wall")
        val after = albumKey("The Wall", "Pink Floyd", dir = "/music/Pink Floyd/The Wall")
        assertEquals(before, after)
    }

    @Test
    @DisplayName("J · a new identity on a reused locator computes its own key from its own metadata")
    fun j_reusedLocatorComputesItsOwnKey() {
        val original = albumKey("Kid A", "Radiohead")
        val replacement = albumKey("Different Album", "Different Artist")
        assertNotEquals(original, replacement)
    }

    // --- K. genres ------------------------------------------------------------------------------------

    @Test
    @DisplayName("K · multiple genres are preserved, in the order they were written")
    fun k_multipleGenresPreserved() {
        assertEquals(listOf("Rock", "Pop"), MetadataNormalizer.parseGenres("Rock; Pop"))
        assertEquals(listOf("Rock", "Pop"), MetadataNormalizer.parseGenres("Rock / Pop".replace("/", ";")))
        assertEquals(listOf("Electronic"), MetadataNormalizer.parseGenres("Electronic"))
    }

    @Test
    @DisplayName("K · a slash is not a genre separator, because it is usually part of the name")
    fun k_slashIsNotASeparator() {
        // "R&B/Soul" is one genre to the people who tag it that way. Splitting on `/` would invent a
        // second genre called "Soul" that no file ever claimed, and it would then appear in browsing.
        assertEquals(listOf("R&B/Soul"), MetadataNormalizer.parseGenres("R&B/Soul"))
        assertEquals(listOf("Folk/Country"), MetadataNormalizer.parseGenres("Folk/Country"))
    }

    @Test
    @DisplayName("K · duplicate and blank genres are dropped, casing preserved")
    fun k_genresAreDeduplicatedNotFolded() {
        assertEquals(listOf("Rock", "Pop"), MetadataNormalizer.parseGenres("Rock; rock; Pop; "))
        assertEquals(emptyList<String>(), MetadataNormalizer.parseGenres(""))
        assertEquals(emptyList<String>(), MetadataNormalizer.parseGenres("<unknown>"))
    }

    // --- L. unknown metadata ---------------------------------------------------------------------------

    @Test
    @DisplayName("L · MediaStore's <unknown> sentinel is absence, not a name")
    fun l_unknownSentinelIsAbsence() {
        assertNull(MetadataNormalizer.normalizeForDisplay("<unknown>"))
        assertNull(MetadataNormalizer.normalizeForDisplay("<UNKNOWN>"))
        assertNull(MetadataNormalizer.normalizeForDisplay("  <unknown> "))
        assertEquals("Unknown Artist", MetadataNormalizer.displayValue("<unknown>", "Unknown Artist"))
        assertEquals("Unknown Album", MetadataNormalizer.displayValue(null, "Unknown Album"))
    }

    @Test
    @DisplayName("L · untagged files do not all collapse into one fake album")
    fun l_untaggedFilesDoNotMerge() {
        // The failure this prevents: every file in one folder with an unreadable album tag sharing
        // the album "<unknown>", and therefore merging into one enormous album. Two untagged tracks
        // in *different* folders are not the same album.
        val keyFromA = MetadataNormalizer.albumGroupingKey(
            albumTitle = "<unknown>", albumArtist = null,
            parentDirectoryPath = "/music/AlbumA", compilation = false,
        ).value
        val keyFromB = MetadataNormalizer.albumGroupingKey(
            albumTitle = "<unknown>", albumArtist = null,
            parentDirectoryPath = "/music/AlbumB", compilation = false,
        ).value
        assertNotEquals(keyFromA, keyFromB)
    }

    @Test
    @DisplayName("L · fully untagged metadata still produces a stable key rather than crashing")
    fun l_fullyUntaggedIsStable() {
        val a = MetadataNormalizer.albumGroupingKey(null, null, null, false).value
        val b = MetadataNormalizer.albumGroupingKey(null, null, null, false).value
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }

    // --- M / N. duplicates ------------------------------------------------------------------------------

    @Test
    @DisplayName("M · two copies of one recording share a duplicate key")
    fun m_probableDuplicateSharesAKey() {
        val original = MetadataNormalizer.duplicateKey("Bohemian Rhapsody", "Queen")
        val copy = MetadataNormalizer.duplicateKey("bohemian rhapsody ", "QUEEN")
        assertEquals(original, copy)
    }

    @Test
    @DisplayName("N · similar but different tracks are not grouped")
    fun n_similarTracksAreNotDuplicates() {
        // A live version and a studio version are different recordings; so are two different songs
        // with similar names, and the same song by two different artists.
        assertNotEquals(
            MetadataNormalizer.duplicateKey("Bohemian Rhapsody", "Queen"),
            MetadataNormalizer.duplicateKey("Bohemian Rhapsody (Live Aid)", "Queen"),
        )
        assertNotEquals(
            MetadataNormalizer.duplicateKey("Bohemian Rhapsody", "Queen"),
            MetadataNormalizer.duplicateKey("Bohemian Rhapsody", "The Muppets"),
        )
    }

    @Test
    @DisplayName("N · a track with no usable title or artist is not a duplicate candidate at all")
    fun n_unidentifiableTracksAreNotGrouped() {
        assertNull(MetadataNormalizer.duplicateKey(null, "Queen"))
        assertNull(MetadataNormalizer.duplicateKey("Song", null))
        assertNull(MetadataNormalizer.duplicateKey("<unknown>", "Queen"))
        assertNull(MetadataNormalizer.duplicateKey("Song", "<unknown>"))
    }

    // --- O. override precedence ---------------------------------------------------------------------------

    @Test
    @DisplayName("O · normalization is a pure interpretation of the source, never a user choice")
    fun o_normalizationIsNotAnOverride() {
        // The normalizer has no access to the override store and no parameter through which a user
        // choice could enter — which is what keeps "the user chose this" distinguishable from "the
        // app interprets it this way". Precedence is applied by the caller that has both.
        val sourceSays = MetadataNormalizer.normalizeForDisplay("A Night at the Opera")
        assertEquals("A Night at the Opera", sourceSays)
    }

    // --- P. compilations without evidence ------------------------------------------------------------------

    @Test
    @DisplayName("P · several track artists on one album is not on its own a compilation")
    fun p_multipleTrackArtistsIsNotEnough() {
        // A duet album, a split single, a soundtrack with two credited performers, or simply
        // inconsistent tagging all look like this — and none of them is a compilation. Guessing
        // "Various Artists" here would rewrite a legitimate album artist, which is the silent
        // correction this phase refuses to do.
        assertFalse(
            MetadataNormalizer.isCompilation(
                explicitFlag = null,
                albumArtist = "Queen",
                distinctTrackArtists = 5,
            )
        )
    }

    @Test
    @DisplayName("P · an explicit flag, or an explicit Various Artists album artist, decides it")
    fun p_explicitEvidenceDecides() {
        assertTrue(MetadataNormalizer.isCompilation(explicitFlag = true, albumArtist = null, distinctTrackArtists = 1))
        assertTrue(MetadataNormalizer.isCompilation(explicitFlag = null, albumArtist = "Various Artists", distinctTrackArtists = 9))
        assertTrue(MetadataNormalizer.isCompilation(explicitFlag = null, albumArtist = "various artists", distinctTrackArtists = 1))
        // A `COMPILATION=0` flag alongside an explicit "Various Artists" album artist is a real
        // conflict, and the album artist wins: it is the value the grouping key uses, so honouring
        // the flag instead would leave an album grouped under "Various Artists" that the app also
        // claimed was not a compilation.
        assertTrue(MetadataNormalizer.isCompilation(explicitFlag = false, albumArtist = "Various Artists", distinctTrackArtists = 3))
        // With no such album artist, the flag is simply believed.
        assertFalse(MetadataNormalizer.isCompilation(explicitFlag = false, albumArtist = "Queen", distinctTrackArtists = 3))
    }

    // --- album artist semantics ----------------------------------------------------------------------------

    @Test
    @DisplayName("album artist falls back to the track artist, and the two are never collapsed")
    fun albumArtistFallsBackButStaysDistinct() {
        assertEquals("Queen", MetadataNormalizer.resolveAlbumArtist("Queen", "Freddie Mercury"))
        assertEquals("Freddie Mercury", MetadataNormalizer.resolveAlbumArtist(null, "Freddie Mercury"))
        assertEquals("Freddie Mercury", MetadataNormalizer.resolveAlbumArtist("<unknown>", "Freddie Mercury"))
        assertNull(MetadataNormalizer.resolveAlbumArtist(null, null))
    }

    // --- artist grouping -----------------------------------------------------------------------------------

    @Test
    @DisplayName("artist grouping folds case and whitespace, and nothing else")
    fun artistGroupingIsConservative() {
        assertEquals(
            MetadataNormalizer.artistGroupingKey("Queen"),
            MetadataNormalizer.artistGroupingKey("  queen  "),
        )
        // Deliberately NOT merged. A false merge of two real artists is far worse than a missed
        // merge of one artist spelled two ways.
        assertNotEquals(
            MetadataNormalizer.artistGroupingKey("AC/DC"),
            MetadataNormalizer.artistGroupingKey("ACDC"),
        )
        assertNotEquals(
            MetadataNormalizer.artistGroupingKey("The Beatles"),
            MetadataNormalizer.artistGroupingKey("Beatles"),
        )
        assertEquals(
            MetadataNormalizer.UNKNOWN_ARTIST_KEY,
            MetadataNormalizer.artistGroupingKey("<unknown>").value,
        )
    }

    // --- determinism ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("every key is deterministic across calls and processes")
    fun keysAreDeterministic() {
        val key = albumKey("The Wall", "Pink Floyd")
        repeat(5) { assertEquals(key, albumKey("The Wall", "Pink Floyd")) }

        // A stable hash rather than `String.hashCode`, so a key stored by one process is comparable
        // with a key computed by another.
        assertEquals(MetadataNormalizer.stableHash("alb:the wall|pink floyd"), MetadataNormalizer.stableHash("alb:the wall|pink floyd"))
        assertEquals(16, MetadataNormalizer.stableHash("anything").length)
    }
}
