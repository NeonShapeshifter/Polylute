package com.lostf1sh.pixelplayeross.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Every tab in the enum must be reachable in the strip.
 *
 * The generated views shipped into `LibraryTabId` while the list the strip actually reads was still
 * written out by hand in the view model, so two whole phases' worth of surface existed, compiled,
 * passed their own tests and could not be opened. This is that bug, written down as an invariant:
 * the default order is *derived* from the enum, and a stored order can only ever add tabs to it,
 * never remove one.
 */
class LibraryTabReachabilityTest {

    @Test
    fun `the default order contains every declared tab`() {
        LibraryTabId.entries.forEach { tab ->
            assertThat(DEFAULT_LIBRARY_TAB_KEYS).contains(tab.storageKey)
        }
        assertThat(DEFAULT_LIBRARY_TAB_KEYS).hasSize(LibraryTabId.entries.size)
    }

    @Test
    fun `a stored order that predates a tab still shows it`() {
        // The shape of every upgrade: the stored order names only the tabs that existed when the
        // user last arranged them.
        val storedFromAnOlderBuild = listOf("SONGS", "ALBUMS")

        val reconciled = reconcileLibraryTabOrder(storedFromAnOlderBuild)

        assertThat(reconciled).containsAtLeast("SONGS", "ALBUMS", "COLLECTIONS").inOrder()
        LibraryTabId.entries.forEach { tab ->
            assertThat(reconciled).contains(tab.storageKey)
        }
    }

    @Test
    fun `the stored arrangement wins where it is still valid`() {
        val stored = listOf("LIKED", "FOLDERS", "SONGS")

        val reconciled = reconcileLibraryTabOrder(stored)

        // The user's own order is preserved for the tabs they arranged...
        assertThat(reconciled.take(3)).containsExactly("LIKED", "FOLDERS", "SONGS").inOrder()
        // ...and the rest are appended rather than interleaved, so their arrangement is not
        // silently rewritten underneath them.
        assertThat(reconciled).hasSize(LibraryTabId.entries.size)
    }

    @Test
    fun `an unreadable stored value falls back to the default order`() {
        assertThat(reconcileLibraryTabOrder(null)).isEqualTo(DEFAULT_LIBRARY_TAB_KEYS)
    }

    @Test
    fun `an unrecognised stored key is dropped rather than rendered`() {
        // A tab this build does not have cannot be drawn, so keeping it would put a blank page in
        // the strip. It is dropped here; the tab strip's own fallback covers the case where a
        // stored id resolves to nothing.
        val reconciled = reconcileLibraryTabOrder(listOf("SONGS", "A_TAB_THAT_NEVER_SHIPPED"))

        assertThat(reconciled).doesNotContain("A_TAB_THAT_NEVER_SHIPPED")
        assertThat(reconciled).hasSize(LibraryTabId.entries.size)
    }
}
