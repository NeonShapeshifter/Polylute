package com.lostf1sh.pixelplayeross.data.identity

/**
 * What a scan should do about identity, decided before anything is destroyed.
 *
 * This is the piece that was missing. [LibraryIdentityResolver] answers *"is this scanned row a
 * library item we already knew?"* — a decision about one row. This answers the question the sync
 * actually has to act on: **which existing items survive, which are reattached, which are new, and
 * which are genuinely gone.**
 *
 * It exists as a separate, pure step because the original defect was an *ordering* defect: sync
 * computed a set difference and deleted the disappeared rows before anything had a chance to
 * recognise them under a new locator. Making the decision a value that is computed first, and acted
 * on afterwards, is what makes that ordering enforceable rather than a matter of remembering where
 * the delete statement is.
 */
data class SyncIdentityPlan(
    /** Rows whose locator is unchanged. Nothing to do beyond refreshing evidence. */
    val unchanged: List<IdentityResolution.Unchanged>,

    /**
     * Rows recognised under a **new** locator. Their user state must follow them.
     *
     * Applying these before any deletion is the entire point: the logical item is the same one, so
     * its engagement, and whatever else hangs off its identity, was never stale.
     */
    val reattached: List<IdentityResolution.Reattached>,

    /** Rows with a fresh identity. */
    val created: List<IdentityResolution.New>,

    /**
     * Rows that could not be safely attributed. They get a new identity; no old state is inherited.
     */
    val ambiguous: List<IdentityResolution.Ambiguous>,

    /**
     * Known items that are absent from this scan and matched nothing.
     *
     * **This is deliberately not a delete list.** An item being absent means it was not seen this
     * time — which is what an unmounted volume, a revoked permission or an in-progress MediaStore
     * rebuild all look like. Destroying history on that evidence would lose a listener's records
     * every time a card was ejected. These items keep their identity and their history, and simply
     * stop being present.
     */
    val absent: List<KnownItem>,
) {
    /** Identities that must be preserved across this sync. Used to prove ordering in tests. */
    val preservedIdentities: Set<String>
        get() = (unchanged.map { it.libraryItemId } + reattached.map { it.libraryItemId })
            .map { it.value }
            .toSet()

    /** Identities minted by this sync. Never overlaps [preservedIdentities]. */
    val mintedIdentities: Set<String>
        get() = (created.map { it.libraryItemId } + ambiguous.map { it.libraryItemId })
            .map { it.value }
            .toSet()

    val reattachmentCount: Int get() = reattached.size
    val ambiguousCount: Int get() = ambiguous.size
    val newIdentityCount: Int get() = created.size + ambiguous.size

    companion object {
        /**
         * Builds the plan. Pure — the caller supplies identity generation, so a test can name them.
         */
        fun build(
            known: List<KnownItem>,
            scanned: List<ScannedItem>,
            generateId: () -> String,
        ): SyncIdentityPlan {
            val resolutions = LibraryIdentityResolver.resolve(known, scanned, generateId)
            val matchedIdentities = resolutions
                .filter { it.retainsIdentity }
                .mapTo(mutableSetOf()) { it.libraryItemId }

            return SyncIdentityPlan(
                unchanged = resolutions.filterIsInstance<IdentityResolution.Unchanged>(),
                reattached = resolutions.filterIsInstance<IdentityResolution.Reattached>(),
                created = resolutions.filterIsInstance<IdentityResolution.New>(),
                ambiguous = resolutions.filterIsInstance<IdentityResolution.Ambiguous>(),
                // Absent means "no longer a live library item", which is decided by whether its
                // identity was claimed — not by whether its number vanished.
                //
                // The distinction matters for locator reuse: when Android hands number 11 to a
                // different file, 11 is still present in the scan, yet the item that used to live
                // there is gone. Testing the locator alone would miss it entirely and leave a dead
                // item looking present.
                absent = known.filter { it.libraryItemId !in matchedIdentities },
            )
        }
    }
}
