package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class B52TypedErrorsContractTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun errors(vm: OfflineDownloadsViewModel): Map<*, *> {
        val method = vm.javaClass.methods.firstOrNull { it.name == "getErrors" }
        org.junit.jupiter.api.Assertions.assertNotNull(method, "Each action must expose its own typed error")
        return (method!!.invoke(vm) as StateFlow<*>).value as Map<*, *>
    }

    @Test fun `Find Choose Download Delete and Pause expose separate errors and Retry clears only its action`() = runTest {
        val downloads = mockk<MusicInfoDownloads>(relaxed = true)
        val dao = mockk<MusicInfoDiscoveryDao>(relaxed = true)
        every { downloads.dao } returns dao
        every { dao.queue() } returns flowOf(InfoQueue())
        every { dao.downloadProgress() } returns flowOf(InfoProgress(0, 0, 0))
        coEvery { downloads.find(any()) } throws IOException("find")
        coEvery { downloads.appendSelected(any()) } throws IOException("download")
        coEvery { downloads.deleteSelected(any()) } throws IOException("delete")
        coEvery { downloads.pause() } throws IOException("pause")
        coEvery { dao.select(any(), any()) } throws IOException("choose")
        val vm = OfflineDownloadsViewModel(downloads)
        vm.opening.value = false
        vm.find(); vm.download(); vm.delete(); vm.pause(); vm.select("photo|artist", true)
        advanceUntilIdle()
        assertThat(errors(vm).keys.map { it.toString() }).containsExactly("FIND", "DOWNLOAD", "DELETE", "PAUSE", "CHOOSE")
        coEvery { downloads.find(any()) } just Runs
        val retry = vm.javaClass.methods.single { it.name == "retry" }
        val find = retry.parameterTypes.single().enumConstants.single { it.toString() == "FIND" }
        retry.invoke(vm, find)
        advanceUntilIdle()
        assertThat(errors(vm).keys.map { it.toString() }).containsExactly("DOWNLOAD", "DELETE", "PAUSE", "CHOOSE")
    }

    private fun pageResult(vm: MusicInfoActionsViewModel): Any {
        val method = vm.javaClass.methods.firstOrNull { it.name == "getResult" }
        org.junit.jupiter.api.Assertions.assertNotNull(method, "Page actions must publish their result")
        return (method!!.invoke(vm) as StateFlow<*>).value!!
    }
    private fun status(result: Any): String = result.javaClass.getMethod("getStatus").invoke(result).toString()

    @Test fun `a page Refresh failure is visible and Retry publishes success`() = runTest {
        val downloads = mockk<OfflineDownloads>(relaxed = true)
        val vm = MusicInfoActionsViewModel(mockk<MusicInfoRepository>(relaxed = true), downloads, mockk<ArtistImageRepository>(relaxed = true))
        coEvery { downloads.refreshArtistInfo(any(), any()) } throws IOException("source down")
        var completed = 0
        vm.refreshArtist("Artist", emptyList()) { completed++ }
        advanceUntilIdle()
        assertThat(status(pageResult(vm))).isEqualTo("FAILURE")
        assertThat(completed).isEqualTo(0)
        coEvery { downloads.refreshArtistInfo(any(), any()) } returns null
        vm.javaClass.getMethod("retry").invoke(vm)
        advanceUntilIdle()
        assertThat(status(pageResult(vm))).isEqualTo("SUCCESS")
        assertThat(completed).isEqualTo(1)
    }

    @Test fun `a page Delete failure has its own visible action result`() = runTest {
        val downloads = mockk<OfflineDownloads>(relaxed = true)
        val vm = MusicInfoActionsViewModel(mockk<MusicInfoRepository>(relaxed = true), downloads, mockk<ArtistImageRepository>(relaxed = true))
        coEvery { downloads.deleteAlbumInfo(any(), any(), any()) } throws IOException("disk")
        vm.deleteAlbum("Album", "Artist", emptyList()) { error("failed action cannot report completion") }
        advanceUntilIdle()
        val result = pageResult(vm)
        assertThat(status(result)).isEqualTo("FAILURE")
        assertThat(result.javaClass.getMethod("getAction").invoke(result).toString()).isEqualTo("DELETE")
    }
}
