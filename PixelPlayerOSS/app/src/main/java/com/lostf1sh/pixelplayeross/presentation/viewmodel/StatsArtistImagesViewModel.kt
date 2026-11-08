package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * B58 6 (#74): the photos Stats' artist rows show, by the artist's name (trimmed, lower case): what Home shows for the same artists, read from the artists table (the picture the
 * listener chose, else the one already stored). **Nothing is looked up**: no request is made by this screen, and an artist that has no stored picture simply has no entry.
 */
@HiltViewModel
class StatsArtistImagesViewModel @Inject constructor(musicDao: MusicDao) : ViewModel() {
    val images: StateFlow<Map<String, String>> = musicDao.getAllArtistsRaw()
        .map { artists ->
            val byName = HashMap<String, String>(artists.size * 2)
            for (artist in artists) {
                val image = artist.customImageUri?.takeIf { it.isNotBlank() } ?: artist.imageUrl?.takeIf { it.isNotBlank() } ?: continue
                byName.putIfAbsent(artist.name.trim().lowercase(), image)
            }
            byName
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
