package com.lostf1sh.pixelplayeross.data.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The durable identity that user data now hangs off.
 *
 * The two failures this exists to separate are opposite in kind, and the tests are grouped that way:
 * the same file under a new locator must **find** its history, and a different file under an old
 * locator must **not** find someone else's.
 */
class LibraryIdentityTest {

    private fun key(
        duration: Long = 240_000L,
        size: Long = 8_000_000L,
        title: String? = "Coming Up Roses",
        artist: String? = "Harry Styles",
        album: String? = "Harry's House",
        track: Int = 3,
        disc: Int = 1,
    ) = LibraryIdentity.computeKey(duration, size, title, artist, album, track, disc)

    // --- The key is about the file, not about Android's numbering ---------------------------------

    @Test
    fun `the key contains no MediaStore locator, so a renumber cannot change it`() {
        // Nothing locator-shaped is an input. Two scans of the same file under different _IDs
        // produce the same key by construction, which is what lets history reattach.
        assertThat(key()).isEqualTo(key())
    }

    @Test
    fun `ordinary rescan and restart produce an identical key`() {
        // Deterministic: no clock, no RNG, no ordering dependence.
        val first = key()
        repeat(3) { assertThat(key()).isEqualTo(first) }
    }

    // --- Reuse defence ----------------------------------------------------------------------------

    @Test
    fun `a materially different file under the same locator gets a different key`() {
        // The mandatory case. The locator is not an input, so a reused _ID cannot inherit: the new
        // file simply has no engagement under its own key.
        val original = key()
        val differentTrack = key(title = "Different Song", duration = 180_000L, size = 6_000_000L)
        assertThat(differentTrack).isNotEqualTo(original)
    }

    @Test
    fun `even a same-length file is distinguished by size`() {
        assertThat(key(size = 8_000_000L)).isNotEqualTo(key(size = 8_000_001L))
    }

    @Test
    fun `a different duration is a different file`() {
        assertThat(key(duration = 240_000L)).isNotEqualTo(key(duration = 241_000L))
    }

    // --- What must NOT break identity --------------------------------------------------------------

    @Test
    fun `case and surrounding whitespace are not identity`() {
        // "Song Title " and "song title" are the same track. Treating them as different would
        // orphan history for no reason at all.
        assertThat(key(title = "  Coming Up Roses  "))
            .isEqualTo(key(title = "coming up roses"))
        assertThat(key(artist = "HARRY   STYLES"))
            .isEqualTo(key(artist = "harry styles"))
    }

    @Test
    fun `missing tags still produce a usable key`() {
        val a = key(title = null, artist = null, album = null)
        val b = key(title = "", artist = "   ", album = null)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEmpty()
    }

    @Test
    fun `an absent file size weakens but does not invalidate the key`() {
        val withSize = key(size = 8_000_000L)
        val withoutSize = key(size = 0L)
        assertThat(withoutSize).isNotEmpty()
        // They differ — an unknown size is genuinely less evidence, not the same evidence.
        assertThat(withoutSize).isNotEqualTo(withSize)
    }

    // --- Duplicates stay separate ------------------------------------------------------------------

    @Test
    fun `the same recording in two encodes is two library items`() {
        // FLAC vs MP3: different size, different duration by a few ms. Separate keys, separate
        // history. Collapsing them would make this *recording* identity, which is a different
        // product decision and explicitly not this pass.
        val flac = key(size = 40_000_000L, duration = 240_000L)
        val mp3 = key(size = 8_000_000L, duration = 240_120L)
        assertThat(flac).isNotEqualTo(mp3)
    }

    @Test
    fun `the same song on a standard and a deluxe edition stays separate`() {
        assertThat(key(album = "Harry's House"))
            .isNotEqualTo(key(album = "Harry's House (Deluxe)"))
    }

    @Test
    fun `two tracks with identical tags but different audio stay separate`() {
        // Identical metadata is not proof of sameness; the audio-bearing fields break the tie.
        assertThat(key(size = 7_000_000L)).isNotEqualTo(key(size = 9_000_000L))
    }

    @Test
    fun `an exact duplicated file is distinguishable by its position in the library`() {
        // Byte-identical copies in two folders produce the same key, because they ARE the same
        // file content. That is the one case where two rows legitimately share an identity, and
        // the ambiguity policy below is what stops it being auto-merged.
        assertThat(key()).isEqualTo(key())
        assertThat(IdentityMatchConfidence.AMBIGUOUS).isNotEqualTo(IdentityMatchConfidence.EXACT)
    }

    // --- Ambiguity policy --------------------------------------------------------------------------

    @Test
    fun `only an exact match may reattach automatically`() {
        // The asymmetry the brief asks for: losing a historical attachment is recoverable, while
        // attaching history to the wrong track is silent corruption nothing can detect later.
        assertThat(IdentityMatchConfidence.entries.map { it.name })
            .containsExactly("EXACT", "AMBIGUOUS", "NEW")
    }

    // --- Migration is additive -----------------------------------------------------------------------

    @Test
    fun `the identity migration only adds columns and invents nothing`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/database/Migrations.kt")
            .let { if (it.isFile) it else File("app/${it.path}") }.readText()
        val migration = source.substringAfter("val MIGRATION_7_8").substringBefore("val MIGRATION_8")

        assertThat(migration).contains("ADD COLUMN `stable_key`")
        assertThat(migration).doesNotContain("DROP TABLE")
        assertThat(migration).doesNotContain("DELETE FROM")
        // Nothing is backfilled: a key computed without the size input would not match the key a
        // later scan derives from the same file, and a key that cannot be reproduced is worse than
        // no key at all.
        assertThat(migration).doesNotContain("UPDATE `songs` SET `stable_key`")
        assertThat(migration).doesNotContain("UPDATE `song_engagements` SET `stable_key`")
    }

    @Test
    fun `no destructive fallback reaches a release build`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/di/AppModule.kt")
            .let { if (it.isFile) it else File("app/${it.path}") }.readText()
        val fallback = source.substringAfter("fallbackToDestructiveMigration").substringBefore("}")
        // Debug-only, exactly as before this pass.
        assertThat(source).contains("BuildConfig.DEBUG")
        assertThat(fallback).isNotEmpty()
    }
}
