package com.lostf1sh.pixelplayeross.data.collections.auto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TestDispatcherProvider
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalCoroutinesApi::class)
class B55HomeRegressionTest {
    private val now=Kit.at(2026,11,2,20)
    private fun mature(): ListeningLedger {
        val songs=SyntheticHistory.songs(800)
        return ListeningLedger.build(SyntheticHistory.events(3000,800,now,70),Kit.catalog(songs,firstSeen=songs.associate { it.libraryItemId to now-300*Kit.DAY }))
    }
    @Test fun `Suggestions never includes On repeat on a mature library`(): Unit {
        val ledger=mature();val sources=AutoCollectionEngine.compute(ledger,now,Kit.zone)
        val day=AutoCollectionRepository.localEpochDay(now,Kit.zone)
        val daily=AutoCollectionEngine.dailyMix(ledger,sources,day)
        val suggestions=AutoCollectionEngine.dailyMix(ledger,sources,day,yours=true,excluding=daily.items.mapTo(hashSetOf()) { it.identity })
        val ids=suggestions.items.map { it.identity }.toSet()
        assertThat(ids.intersect(sources[AutoCollectionId.ON_REPEAT]!!.items.map { it.identity }.toSet())).isEmpty()
        assertThat(ids.intersect(daily.items.map { it.identity }.toSet())).isEmpty()
        assertThat(ids).hasSize(60)
        println("B55 Suggestions sourceCounts=${suggestions.javaClass.methods.firstOrNull { it.name == "getSourceCounts" }?.invoke(suggestions)}")
        println("B55 Suggestions mature size=${ids.size} new=${ids.intersect(sources[AutoCollectionId.NEW_UNEXPLORED]!!.items.map { it.identity }.toSet()).size} repeat=0")
    }
    @Test fun `Suggestions starts with the approved four shares and fills Deep cuts first`(): Unit {
        val ledger=mature();val items=ledger.catalog.items
        val sources=linkedMapOf(
            AutoCollectionId.NEW_UNEXPLORED to AutoCollection(AutoCollectionId.NEW_UNEXPLORED,items.subList(0,120)),
            AutoCollectionId.DEEP_CUTS to AutoCollection(AutoCollectionId.DEEP_CUTS,items.subList(120,240)),
            AutoCollectionId.NEVER_PLAYED to AutoCollection(AutoCollectionId.NEVER_PLAYED,items.subList(240,360)),
            AutoCollectionId.REDISCOVER to AutoCollection(AutoCollectionId.REDISCOVER,items.subList(360,480)),
            AutoCollectionId.ON_REPEAT to AutoCollection(AutoCollectionId.ON_REPEAT,items.subList(480,600)),
        )
        fun counts(input:Map<AutoCollectionId,AutoCollection>): List<Int> {
            val ids=AutoCollectionEngine.dailyMix(ledger,input,42,yours=true).items.map { it.identity }.toSet()
            return sources.keys.map { id -> sources[id]!!.items.count { it.identity in ids } }
        }
        assertThat(counts(sources)).containsExactly(15,20,15,10,0).inOrder()
        assertThat(counts(sources + (AutoCollectionId.NEW_UNEXPLORED to AutoCollection(AutoCollectionId.NEW_UNEXPLORED,emptyList())))).containsExactly(0,35,15,10,0).inOrder()
    }
    private suspend fun force(repository:AutoCollectionRepository) {
        // Both public APIs are supported so the same behavioral assertions run against B55-before.
        val method=repository.javaClass.methods.firstOrNull { it.name=="refresh" && it.parameterCount==3 }
        if(method == null) repository.refresh("user requested a refresh")
        else suspendCoroutineUninterceptedOrReturn<Unit> { method.invoke(repository,"user requested a refresh",true,it) }
    }
    private suspend fun TestScope.withRepository(history:ListeningLedger?=null,block:suspend (AutoCollectionRepository,UserPreferencesRepository,()->Unit)->Unit) {
        val directory=Files.createTempDirectory("b55-mixes").toFile()
        var instant=now
        val preferences=UserPreferencesRepository(PreferenceDataStoreFactory.create(scope=backgroundScope,produceFile={directory.resolve("settings.preferences_pb")}),Json)
        val songs=SyntheticHistory.songs(180)
        val ledger=history ?: ListeningLedger.build(emptyList(),Kit.catalog(songs,firstSeen=songs.associate { it.libraryItemId to now-10*Kit.DAY }))
        val provider=object:LedgerProvider { override val changes=emptyFlow<Long>();override suspend fun ledger()=ledger }
        val music=mockk<MusicRepository>(relaxed=true)
        coEvery { music.getSongIdsSorted(any(),any()) } returns emptyList()
        val repository=AutoCollectionRepository(provider,preferences,music,LibraryChangeSignal(),TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),backgroundScope)
        repository.clock={instant};repository.zone={Kit.zone}
        try { repository.refresh("test");runCurrent();block(repository,preferences,{instant-=Kit.DAY}) }
        finally { directory.deleteRecursively() }
    }
    @Test fun `H02 force creates a new Daily Mix and the saved list stays stable afterwards`(): Unit = runTest {
        withRepository { repo,_,_ ->
            val first=repo.homeMixes.value.daily.map { it.identity }
            force(repo);runCurrent();val second=repo.homeMixes.value.daily.map { it.identity }
            assertThat(second).isNotEqualTo(first)
            repo.refresh("opened again");runCurrent()
            assertThat(repo.homeMixes.value.daily.map { it.identity }).isEqualTo(second)
        }
    }
    @Test fun `H04 hiding Daily Mix hides it on Home as well`(): Unit = runTest {
        withRepository { repo,prefs,_ ->
            prefs.setAutoCollectionHidden("daily_mix",true);runCurrent()
            assertThat(repo.homeMixes.value.daily).isEmpty()
        }
    }
    @Test fun `H09 going back a day does not overwrite the later saved mix`(): Unit = runTest {
        withRepository { repo,prefs,back ->
            val later=prefs.autoMixSnapshotJson()
            back();repo.refreshIfDayChanged();runCurrent()
            assertThat(prefs.autoMixSnapshotJson()).isEqualTo(later)
        }
    }
    @Test fun `Suggestions is available to Home without a Playlists collection row`(): Unit = runTest {
        withRepository { repo,_,_ ->
            assertThat(repo.homeMixes.value.yours).isNotEmpty()
            assertThat(repo.state.value.collections.map { it.id.key }).doesNotContain("your_mix")
            assertThat(AutoCollectionId.fromKey("your_mix")).isNull()
        }
    }

    @Test fun `a forced Daily regeneration keeps Suggestions on its day seed with the updated exclusion`():Unit = runTest {
        val ledger=mature()
        withRepository(ledger) { repo,_,_ ->
            force(repo);runCurrent()
            val daily=repo.homeMixes.value.daily.mapTo(hashSetOf()) { it.identity }
            val sources=AutoCollectionEngine.compute(ledger,now,Kit.zone,emptyList())
            val expected=AutoCollectionEngine.dailyMix(ledger,sources,AutoCollectionRepository.localEpochDay(now,Kit.zone),yours=true,excluding=daily)
            assertThat(repo.homeMixes.value.yours.map { it.identity }).isEqualTo(expected.items.map { it.identity })
        }
    }

}
