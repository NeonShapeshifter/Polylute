package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtistPageNonBlockingTest {
    private val models = mutableListOf<ArtistDetailViewModel>()
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private val music = mockk<MusicRepository>(relaxed = true).also {
        every { it.getArtistById(any()) } answers { flowOf(Artist(firstArg(), "Artist ${firstArg<Long>()}", 0)) }
        every { it.getSongsForArtist(any()) } returns flowOf(emptyList())
    }
    private val images = mockk<ArtistImageRepository>(relaxed = true)
    private val themes = mockk<ThemeStateHolder>(relaxed = true)
    private fun model(handle: SavedStateHandle = SavedStateHandle(mapOf("artistId" to "1"))): ArtistDetailViewModel {
        val service = mockk<MusicInfoService>(relaxed = true)
        coEvery { service.cachedAbout(any(), any(), any()) } returns null
        coEvery { service.cachedArtistPhoto(any()) } returns null
        coEvery { service.fetchAbout(any(), any(), any(), any()) } returns null
        val info = mockk<MusicInfoRepository>(relaxed = true) { every { this@mockk.service } returns service }
        return ArtistDetailViewModel(ApplicationProvider.getApplicationContext<Context>(), music, images, info, themes, handle).also { models += it }
    }
    @Test fun `the local artist and songs appear while the online photo is still waiting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<String?>()
        coEvery { images.getEffectiveArtistImageUrl(any(), any()) } coAnswers { gate.await() }
        val vm = model()
        runCurrent()
        assertThat(vm.uiState.value.artist?.name).isEqualTo("Artist 1")
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(gate.isCompleted).isFalse()
        gate.complete(null)
        runCurrent()
        io.mockk.coVerify(exactly = 1) { images.getEffectiveArtistImageUrl(1L, "Artist 1") }
        assertThat(vm.uiState.value.isLoading).isFalse()
    }
    @Test fun `palette extraction never holds the page loading state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { images.getEffectiveArtistImageUrl(any(), any()) } returns "/photo.jpg"
        val gate = CompletableDeferred<ColorSchemePair?>()
        coEvery { themes.getOrGenerateColorScheme("/photo.jpg") } coAnswers { gate.await() }
        val vm = model()
        runCurrent()
        assertThat(vm.uiState.value.artist).isNotNull()
        assertThat(vm.uiState.value.isLoading).isFalse()
        gate.complete(null)
        runCurrent()
    }
    @Test fun `an old image arriving after navigation cannot replace the next artist`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<String?>()
        coEvery { images.getEffectiveArtistImageUrl(1L, any()) } coAnswers { withContext(NonCancellable) { gate.await() } }
        coEvery { images.getEffectiveArtistImageUrl(2L, any()) } returns null
        val handle = SavedStateHandle(mapOf("artistId" to "1"))
        val vm = model(handle)
        runCurrent()
        handle["artistId"] = "2"
        runCurrent()
        gate.complete("/old-photo.jpg")
        runCurrent()
        assertThat(vm.uiState.value.artist?.id).isEqualTo(2L)
        assertThat(vm.uiState.value.effectiveImageUrl).isNull()
    }
}
