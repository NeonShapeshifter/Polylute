package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import com.lostf1sh.pixelplayeross.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class GenrePageInformation @Inject constructor(
    private val musicInfo: MusicInfoRepository,
    private val artistImages: ArtistImageRepository,
    private val preferences: UserPreferencesRepository,
    @AppScope scope: CoroutineScope,
) {
    private val photoRequests = SingleFlight<Long, String?>(scope)

    internal fun description(name: String) =
        combine(preferences.musicInfoDescriptionsEnabledFlow, musicInfo.coverVersion) { enabled, _ -> enabled }
            .mapLatest { enabled -> if (enabled) musicInfo.service.fetchAbout(AboutKind.GENRE, name) else null }

    internal fun featuredPhoto(artist: GenreArtist) =
        combine(preferences.musicInfoArtistPhotosEnabledFlow, musicInfo.coverVersion) { _, _ -> Unit }
            .mapLatest {
                // The shared artist path checks custom and cached files before considering its opt-in.
                photoRequests.run(artist.artistId) {
                    artistImages.getEffectiveArtistImageUrl(artist.artistId, artist.name)
                }
            }
}
