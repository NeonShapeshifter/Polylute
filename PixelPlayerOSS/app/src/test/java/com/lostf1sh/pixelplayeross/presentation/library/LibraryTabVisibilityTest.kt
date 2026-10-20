package com.lostf1sh.pixelplayeross.presentation.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Library tab visibility rule.
 *
 * The product promise is that hiding a tab is reversible and loses nothing, so these are about the
 * invariants rather than about any particular tab.
 */
class LibraryTabVisibilityTest {

    private val all = listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED")

    @Test
    fun `nothing hidden shows every tab in order`() {
        assertThat(resolveVisibleTabs(all, emptySet())).isEqualTo(all)
    }

    @Test
    fun `hiding removes only the hidden tabs`() {
        val visible = resolveVisibleTabs(all, setOf("PLAYLISTS", "LIKED"))
        assertThat(visible).isEqualTo(listOf("SONGS", "ALBUMS", "ARTIST", "FOLDERS"))
    }

    @Test
    fun `restoring returns the tab at its original position`() {
        val hidden = resolveVisibleTabs(all, setOf("ALBUMS"))
        assertThat(hidden).isEqualTo(listOf("SONGS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED"))

        // Turning it back on restores the original order, because the order record is untouched.
        assertThat(resolveVisibleTabs(all, emptySet())).isEqualTo(all)
    }

    @Test
    fun `hiding every tab falls back to the first rather than showing nothing`() {
        val visible = resolveVisibleTabs(all, all.toSet())
        assertThat(visible).isEqualTo(listOf("SONGS"))
    }

    @Test
    fun `unknown or stale hidden ids are ignored`() {
        val visible = resolveVisibleTabs(all, setOf("SOMETHING_REMOVED", "ALSO_UNKNOWN"))
        assertThat(visible).isEqualTo(all)
    }

    @Test
    fun `an empty order yields an empty result`() {
        assertThat(resolveVisibleTabs(emptyList(), setOf("SONGS"))).isEmpty()
    }
}
