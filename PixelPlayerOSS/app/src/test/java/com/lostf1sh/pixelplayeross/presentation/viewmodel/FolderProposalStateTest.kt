package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders
import org.junit.jupiter.api.Test

/** B39-A9: every proposed folder starts chosen; unchecking takes its songs out of the total, checking again puts them back. */
class FolderProposalStateTest {
    private val state = FolderProposalState(
        listOf(LibraryFolders.Proposal("/a", 10), LibraryFolders.Proposal("/b", 5), LibraryFolders.Proposal("/c", 1)),
    )

    @Test
    fun `everything starts chosen`() {
        assertThat(state.chosen).containsExactly("/a", "/b", "/c").inOrder()
        assertThat(state.chosenSongs).isEqualTo(16)
        assertThat(state.isChosen("/b")).isTrue()
    }

    @Test
    fun `unchecking and checking again move a folder out of and back into the total`() {
        val without = state.toggle("/b")
        assertThat(without.chosen).containsExactly("/a", "/c").inOrder()
        assertThat(without.chosenSongs).isEqualTo(11)
        assertThat(without.isChosen("/b")).isFalse()
        assertThat(without.toggle("/b")).isEqualTo(state)
    }

    @Test
    fun `unchecking all leaves nothing to add`() {
        val none = state.toggle("/a").toggle("/b").toggle("/c")
        assertThat(none.chosen).isEmpty()
        assertThat(none.chosenSongs).isEqualTo(0)
    }
}
