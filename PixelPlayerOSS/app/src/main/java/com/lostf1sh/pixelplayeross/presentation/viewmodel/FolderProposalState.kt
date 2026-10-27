package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders

/**
 * B39-A9: folders found with music, offered to the listener, every one already chosen. Shared by the first run and by "Find folders with
 * music" in Settings, Library.
 */
@Immutable
data class FolderProposalState(
    val folders: List<LibraryFolders.Proposal>,
    val unchecked: Set<String> = emptySet(),
) {
    /** The folders that will be added. */
    val chosen: List<String> get() = folders.map { it.path }.filterNot { it in unchecked }

    /** How many songs the chosen folders hold. */
    val chosenSongs: Int get() = folders.filter { it.path !in unchecked }.sumOf { it.songCount }

    fun isChosen(path: String): Boolean = path !in unchecked

    fun toggle(path: String): FolderProposalState =
        if (path in unchecked) copy(unchecked = unchecked - path) else copy(unchecked = unchecked + path)
}

/** What looking for folders tells the listener outside the dialog (a toast). */
enum class FolderSearchNotice { SEARCHING, NONE_FOUND, NOT_READY }
