package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.offline.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B52PreparingFindContractTest {
    @Test fun `finding during preparation waits for the requested scope and never starts a library search`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val downloads=mockk<MusicInfoDownloads>(relaxed=true)
        val dao=mockk<MusicInfoDiscoveryDao>(relaxed=true)
        every { downloads.dao } returns dao
        every { dao.queue() } returns flowOf(InfoQueue())
        every { dao.downloadProgress() } returns flowOf(InfoProgress(0,0,0))
        every { downloads.scopeKey(any(),any()) } returns "album-scope"
        coEvery { downloads.open(any(),any()) } coAnswers { entered.complete(Unit);release.await();"album-scope" }
        val vm=OfflineDownloadsViewModel(downloads)
        try {
            vm.open(listOf("1"))
            withTimeout(2000) { entered.await() }
            vm.find()
            coVerify(exactly=0) { downloads.find(any()) }
            release.complete(Unit)
            withTimeout(2000) { while(vm.opening.value) delay(5) }
            coVerify(exactly=1) { downloads.find("album-scope") }
        } finally {
            release.complete(Unit)
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
