package com.lostf1sh.pixelplayeross.data.collections.auto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TestDispatcherProvider
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B55AlbumDayStabilityTest {
    private val now=Kit.at(2026,11,2,20)
    private fun library(ids:List<Long>):List<Song> = ids.flatMap { album -> (1L..3L).map { track ->
        Kit.song(album*100+track,albumId=album).copy(album="Synthetic album $album",dateAdded=album)
    } }
    private fun ids(repo:AutoCollectionRepository):List<Long> {
        val state=repo.homeMixes.value
        @Suppress("UNCHECKED_CAST")
        val albums=state.javaClass.methods.firstOrNull { it.name=="getSuggestedAlbums" }?.invoke(state) as? List<Album> ?: emptyList()
        return albums.map { it.id }
    }
    private suspend fun TestScope.withRepository(initial:List<Song>,block:suspend (AutoCollectionRepository,(List<Song>)->Unit,()->Unit)->Unit) {
        val directory=Files.createTempDirectory("b55-album-day").toFile()
        var instant=now
        fun ledger(songs:List<Song>)=ListeningLedger.build(emptyList(),Kit.catalog(songs,firstSeen=songs.associate { it.libraryItemId to now-10*Kit.DAY }))
        var snapshot=ledger(initial)
        val provider=object:LedgerProvider { override val changes=emptyFlow<Long>();override suspend fun ledger()=snapshot }
        val prefs=UserPreferencesRepository(PreferenceDataStoreFactory.create(scope=backgroundScope,produceFile={directory.resolve("settings.preferences_pb")}),Json)
        val music=mockk<MusicRepository>(relaxed=true)
        coEvery { music.getSongIdsSorted(any(),any()) } returns emptyList()
        val repo=AutoCollectionRepository(provider,prefs,music,LibraryChangeSignal(),TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),backgroundScope)
        repo.clock={instant};repo.zone={Kit.zone}
        try {
            repo.refresh("test");runCurrent()
            block(repo,{snapshot=ledger(it)},{instant+=Kit.DAY})
        } finally { directory.deleteRecursively() }
    }
    @Test fun `a library change preserves the chosen albums for the day and the next day can choose newly added albums`():Unit=runTest {
        withRepository(library((1L..15L).toList())) { repo,replace,nextDay ->
            val first=ids(repo);assertThat(first).hasSize(10)
            replace(library((1L..15L).toList()+100L));repo.refresh("library changed");runCurrent()
            assertThat(ids(repo)).isEqualTo(first)
            nextDay();repo.refreshIfDayChanged();runCurrent()
            assertThat(ids(repo).first()).isEqualTo(100L)
        }
    }
    @Test fun `a previously empty library can fill its daily albums once then holds them through later imports`():Unit=runTest {
        withRepository(emptyList()) { repo,replace,_ ->
            assertThat(ids(repo)).isEmpty()
            replace(library((1L..15L).toList()));repo.refresh("library changed");runCurrent()
            val first=ids(repo);assertThat(first).hasSize(10)
            replace(library((1L..15L).toList()+100L));repo.refresh("library changed");runCurrent()
            assertThat(ids(repo)).isEqualTo(first)
        }
    }
}
