package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.musicinfo.ArtistPhoto
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtistImageRepositoryTest {

    @Test
    fun `calculateCustomImageSampleSize keeps small bitmaps at full resolution`() {
        assertEquals(1, ArtistImageRepository.calculateCustomImageSampleSize(1024, 1024))
    }

    @Test
    fun `calculateCustomImageSampleSize aggressively downsamples oversized inputs`() {
        val sampleSize = ArtistImageRepository.calculateCustomImageSampleSize(12000, 8000)

        assertTrue(sampleSize >= 4)
        assertEquals(8, sampleSize)
    }

    private val photoFile: java.io.File = java.io.File.createTempFile("photo", ".jpg").also { it.writeBytes(ByteArray(10)); it.deleteOnExit() }

    private fun musicInfo(service: MusicInfoService): MusicInfoRepository = mockk(relaxed = true) { every { this@mockk.service } returns service }

    @Test
    fun `cancelled prefetch does not mark artist as failed for the session`() = runTest {
        val service = mockk<MusicInfoService>()
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(musicInfo(service), musicDao, userPreferencesRepository())
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val attempts = AtomicInteger(0)

        coEvery { musicDao.clearRemoteArtistImages() } returns 0
        coEvery { musicDao.getArtistIdByNormalizedName("Artist Name") } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns null
        coEvery { musicDao.getArtistImageUrlByNormalizedName("Artist Name") } returns null
        coJustRun { musicDao.updateArtistImageUrl(42L, any()) }
        coEvery { service.cachedArtistPhoto("Artist Name") } returns null
        coEvery { service.fetchArtistPhoto("Artist Name") } coAnswers {
            when (attempts.incrementAndGet()) {
                1 -> {
                    firstAttemptStarted.complete(Unit)
                    awaitCancellation()
                }

                else -> ArtistPhoto(photoFile.absolutePath, "Jane Doe", "CC BY 4.0", null, null)
            }
        }

        val prefetchJob = launch {
            repository.prefetchArtistImages(listOf(42L to "Artist Name"))
        }
        firstAttemptStarted.await()
        prefetchJob.cancel()
        prefetchJob.join()

        val imageUrl = repository.getArtistImageUrl("Artist Name", 42L)

        assertEquals(photoFile.absolutePath, imageUrl)
        assertEquals(2, attempts.get())
        coVerify(exactly = 1) { musicDao.updateArtistImageUrl(42L, photoFile.absolutePath) }
    }

    @Test
    fun `with the switch off no request is made`() = runTest {
        val service = mockk<MusicInfoService>(relaxed = true)
        val musicDao = mockk<MusicDao>(relaxed = true)
        coEvery { service.cachedArtistPhoto(any()) } returns null
        val repository = ArtistImageRepository(musicInfo(service), musicDao, userPreferencesRepository(photos = false))

        val imageUrl = repository.getArtistImageUrl("Artist Name", 42L)

        assertEquals(null, imageUrl)
        coVerify(exactly = 0) { service.fetchArtistPhoto(any()) }
    }

    @Test
    fun `a picture downloaded earlier is shown with the switch off, from the phone`() = runTest {
        val service = mockk<MusicInfoService>()
        val musicDao = mockk<MusicDao>(relaxed = true)
        coEvery { service.cachedArtistPhoto("Artist Name") } returns ArtistPhoto(photoFile.absolutePath, null, null, null, null)
        coEvery { musicDao.getArtistIdByNormalizedName(any()) } returns 42L
        coEvery { musicDao.getArtistImageUrl(any()) } returns null
        coEvery { musicDao.getArtistImageUrlByNormalizedName(any()) } returns null
        val repository = ArtistImageRepository(musicInfo(service), musicDao, userPreferencesRepository(photos = false))

        assertEquals(photoFile.absolutePath, repository.getArtistImageUrl("Artist Name", 42L))
        coVerify(exactly = 0) { service.fetchArtistPhoto(any()) }
    }

    @Test
    fun `a picture address stored from Deezer is cleared at the start and never shown`() = runTest {
        val service = mockk<MusicInfoService>()
        val musicDao = mockk<MusicDao>(relaxed = true)
        val deezer = "https://cdn-images.dzcdn.net/images/artist/1000x1000-000000-80-0-0.jpg"
        coEvery { service.cachedArtistPhoto(any()) } returns null
        coEvery { musicDao.clearRemoteArtistImages() } returns 7
        coEvery { musicDao.getArtistIdByNormalizedName(any()) } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns deezer
        coEvery { musicDao.getArtistImageUrlByNormalizedName(any()) } returns deezer
        val repository = ArtistImageRepository(musicInfo(service), musicDao, userPreferencesRepository(photos = false))

        repository.retireDeezerOnce()
        repository.retireDeezerOnce()
        // even if one were still there, the read path does not show it
        assertEquals(null, repository.getArtistImageUrl("Artist Name", 42L))
        coVerify(exactly = 1) { musicDao.clearRemoteArtistImages() }
    }

    private fun userPreferencesRepository(photos: Boolean = true): UserPreferencesRepository {
        return mockk {
            every { this@mockk.musicInfoArtistPhotosEnabledFlow } returns flowOf(photos)
        }
    }
}
