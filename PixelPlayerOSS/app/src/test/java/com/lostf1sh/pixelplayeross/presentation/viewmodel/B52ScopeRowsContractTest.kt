package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.offline.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B52ScopeRowsContractTest {
    @Test fun `an existing artist subscription follows a different entry scope`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val downloads=mockk<MusicInfoDownloads>(relaxed=true)
        val dao=mockk<MusicInfoDiscoveryDao>(relaxed=true)
        every { downloads.dao } returns dao
        every { dao.queue() } returns flowOf(InfoQueue())
        every { dao.downloadProgress() } returns flowOf(InfoProgress(0,0,0))
        every { downloads.scopeKey(any(),any()) } answers { firstArg<List<String>>().single() }
        coEvery { downloads.open(any(),any()) } coAnswers { firstArg<List<String>>().single() }
        fun row(key:String)=InfoDiscovery(key,"COVER",key,"artist","Artist",albumKey="album",availability="AVAILABLE")
        coEvery { dao.scopeRows(any()) } answers { listOf(row(firstArg())) }
        coEvery { dao.scopeMembers(any()) } answers {
            listOf(InfoArtistMember(firstArg(),"artist","Artist",firstArg(),""))
        }
        val vm=OfflineDownloadsViewModel(downloads)
        var rows=emptyList<InfoChoice>()
        val subscription=launch(Dispatchers.Main) { vm.choices("artist").collect { rows=it } }
        try {
            vm.open(listOf("album-a"))
            withTimeout(2000) { while(vm.opening.value) delay(5) }
            withTimeout(500) { while(rows.firstOrNull()?.key!="album-a") delay(5) }
            vm.open(listOf("album-b"))
            withTimeout(2000) { while(vm.opening.value) delay(5) }
            withTimeout(500) { while(rows.firstOrNull()?.key!="album-b") delay(5) }
            assertThat(rows.map { it.key }).containsExactly("album-b")
        } finally { subscription.cancelAndJoin();vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin();Dispatchers.resetMain() }
    }
}
