package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.offline.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B55OpeningDeltaTest {
    @Test fun `a worker change during the initial member load is merged after the snapshot is ready`():Unit = runTest {
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val background=StandardTestDispatcher(testScheduler)
        val downloads=mockk<MusicInfoDownloads>(relaxed=true)
        val dao=mockk<MusicInfoDiscoveryDao>(relaxed=true)
        val membersReady=CompletableDeferred<Unit>()
        var row=InfoDiscovery("synthetic","LYRICS","Synthetic song","artist","Synthetic artist")
        var listener:(List<String>)->Unit={}
        every { downloads.dao } returns dao
        every { downloads.scopeKey(any(),any()) } returns "library"
        every { dao.listenChanges(any()) } answers { listener=firstArg();{} }
        coEvery { dao.scopeRows("library") } answers { listOf(row) }
        coEvery { dao.scopeMembers("library") } coAnswers { membersReady.await();emptyList() }
        coEvery { dao.changedRows(any()) } answers { listOf(row) }
        val vm=OfflineDownloadsViewModel(downloads,background)
        try {
            vm.open(null);runCurrent()
            assertThat(vm.opening.value).isTrue()
            row=row.copy(availability="AVAILABLE",checkedAt=55)
            listener(listOf(row.key))
            advanceTimeBy(150);runCurrent()
            membersReady.complete(Unit);runCurrent()
            assertThat(vm.opening.value).isFalse()
            assertThat(vm.types.value).isEqualTo(infoTypeCounts(listOf(row)))
            assertThat(vm.checkedAt.value).isEqualTo(55)
            val choiceDone=CompletableDeferred<Unit>()
            coEvery { dao.select(any(),any()) } coAnswers { choiceDone.await();row=row.copy(selected=secondArg());Unit }
            vm.select(row.key,true);runCurrent()
            assertThat(vm.types.value.single { it.kind==InfoKind.LYRICS }.chosen).isEqualTo(1)
            // A recreated composition re-opens the same retained ViewModel, as on rotation.
            vm.open(null);runCurrent()
            assertThat(vm.types.value.single { it.kind==InfoKind.LYRICS }.chosen).isEqualTo(1)
            coVerify(exactly=1) { dao.scopeRows("library") }
            choiceDone.complete(Unit);runCurrent()
        } finally { vm.viewModelScope.cancel();runCurrent();vm.viewModelScope.coroutineContext[Job]?.join();Dispatchers.resetMain();unmockkAll() }
    }
    @Test fun `model checked time equals a fresh load after check timestamps move backwards`():Unit {
        val first=InfoDiscovery("one","LYRICS","Synthetic one","artist","Synthetic artist",checkedAt=100)
        val second=first.copy(key="two",checkedAt=200)
        val model=InfoScreenModel(listOf(first,second),emptyList())
        model.merge(listOf(second.copy(checkedAt=50)))
        assertThat(model.checkedAt).isEqualTo(100)
        model.merge(listOf(first.copy(checkedAt=10)))
        assertThat(model.checkedAt).isEqualTo(50)
    }

}
