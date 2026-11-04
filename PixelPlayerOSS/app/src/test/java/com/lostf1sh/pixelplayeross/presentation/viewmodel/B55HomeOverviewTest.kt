package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.preferences.*
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class B55HomeOverviewTest {
    private class Provider: LedgerProvider {
        var calls=0
        override val changes=MutableStateFlow(0L)
        override suspend fun ledger():ListeningLedger { calls++;return Kit.ledger(emptyList(),Kit.catalog(emptyList())) }
    }
    private fun make(provider:Provider,dispatchers:DispatcherProvider,clock:ClockChanges):ViewModel {
        val cls=try { Class.forName("com.lostf1sh.pixelplayeross.presentation.viewmodel.HomeOverviewViewModel") } catch(_:ClassNotFoundException) { null }
        if(cls == null) return StatsViewModel(provider,mockk<PlaylistPreferencesRepository>(relaxed=true),LibraryChangeSignal(),dispatchers,clock)
        val prefs=mockk<UserPreferencesRepository>(relaxed=true)
        every { prefs.artistDelimitersFlow } returns flowOf(UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
        val session=Class.forName("com.lostf1sh.pixelplayeross.presentation.viewmodel.SuggestionsOpenSession").getConstructor().newInstance()
        return cls.constructors.single().newInstance(provider,dispatchers,clock,prefs,mockk<MusicRepository>(relaxed=true),session) as ViewModel
    }
    @Test fun `H08 Home asks the fake ledger provider once instead of loading a summary and an overview`():Unit = runTest {
        val provider=Provider();val clock=object:ClockChanges { override val changes=emptyFlow<Unit>() }
        val vm=make(provider,TestDispatcherProvider(StandardTestDispatcher(testScheduler)),clock)
        try { runCurrent();assertThat(provider.calls).isEqualTo(1) } finally { vm.viewModelScope.cancel();unmockkAll() }
    }
    @Test fun `clock and zone signals reproject the full Home history`():Unit = runTest {
        val provider=Provider();val signals=MutableSharedFlow<Unit>(extraBufferCapacity=1)
        val clock=object:ClockChanges { override val changes=signals }
        val vm=make(provider,TestDispatcherProvider(StandardTestDispatcher(testScheduler)),clock)
        try {
            val field=vm.javaClass.getDeclaredField("clock").apply { isAccessible=true }
            var now=Kit.at(2026,11,2,23)
            field.set(vm,{now})
            var zone=java.time.ZoneId.of("UTC")
            vm.javaClass.getDeclaredField("zone").apply { isAccessible=true }.set(vm,{zone})
            runCurrent()
            now+=2*Kit.HOUR;zone=java.time.ZoneId.of("America/Los_Angeles");signals.emit(Unit);runCurrent()
            val getter=vm.javaClass.methods.firstOrNull { it.name=="getState" }
            org.junit.jupiter.api.Assertions.assertNotNull(getter,"Home must expose its own clock-projected state")
            val state=(getter!!.invoke(vm) as StateFlow<*>).value!!
            assertThat(state.javaClass.getMethod("getNowMs").invoke(state)).isEqualTo(now)
            assertThat(state.javaClass.getMethod("getZone").invoke(state)).isEqualTo(zone)
            assertThat(provider.calls).isEqualTo(2)
        } finally { vm.viewModelScope.cancel();unmockkAll() }
    }
}
