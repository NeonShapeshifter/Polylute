package com.lostf1sh.pixelplayeross.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * FIX-3 (B11-fix, device backup `device-backup-pre-B11` against ffprobe): `track_number = 1012` (the
 * packed `disc * 1000 + track`) for 4869 of 4870 songs, shown as "1-1012"; `year = 0` for 4867 of 4870.
 */
class LibraryRepairPolicyTest {

    // --- the packed track ----------------------------------------------------------------------

    @Test
    fun `a packed track is unpacked into disc and track`() {
        assertThat(MetadataNormalizer.unpackStoredPosition(1012, null))
            .isEqualTo(MetadataNormalizer.StoredPosition(track = 12, disc = 1))
        assertThat(MetadataNormalizer.unpackStoredPosition(2021, null))
            .isEqualTo(MetadataNormalizer.StoredPosition(track = 21, disc = 2))
        assertThat(MetadataNormalizer.unpackStoredPosition(1000, null))
            .isEqualTo(MetadataNormalizer.StoredPosition(track = 0, disc = 1))
    }

    @Test
    fun `a disc that is already stored is kept, the track still unpacked`() {
        assertThat(MetadataNormalizer.unpackStoredPosition(1012, 2))
            .isEqualTo(MetadataNormalizer.StoredPosition(track = 12, disc = 2))
    }

    @Test
    fun `an ordinary track is never touched, and the unpack is idempotent`() {
        for (track in listOf(0, 1, 12, 999)) {
            assertThat(MetadataNormalizer.unpackStoredPosition(track, null))
                .isEqualTo(MetadataNormalizer.StoredPosition(track, null))
        }
        // Two passes equal one pass, for every packed value there is.
        for (packed in listOf(1001, 1012, 2003, 4050, 1000)) {
            val once = MetadataNormalizer.unpackStoredPosition(packed, null)
            val twice = MetadataNormalizer.unpackStoredPosition(once.track, once.disc)
            assertThat(twice).isEqualTo(once)
        }
    }

    // --- the merge with a stored value ---------------------------------------------------------

    @Test
    fun `a stored packed value is replaced by what the scan decoded`() {
        // The scan (with the decimal decoder) sees raw 1012 as disc 1, track 12.
        val merged = LibraryRepairPolicy.mergePosition(
            storedTrack = 1012, storedDisc = null, userEdited = false, scannedTrack = 12, scannedDisc = 1,
        )
        assertThat(merged).isEqualTo(MetadataNormalizer.StoredPosition(track = 12, disc = 1))
    }

    @Test
    fun `a row the scan did not edit follows the scan, not the earlier value`() {
        // A file re-tagged from track 3 to track 4: the old "keep any nonzero local value" kept 3.
        val merged = LibraryRepairPolicy.mergePosition(3, 1, userEdited = false, scannedTrack = 4, scannedDisc = 1)
        assertThat(merged.track).isEqualTo(4)
    }

    @Test
    fun `a user edit is respected`() {
        val merged = LibraryRepairPolicy.mergePosition(
            storedTrack = 7, storedDisc = 3, userEdited = true, scannedTrack = 12, scannedDisc = 1,
        )
        assertThat(merged).isEqualTo(MetadataNormalizer.StoredPosition(track = 7, disc = 3))
    }

    @Test
    fun `a packed value an edit copied is still unpacked, a user never typed track 1012`() {
        val merged = LibraryRepairPolicy.mergePosition(
            storedTrack = 1012, storedDisc = null, userEdited = true, scannedTrack = 12, scannedDisc = 1,
        )
        assertThat(merged).isEqualTo(MetadataNormalizer.StoredPosition(track = 12, disc = 1))
    }

    @Test
    fun `the user edit flags are what says a row was edited`() {
        assertThat(LibraryRepairPolicy.isUserEdited(false, false, false, false)).isFalse()
        // The editor sets all four together, so any one of them is an edit.
        for (index in 0 until 4) {
            val flags = BooleanArray(4).also { it[index] = true }
            assertThat(LibraryRepairPolicy.isUserEdited(flags[0], flags[1], flags[2], flags[3])).isTrue()
        }
    }

    // --- the year ------------------------------------------------------------------------------

    @Test
    fun `a year comes out of the tag spellings taggers write`() {
        assertThat(MetadataNormalizer.parseYearTag("1998-01-01")).isEqualTo(1998)
        assertThat(MetadataNormalizer.parseYearTag("1998")).isEqualTo(1998)
        assertThat(MetadataNormalizer.parseYearTag(" 2005 ")).isEqualTo(2005)
        assertThat(MetadataNormalizer.parseYearTag("19980101")).isEqualTo(1998)
        assertThat(MetadataNormalizer.parseYearTag("2001/06")).isEqualTo(2001)
        assertThat(MetadataNormalizer.parseYearTag("Jan 5, 1998")).isEqualTo(1998)
    }

    @Test
    fun `garbage is no year at all`() {
        for (raw in listOf(null, "", "   ", "abcd", "garbage", "0000", "98", "-1", "1", "9999", "1700-01-01")) {
            assertThat(MetadataNormalizer.parseYearTag(raw)).isNull()
        }
    }

    @Test
    fun `a scan with no year does not erase the year read from the file`() {
        assertThat(LibraryRepairPolicy.mergeYear(storedYear = 1998, scannedYear = 0)).isEqualTo(1998)
        assertThat(LibraryRepairPolicy.mergeYear(storedYear = 0, scannedYear = 0)).isEqualTo(0)
        // When MediaStore does have a year it wins.
        assertThat(LibraryRepairPolicy.mergeYear(storedYear = 1998, scannedYear = 2001)).isEqualTo(2001)
    }

    @Test
    fun `MediaStore not knowing a year is not a change`() {
        assertThat(LibraryRepairPolicy.isYearUnchanged(storedYear = 1998, mediaStoreYear = null)).isTrue()
        assertThat(LibraryRepairPolicy.isYearUnchanged(storedYear = 1998, mediaStoreYear = 0)).isTrue()
        assertThat(LibraryRepairPolicy.isYearUnchanged(storedYear = 1998, mediaStoreYear = 1998)).isTrue()
        assertThat(LibraryRepairPolicy.isYearUnchanged(storedYear = 1998, mediaStoreYear = 2001)).isFalse()
    }

    @Test
    fun `an album's year is its most common valid year, not its first track's`() {
        assertThat(MetadataNormalizer.albumYear(listOf(0, 1998, 1998, 1999))).isEqualTo(1998)
        // A tie goes to the earlier year.
        assertThat(MetadataNormalizer.albumYear(listOf(2001, 1999, 2001, 1999))).isEqualTo(1999)
        assertThat(MetadataNormalizer.albumYear(listOf(0, 0, -1))).isEqualTo(0)
        assertThat(MetadataNormalizer.albumYear(emptyList())).isEqualTo(0)
    }

    // --- what the details screen prints --------------------------------------------------------

    @Test
    fun `the track label never prints a packed value`() {
        assertThat(LibraryRepairPolicy.trackLabel(track = 12, disc = 1)).isEqualTo("1-12")
        // A library not yet repaired: the stored value is still the packed one.
        assertThat(LibraryRepairPolicy.trackLabel(track = 1012, disc = null)).isEqualTo("1-12")
        assertThat(LibraryRepairPolicy.trackLabel(track = 2021, disc = null)).isEqualTo("2-21")
        assertThat(LibraryRepairPolicy.trackLabel(track = 1012, disc = 2)).isEqualTo("2-12")
        assertThat(LibraryRepairPolicy.trackLabel(track = 5, disc = null)).isEqualTo("5")
        assertThat(LibraryRepairPolicy.trackLabel(track = 0, disc = 1)).isNull()
    }

    // --- album order, multi-disc ---------------------------------------------------------------

    private data class Row(val title: String, val disc: Int?, val track: Int)

    private val order = LibraryRepairPolicy.albumOrder<Row>({ it.disc }, { it.track }, { it.title })

    @Test
    fun `a multi-disc album lists disc one then disc two, never interleaved`() {
        val shuffled = listOf(
            Row("d2t1", 2, 1), Row("d1t2", 1, 2), Row("d2t2", 2, 2), Row("d1t1", 1, 1), Row("d1t3", 1, 3),
        )
        assertThat(shuffled.sortedWith(order).map { it.title })
            .containsExactly("d1t1", "d1t2", "d1t3", "d2t1", "d2t2").inOrder()
        // The track alone (what two call sites used to sort by) interleaves them.
        assertThat(shuffled.sortedBy { it.track }.map { it.title }.take(2)).containsExactly("d2t1", "d1t1")
    }

    @Test
    fun `no disc counts as the first disc and no track sorts last`() {
        val rows = listOf(Row("untracked", 1, 0), Row("two", null, 2), Row("one", 1, 1), Row("d2", 2, 1))
        assertThat(rows.sortedWith(order).map { it.title })
            .containsExactly("one", "two", "untracked", "d2").inOrder()
    }
}
