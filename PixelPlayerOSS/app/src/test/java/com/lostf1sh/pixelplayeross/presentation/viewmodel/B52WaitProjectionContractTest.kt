package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.offline.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B52WaitProjectionContractTest {
    private var vm: OfflineDownloadsViewModel? = null
    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { vm?.viewModelScope?.cancel(); Dispatchers.resetMain(); unmockkAll() }
    private fun field(value: Any, name: String): Any = value.javaClass.getMethod("get$name").invoke(value)

    @Test fun `one wait projection merges constraints live rate limits and persisted source retries`(): Unit = runBlocking {
        val networkType = Class.forName("com.lostf1sh.pixelplayeross.data.offline.InfoNetworkState")
        val wifi = networkType.enumConstants.single { it.toString() == "WIFI" }
        val ready = networkType.enumConstants.single { it.toString() == "READY" }
        val waitKind = Class.forName("com.lostf1sh.pixelplayeross.data.network.SourceWaitKind").enumConstants.single { it.toString() == "RATE_LIMIT" }
        val waitType = Class.forName("com.lostf1sh.pixelplayeross.data.network.SourceWait")
        val liveWait = waitType.constructors.single().newInstance("LRCLIB", waitKind, System.currentTimeMillis() + 40_000)
        val retryType = Class.forName("com.lostf1sh.pixelplayeross.data.offline.InfoSourceRetry")
        val persistedWait = retryType.constructors.single().newInstance("Wikimedia Commons", "UNAVAILABLE", System.currentTimeMillis() + 120_000)
        val network = MutableStateFlow(wifi)
        val live = MutableStateFlow(mapOf("LRCLIB" to liveWait))
        val persisted = MutableStateFlow(listOf(persistedWait))
        val downloads = mockk<MusicInfoDownloads>(relaxed = true)
        val dao = mockk<MusicInfoDiscoveryDao>(relaxed = true)
        every { downloads.dao } returns dao
        every { dao.queue() } returns flowOf(InfoQueue())
        every { dao.downloadProgress() } returns flowOf(InfoProgress(0, 0, 0))
        every { downloads getProperty "network" } returns network
        every { downloads getProperty "sourceWaits" } returns live
        every { dao["sourceRetries"]() } returns persisted
        every { dao["undoActions"]() } returns flowOf(emptyList<Any>())
        val model = OfflineDownloadsViewModel(downloads).also { vm = it }
        @Suppress("UNCHECKED_CAST")
        val waiting = model.javaClass.getMethod("getWaiting").invoke(model) as StateFlow<Any>
        withTimeout(10_000) {
            val first = waiting.first { field(it, "Network").toString() == "WIFI" && (field(it, "Sources") as List<*>).size == 2 }
            assertThat((field(first, "Sources") as List<*>).map { field(it!!, "Source") }).containsExactly("LRCLIB", "Wikimedia Commons")
            network.value = ready
            waiting.first { field(it, "Network").toString() == "READY" && (field(it, "Sources") as List<*>).size == 2 }
            live.value = emptyMap()
            val restored = waiting.first { (field(it, "Sources") as List<*>).size == 1 }
            assertThat(field((field(restored, "Sources") as List<*>).single()!!, "Source")).isEqualTo("Wikimedia Commons")
            persisted.value = emptyList()
            waiting.first { (field(it, "Sources") as List<*>).isEmpty() }
        }
    }
}
