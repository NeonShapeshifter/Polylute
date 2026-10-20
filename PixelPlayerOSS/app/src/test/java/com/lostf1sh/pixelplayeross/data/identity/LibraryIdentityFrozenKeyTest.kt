package com.lostf1sh.pixelplayeross.data.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * FIX-3 (B11-fix): the fingerprint's track and disc must not follow the display decoder.
 *
 * Checked against the owner's library (device backup `device-backup-pre-B11`): all 4870 stored
 * `stable_key`s equal the key made with the original arithmetic (low 12 bits track, next 12 disc); only
 * one equals the key made with the decimal decode that Track Details now uses. 4814 of the 4870 active
 * `library_items` have an empty `file_signature`, and for such an item a changed fingerprint reads as
 * "a different file under a reused number": a new identity, with the favourites, lyrics, bookmarks,
 * playlist memberships and play history left behind on the old one.
 */
class LibraryIdentityFrozenKeyTest {

    @Test
    fun `the fingerprint position is the original arithmetic, not MediaStore's decimal packing`() {
        assertThat(LibraryIdentity.fingerprintPosition(1012)).isEqualTo(1012 to 0)
        assertThat(LibraryIdentity.fingerprintPosition(2021)).isEqualTo(2021 to 0)
        assertThat(LibraryIdentity.fingerprintPosition(2 * 4096 + 1)).isEqualTo(1 to 2)
        assertThat(LibraryIdentity.fingerprintPosition(3)).isEqualTo(3 to 0)
        assertThat(LibraryIdentity.fingerprintPosition(0)).isEqualTo(0 to 0)
        assertThat(LibraryIdentity.fingerprintPosition(-5)).isEqualTo(0 to 0)
    }

    private fun keyFor(duration: Long, size: Long, title: String, artist: String, album: String, raw: Int): String {
        val (track, disc) = LibraryIdentity.fingerprintPosition(raw)
        return LibraryIdentity.computeKey(duration, size, title, artist, album, track, disc)
    }

    @Test
    fun `real stored keys from the owner's library are reproduced exactly`() {
        // Rows from `songs` in the backup: duration, file size, raw MediaStore TRACK, and the stored key.
        assertThat(keyFor(252760, 30124161, "Fernando", "ABBA", "Abba Gold Anniversary Edition", 1012))
            .isEqualTo("109371fd0cf9a021d3fda52b9f9e74893b213cb5")
        assertThat(keyFor(231093, 31376492, "Dancing Queen", "ABBA", "Abba Gold Anniversary Edition", 1001))
            .isEqualTo("33022f7122a018dca50176461cb3cc1976d943a9")
        // A disc-2 row (raw 2021).
        assertThat(keyFor(260507, 33474950, "Angeleyes", "ABBA", "Abba Gold Anniversary Edition", 2021))
            .isEqualTo("595ae1c7ac64540346753717da6457354f18f040")
    }

    @Test
    fun `the decimal decode would have produced different keys, which is the whole hazard`() {
        val frozen = keyFor(252760, 30124161, "Fernando", "ABBA", "Abba Gold Anniversary Edition", 1012)
        val decimal = LibraryIdentity.computeKey(252760, 30124161, "Fernando", "ABBA", "Abba Gold Anniversary Edition", 12, 1)
        assertThat(decimal).isNotEqualTo(frozen)
    }

    @Test
    fun `a changed fingerprint on an item with no recorded file signature is a NEW identity, so the key must never change`() {
        val frozen = keyFor(252760, 30124161, "Fernando", "ABBA", "Abba Gold Anniversary Edition", 1012)
        val decimal = LibraryIdentity.computeKey(252760, 30124161, "Fernando", "ABBA", "Abba Gold Anniversary Edition", 12, 1)
        val signature = LibraryIdentity.computeFileSignature(252760, 30124161)
        val item = LibraryItemId("item-1")

        // As it is today: the stored key is the frozen one, the file signature was never recorded.
        val known = KnownItem(item, mediaStoreId = 7L, fingerprint = frozen, fileSignature = "")

        val unchanged = LibraryIdentityResolver.resolve(
            known = listOf(known),
            scanned = listOf(ScannedItem(7L, fingerprint = frozen, fileSignature = signature)),
            generateId = { "minted" },
        ).single()
        assertThat(unchanged).isInstanceOf(IdentityResolution.Unchanged::class.java)
        assertThat(unchanged.libraryItemId).isEqualTo(item)

        // If the scan's key followed the display decoder: the item is lost, a new one is minted.
        val lost = LibraryIdentityResolver.resolve(
            known = listOf(known),
            scanned = listOf(ScannedItem(7L, fingerprint = decimal, fileSignature = signature)),
            generateId = { "minted" },
        ).single()
        assertThat(lost).isInstanceOf(IdentityResolution.New::class.java)
        assertThat(lost.libraryItemId).isNotEqualTo(item)
    }
}
