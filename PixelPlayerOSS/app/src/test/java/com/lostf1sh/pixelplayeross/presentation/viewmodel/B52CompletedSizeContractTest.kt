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
class B52CompletedSizeContractTest {
    @Test fun `Done measures saved global data rather than the current scopes source estimates`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val downloads=mockk<MusicInfoDownloads>(relaxed=true)
        val dao=mockk<MusicInfoDiscoveryDao>(relaxed=true)
        val progress=MutableStateFlow(InfoProgress(10,2,0,pending=8))
        every { downloads.dao } returns dao
        every { dao.queue() } returns flowOf(InfoQueue())
        every { dao.downloadProgress() } returns progress
        val vm=OfflineDownloadsViewModel(downloads)
        var subscription:Job?=null
        try {
            val getter=vm.javaClass.methods.firstOrNull { it.name=="getCompletedBytes" }
            org.junit.Assert.assertNotNull("Done must measure actual saved global bytes, including texts with no source size",getter)
            coEvery { downloads.downloadedBytes() } returns 10_400_000L
            val bytes=getter!!.invoke(vm) as StateFlow<*>
            subscription=launch(Dispatchers.Main) { bytes.collect {} }
            coVerify(exactly=0) { downloads.downloadedBytes() }
            progress.value=InfoProgress(10,10,0,pending=0)
            withTimeout(2000) { while(bytes.value!=10_400_000L) delay(5) }
            assertThat(bytes.value).isEqualTo(10_400_000L)
        } finally { subscription?.cancel();vm.viewModelScope.cancel();Dispatchers.resetMain() }
    }
}

// Compile the same test against HEAD. Its missing projection fails above before this compatibility bridge can run.
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
private suspend fun MusicInfoDownloads.downloadedBytes(): Long = 0
