package com.lostf1sh.pixelplayeross.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Optional lookup hints read from tags. They never participate in library identity or grouping. */
@Parcelize
data class MusicBrainzIds(
    val recording: String? = null,
    val release: String? = null,
    val releaseGroup: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
) : Parcelable
