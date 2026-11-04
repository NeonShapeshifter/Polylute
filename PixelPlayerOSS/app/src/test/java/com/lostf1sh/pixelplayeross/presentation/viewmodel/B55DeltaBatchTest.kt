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
class B55DeltaBatchTest {
    @Test fun `two hundred worker deltas publish a handful of updates without reloading the scope`(): Unit = runBlocking {
        val scheduler=TestCoroutineScheduler();Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val downloads=mockk<MusicInfoDownloads>(relaxed=true)
        val dao=mockk<MusicInfoDiscoveryDao>(relaxed=true)
        val rows=(0 until 200).associate { "$it" to InfoDiscovery("$it","LYRICS","Synthetic $it","a","Synthetic artist") }.toMutableMap()
        var listener: (List<String>) -> Unit = {}
        every { downloads.dao } returns dao
        every { downloads.scopeKey(any(),any()) } returns "library"
        every { dao.listenChanges(any()) } answers { listener=firstArg();{} }
        coEvery { dao.scopeRows(any()) } answers { rows.values.toList() }
        coEvery { dao.scopeMembers(any()) } returns emptyList()
        coEvery { dao.changedRows(any()) } answers { firstArg<List<String>>().mapNotNull { rows[it] } }
        val vm=OfflineDownloadsViewModel(downloads)
        var emissions=0
        val collect=launch(Dispatchers.Main) { vm.types.collect { emissions++ } }
        try {
            vm.open(null);withTimeout(3000) { while(vm.opening.value) delay(5) }
            emissions=0
            repeat(200) { n -> rows["$n"]=rows["$n"]!!.copy(availability="AVAILABLE");listener(listOf("$n")) }
            scheduler.advanceTimeBy(150);scheduler.runCurrent()
            withTimeout(3000) { while(vm.types.value.sumOf { it.available } != 200) delay(5) }
            assertThat(emissions).isAtMost(3)
            assertThat(vm.types.value).isEqualTo(infoTypeCounts(rows.values.toList()))
            coVerify(exactly=1) { dao.scopeRows("library") }
        } finally { collect.cancelAndJoin();vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin();Dispatchers.resetMain();unmockkAll() }
    }
}
