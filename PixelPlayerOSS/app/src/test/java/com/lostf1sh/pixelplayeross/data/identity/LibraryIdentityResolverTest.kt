package com.lostf1sh.pixelplayeross.data.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The end-to-end identity cases, at the level the whole pass exists for.
 *
 * Each of these is a scenario the previous implementation got wrong or could not express at all.
 * They are pure — the resolver takes lists and returns decisions — so the behaviour that would
 * otherwise require inducing a real MediaStore renumber on a device is assertable here.
 */
class LibraryIdentityResolverTest {

    private fun known(
        identity: String,
        locator: Long,
        fingerprint: String = "F",
        fileSignature: String = "S",
    ) = KnownItem(LibraryItemId(identity), locator, fingerprint, fileSignature)

    private fun scanned(
        locator: Long,
        fingerprint: String = "F",
        fileSignature: String = "S",
    ) = ScannedItem(locator, fingerprint, fileSignature)

    /** Deterministic id source so assertions can name the minted identities. */
    private fun ids(vararg values: String): () -> String {
        var index = 0
        return { values[index++] }
    }

    // --- A. RENUMBER ------------------------------------------------------------------------------

    @Test
    fun `A - a renumbered track keeps its identity and therefore its history`() {
        val existing = listOf(known(identity = "L1", locator = 11L, fingerprint = "F1"))
        val scan = listOf(scanned(locator = 22L, fingerprint = "F1"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids("NEVER")).single() as IdentityResolution.Reattached
        assertThat(result.libraryItemId.value).isEqualTo("L1")
        assertThat(result.previousMediaStoreId).isEqualTo(11L)
        assertThat(result.mediaStoreId).isEqualTo(22L)
        // No new identity was minted: history under L1 stays attached to this row.
        assertThat(result.retainsIdentity).isTrue()
    }

    @Test
    fun `A - reattachment does not require the locator to be anywhere near the old one`() {
        val existing = listOf(known("L1", locator = 5L, fingerprint = "F1"))
        val scan = listOf(scanned(locator = 999_999L, fingerprint = "F1"))
        val result = LibraryIdentityResolver.resolve(existing, scan, ids()).single()
            as IdentityResolution.Reattached
        assertThat(result.retainsIdentity).isTrue()
    }

    // --- B. LOCATOR REUSE -------------------------------------------------------------------------

    @Test
    fun `B - a reused locator does not inherit the previous track's identity`() {
        // Android handed number 11 to a completely different file. Different audio, so different
        // file signature — which is the evidence that makes this decidable at all.
        val existing = listOf(known("L1", locator = 11L, fingerprint = "F1", fileSignature = "S1"))
        val scan = listOf(scanned(locator = 11L, fingerprint = "F2", fileSignature = "S2"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids("L2")).single()
            as IdentityResolution.New
        assertThat(result.libraryItemId.value).isEqualTo("L2")
        assertThat(result.retainsIdentity).isFalse()
        // The old item's engagement is keyed by L1 and is never consulted for this row.
        assertThat(result.libraryItemId.value).isNotEqualTo("L1")
    }

    @Test
    fun `B - a reused locator is not matched to some other missing item either`() {
        // L1 is gone AND the reused locator holds different audio. Even though a missing candidate
        // exists, its evidence does not match, so nothing is attached.
        val existing = listOf(
            known("L1", locator = 11L, fingerprint = "F1", fileSignature = "S1"),
        )
        val scan = listOf(scanned(locator = 11L, fingerprint = "F9", fileSignature = "S9"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids("L2")).single()
            as IdentityResolution.New
        assertThat(result).isInstanceOf(IdentityResolution.New::class.java)
    }

    // --- C. AMBIGUITY ------------------------------------------------------------------------------

    @Test
    fun `C - two identical files do not let a new row pick one arbitrarily`() {
        // L1 and L2 are byte-identical copies. A third appears with the same evidence. Choosing
        // either would be a guess, and a wrong guess silently hands one item's history to another.
        val existing = listOf(
            known("L1", locator = 11L, fingerprint = "F", fileSignature = "S"),
            known("L2", locator = 12L, fingerprint = "F", fileSignature = "S"),
        )
        val scan = listOf(scanned(locator = 33L, fingerprint = "F", fileSignature = "S"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids("L3")).single() as IdentityResolution.Ambiguous
        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.libraryItemId.value).isEqualTo("L3")
        assertThat(result.retainsIdentity).isFalse()
    }

    @Test
    fun `C - a single candidate still reattaches, so ambiguity is not over-triggered`() {
        val existing = listOf(
            known("L1", locator = 11L, fingerprint = "F"),
            known("L2", locator = 12L, fingerprint = "OTHER"),
        )
        val scan = listOf(scanned(locator = 33L, fingerprint = "F"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids()).single()
        assertThat(result).isInstanceOf(IdentityResolution.Reattached::class.java)
    }

    // --- E. METADATA UPDATE OF A KNOWN ITEM -------------------------------------------------------

    @Test
    fun `E - a re-tagged file keeps its identity and only its evidence moves`() {
        // Same locator, same audio, edited tags. The fingerprint changes because tags are inputs to
        // it — that is what a tag edit is — but the file itself did not, so the identity holds and
        // only the matching evidence is refreshed.
        val existing = listOf(known("L1", locator = 11L, fingerprint = "F1", fileSignature = "S1"))
        val scan = listOf(scanned(locator = 11L, fingerprint = "F2", fileSignature = "S1"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids("NEVER")).single() as IdentityResolution.Unchanged
        assertThat(result.libraryItemId.value).isEqualTo("L1")
        assertThat(result.fingerprintUpdated).isTrue()
        assertThat(result.fingerprint).isEqualTo("F2")
    }

    @Test
    fun `E - an untouched file is unchanged and its evidence is not rewritten`() {
        val existing = listOf(known("L1", locator = 11L, fingerprint = "F1"))
        val scan = listOf(scanned(locator = 11L, fingerprint = "F1"))

        val result = LibraryIdentityResolver.resolve(existing, scan, ids()).single()
            as IdentityResolution.Unchanged
        assertThat(result.fingerprintUpdated).isFalse()
    }

    // --- D. SESSION ACROSS SYNC -------------------------------------------------------------------

    @Test
    fun `D - identity survives a sync, so a session can still resolve to its own track`() {
        // A session capturing L1 must find L1 again after the scan moved it to a new locator.
        // This is the resolver half of that guarantee: the mapping A -> L1 becomes B -> L1.
        val before = LibraryIdentityResolver.resolve(
            known = listOf(known("L1", locator = 11L, fingerprint = "F1")),
            scanned = listOf(scanned(locator = 11L, fingerprint = "F1")),
            generateId = ids(),
        ).single() as IdentityResolution.Unchanged
        assertThat(before.libraryItemId.value).isEqualTo("L1")

        val after = LibraryIdentityResolver.resolve(
            known = listOf(known("L1", locator = 11L, fingerprint = "F1")),
            scanned = listOf(scanned(locator = 77L, fingerprint = "F1")),
            generateId = ids("NEVER"),
        ).single() as IdentityResolution.Reattached

        assertThat(after.libraryItemId.value).isEqualTo("L1")
        assertThat(after.mediaStoreId).isEqualTo(77L)
    }

    // --- Structural guarantees ----------------------------------------------------------------------

    @Test
    fun `one identity is never claimed by two rows in the same scan`() {
        // Two scanned rows carrying identical evidence, with only one missing candidate between
        // them. The first takes it; the second must not also claim it.
        val existing = listOf(known("L1", locator = 11L, fingerprint = "F"))
        val scan = listOf(
            scanned(locator = 50L, fingerprint = "F"),
            scanned(locator = 51L, fingerprint = "F"),
        )

        val results = LibraryIdentityResolver.resolve(existing, scan, ids("L2"))
        val identities = results.map { resolution -> resolution.identityValue() }

        assertThat(identities).containsExactly("L1", "L2")
        assertThat(identities.toSet()).hasSize(2)
    }

    @Test
    fun `an item still present under its own locator is never stolen by another row`() {
        val existing = listOf(known("L1", locator = 11L, fingerprint = "F"))
        val scan = listOf(
            scanned(locator = 11L, fingerprint = "F"), // still here, under its own locator
            scanned(locator = 60L, fingerprint = "F"), // an unrelated duplicate
        )

        val results = LibraryIdentityResolver.resolve(existing, scan, ids("L2"))
        val byLocator = results.associateBy { it.mediaStoreId }

        assertThat(byLocator[11L]!!.identityValue()).isEqualTo("L1")
        assertThat(byLocator[60L]!!.identityValue()).isEqualTo("L2")
    }

    @Test
    fun `every scanned row leaves the resolver with exactly one identity`() {
        val existing = listOf(
            known("L1", locator = 11L, fingerprint = "F1"),
            known("L2", locator = 12L, fingerprint = "F2"),
        )
        val scan = listOf(
            scanned(11L, "F1"),
            scanned(12L, "F2"),
            scanned(13L, "F3"),
            scanned(14L, "F1"),
        )

        val results = LibraryIdentityResolver.resolve(existing, scan, ids("N1", "N2"))
        assertThat(results).hasSize(4)
        assertThat(results.map { resolution -> resolution.identityValue() }.toSet()).hasSize(4)
    }

    /** The identity a resolution settled on, whatever kind of resolution it was. */
    private fun IdentityResolution.identityValue(): String = when (this) {
        is IdentityResolution.Unchanged -> libraryItemId.value
        is IdentityResolution.Reattached -> libraryItemId.value
        is IdentityResolution.New -> libraryItemId.value
        is IdentityResolution.Ambiguous -> libraryItemId.value
    }
}
