package com.lostf1sh.pixelplayeross.data.identity

/**
 * The three things a song can be identified by, kept strictly apart.
 *
 * Conflating any two of these is what produced the original defect, so they are separate types
 * rather than three strings that happen to travel together.
 *
 * * **Locator** — Android's MediaStore `_ID`. Stable for as long as Android says so, and no longer.
 *   It is how a scan finds a row, never what the row *is*.
 * * **Fingerprint** — deterministic evidence derived from scanned file metadata. Cheap, may be
 *   ambiguous, and legitimately changes when the underlying file materially changes. It is
 *   *matching evidence*.
 * * **Library item id** — an opaque, PixelPlayer-owned identifier, generated once and never
 *   recomputed from metadata. It is the identity. User state hangs off this and nothing else.
 *
 * The earlier implementation used the fingerprint *as* the identity, which is subtly wrong: a
 * fingerprint can be shared by two byte-identical files, and it changes when a file is re-tagged.
 * Identity must survive both.
 */
@JvmInline
value class LibraryItemId(val value: String) {
    val isAssigned: Boolean get() = value.isNotEmpty()
}

/** A library item as the database currently knows it. */
data class KnownItem(
    val libraryItemId: LibraryItemId,
    val mediaStoreId: Long,
    val fingerprint: String,
    val fileSignature: String,
)

/** A row the current scan found in MediaStore. */
data class ScannedItem(
    val mediaStoreId: Long,
    val fingerprint: String,
    val fileSignature: String,
)

/**
 * What the resolver decided about one scanned row.
 *
 * [retainsIdentity] is the property that matters downstream: it is true only where the app is
 * confident this scanned row **is** a library item it already knew, and therefore where user state
 * must follow the reattachment rather than being abandoned.
 */
sealed interface IdentityResolution {
    val mediaStoreId: Long
    val fingerprint: String

    /** The identity this scanned row settled on. */
    val libraryItemId: LibraryItemId

    /**
     * Whether this row is a library item we already knew.
     *
     * Hoisted onto the interface because it is the property every caller actually branches on, and
     * reading it through a `when` at every call site invites exactly the kind of per-caller
     * reinterpretation that produced the original defect.
     */
    val retainsIdentity: Boolean

    /** The locator is unchanged and still refers to the same library item. */
    data class Unchanged(
        override val mediaStoreId: Long,
        override val fingerprint: String,
        override val libraryItemId: LibraryItemId,
        /** True when the file changed materially but is still the same known item. */
        val fingerprintUpdated: Boolean,
    ) : IdentityResolution {
        override val retainsIdentity: Boolean get() = true
    }

    /**
     * The item was re-identified under a different locator — a MediaStore renumber.
     *
     * This is the case the whole pass exists for: the locator changed and the identity did not.
     */
    data class Reattached(
        override val mediaStoreId: Long,
        override val fingerprint: String,
        override val libraryItemId: LibraryItemId,
        val previousMediaStoreId: Long,
    ) : IdentityResolution {
        override val retainsIdentity: Boolean get() = true
    }

    /** Genuinely new: nothing known matches, so a fresh identity is minted. */
    data class New(
        override val mediaStoreId: Long,
        override val fingerprint: String,
        override val libraryItemId: LibraryItemId,
    ) : IdentityResolution {
        override val retainsIdentity: Boolean get() = false
    }

    /**
     * Candidates exist but cannot be told apart — the duplicate case.
     *
     * The brief is explicit and this follows it: **do not choose arbitrarily.** The row becomes a
     * new library item, and the existing items keep their own history. Nothing is merged, so nothing
     * is mis-attributed, and the cost is a history that stays detached rather than one that is
     * wrong.
     */
    data class Ambiguous(
        override val mediaStoreId: Long,
        override val fingerprint: String,
        override val libraryItemId: LibraryItemId,
        val candidateCount: Int,
    ) : IdentityResolution {
        override val retainsIdentity: Boolean get() = false
    }
}

/**
 * Decides, for one scan, which scanned rows are known library items and which are new.
 *
 * Pure: no database, no Android, no clock, no randomness except through the injected
 * [generateId]. Every rule the brief specifies is therefore an assertable function call rather than
 * something observable only after a device renumber.
 *
 * ## The matching rules
 *
 * * A **currently-known locator** wins outright. The scan found the same `_ID` we already had, so
 *   this is the same item — even if its fingerprint moved, which happens when a file is re-tagged.
 *   Importantly this is *not* how a reused locator inherits: the fingerprint comparison below is
 *   what decides whether the item behind that locator is still the same one.
 * * A **locator we have never seen** is matched by fingerprint against items whose locator has
 *   disappeared from this scan. Exactly one candidate is [IdentityResolution.Reattached]; two or
 *   more is [IdentityResolution.Ambiguous]; none is [IdentityResolution.New].
 */
object LibraryIdentityResolver {

    fun resolve(
        known: List<KnownItem>,
        scanned: List<ScannedItem>,
        generateId: () -> String,
    ): List<IdentityResolution> {
        val scannedLocators = scanned.mapTo(mutableSetOf()) { it.mediaStoreId }

        // Items whose locator is gone from this scan are the only legitimate reattachment
        // candidates. An item still present under its own locator is not a candidate for a
        // different row — that would let two live rows claim one identity.
        val missing = known.filter { it.mediaStoreId !in scannedLocators }

        // Locators that are present *and* still describe the same file. A known locator whose
        // fingerprint has materially changed is NOT a match for the item previously behind it: that
        // is the locator-reuse case, and the item must not inherit.
        val byLocator = known.associateBy { it.mediaStoreId }

        val claimed = mutableSetOf<LibraryItemId>()
        val results = mutableListOf<IdentityResolution>()

        for (row in scanned) {
            val sameLocator = byLocator[row.mediaStoreId]
            if (sameLocator != null) {
                val sameFile = sameLocator.fileSignature == row.fileSignature
                val sameEvidence = sameLocator.fingerprint == row.fingerprint
                // Two very different situations arrive as "same locator, different fingerprint",
                // and telling them apart is the whole difficulty:
                //
                //   * the file was re-tagged — same audio, edited metadata. Same item.
                //   * the locator now points at a different file — Android reused the number.
                //     Different item, and it must not inherit.
                //
                // The discriminator is file-intrinsic evidence (duration + size), which a tag edit
                // does not move and a different recording does. Tag-derived fields cannot make
                // this call, because a tag edit is exactly what changes them.
                if (sameEvidence || sameFile) {
                    claimed += sameLocator.libraryItemId
                    results += IdentityResolution.Unchanged(
                        mediaStoreId = row.mediaStoreId,
                        fingerprint = row.fingerprint,
                        libraryItemId = sameLocator.libraryItemId,
                        fingerprintUpdated = !sameEvidence,
                    )
                    continue
                }

                // No evidence was ever recorded for this item, so nothing here *contradicts* the
                // locator. That state is reachable: `MIGRATION_7_8` deliberately left `stable_key`
                // empty rather than invent a key it could not reproduce, and a scan only fills it
                // for files it actually reprocesses.
                //
                // Minting a new identity here would orphan the user's favourites, lyrics and
                // history for every such row in one scan — and it would do so on the strength of a
                // missing value, not a conflicting one. Refusing to act on an absence is the same
                // rule the rest of this phase follows for absent *items*.
                if (sameLocator.fingerprint.isEmpty() && sameLocator.fileSignature.isEmpty()) {
                    claimed += sameLocator.libraryItemId
                    results += IdentityResolution.Unchanged(
                        mediaStoreId = row.mediaStoreId,
                        fingerprint = row.fingerprint,
                        libraryItemId = sameLocator.libraryItemId,
                        fingerprintUpdated = true,
                    )
                    continue
                }
                // Different audio under a reused number: deliberately falls through to matching,
                // where it will find nothing and be minted as a new item. The item that used to
                // live here keeps its own history, dormant.
            }

            // Either an unseen locator, or a known locator whose evidence no longer matches what was
            // there before. Both go to fingerprint matching, which is what defends against reuse.
            val candidates = missing.filter { it.fingerprint == row.fingerprint && it.libraryItemId !in claimed }

            when {
                candidates.size == 1 -> {
                    val match = candidates.first()
                    claimed += match.libraryItemId
                    results += IdentityResolution.Reattached(
                        mediaStoreId = row.mediaStoreId,
                        fingerprint = row.fingerprint,
                        libraryItemId = match.libraryItemId,
                        previousMediaStoreId = match.mediaStoreId,
                    )
                }

                candidates.size > 1 -> {
                    // Two byte-identical files. Refusing to choose is the whole point.
                    results += IdentityResolution.Ambiguous(
                        mediaStoreId = row.mediaStoreId,
                        fingerprint = row.fingerprint,
                        libraryItemId = LibraryItemId(generateId()),
                        candidateCount = candidates.size,
                    )
                }

                else -> {
                    // A known locator whose evidence changed is still the same *row* in the library
                    // sense — Android reused a number, but we minted an identity for the file that
                    // was there. It is gone; this is something new.
                    results += IdentityResolution.New(
                        mediaStoreId = row.mediaStoreId,
                        fingerprint = row.fingerprint,
                        libraryItemId = LibraryItemId(generateId()),
                    )
                }
            }
        }

        return results
    }
}
