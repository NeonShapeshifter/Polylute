package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFillController
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** B57 1.3: holds the album editor's MusicBrainz fill so it survives a rotation while a request is in flight. */
@HiltViewModel
class AlbumEditViewModel @Inject constructor(api: MusicBrainzApiService) : ViewModel() {
    val fill = AlbumFillController(api, viewModelScope)
}
