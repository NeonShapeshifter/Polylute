package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.lifecycle.ViewModel
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** B56 phase 6: a composer's songs, from the repository (the one list Play, Shuffle and the rows queue). */
@HiltViewModel
class ComposerDetailViewModel @Inject constructor(private val music: MusicRepository) : ViewModel() {
    fun songs(composer: String): Flow<List<Song>> = music.getSongsByComposer(composer)
}
