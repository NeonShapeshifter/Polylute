package com.lostf1sh.pixelplayeross.presentation.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionRepository
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionsState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The automatic collections for the Playlists list and their page (B28).
 *
 * A thin window onto [AutoCollectionRepository]: opening a screen that shows them asks for a build only if what is held is old, off
 * the main thread, and never rebuilds because a track ended; hiding and showing are the only things a collection can be asked to do.
 */
@HiltViewModel
class AutoCollectionsViewModel @Inject constructor(
    private val repository: AutoCollectionRepository,
) : ViewModel() {

    val state: StateFlow<AutoCollectionsState> = repository.state

    init {
        viewModelScope.launch { repository.ensureFresh() }
    }

    fun hide(id: AutoCollectionId) = repository.hide(id)

    fun show(id: AutoCollectionId) = repository.show(id)

    fun showAllHidden() = repository.showAllHidden()
}
