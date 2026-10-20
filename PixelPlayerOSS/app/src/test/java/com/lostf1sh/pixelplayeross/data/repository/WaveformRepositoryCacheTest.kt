package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.Waveform
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B31 P2.5: **one decode per file.** The Details waveform took 7.4 s to decode a 4:13 FLAC; what the owner must never pay twice is the
 * same file's second open. The real [WaveformRepository] and its disk cache run here; only the codec is replaced by a counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WaveformRepositoryCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var track: File

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private fun waveform(seed: Float) = Waveform(
        bucketCount = 4, peaks = floatArrayOf(seed, 0.5f, 0.25f, 0.1f), rms = floatArrayOf(0.1f, 0.2f, 0.1f, 0.05f), sampleRate = 44_100, durationMs = 253_000L,
    )

    private fun song() = Song.emptySong().copy(id = "7", title = "Gaia", path = track.absolutePath, contentUriString = "", duration = 253_000L)

    private fun repository(decodes: AtomicInteger, produce: () -> Waveform? = { waveform(0.9f) }): WaveformRepository {
        // A fresh cache directory per test: the repository keeps its cache in the app's cache dir.
        File(context.cacheDir, "waveforms").deleteRecursively()
        return WaveformRepository(context, dispatchers).also {
            it.decoder = { _, _, _ -> decodes.incrementAndGet(); produce() }
        }
    }

    @Before
    fun makeTrack() {
        track = File.createTempFile("track", ".flac", context.cacheDir).also { it.writeBytes(ByteArray(2048) { b -> b.toByte() }) }
    }

    @After
    fun cleanUp() {
        track.delete()
        File(context.cacheDir, "waveforms").deleteRecursively()
    }

    @Test
    fun `the second open of the same file is read from the cache and decodes nothing`() = runBlocking {
        val decodes = AtomicInteger()
        val repo = repository(decodes)
        val first = repo.waveformFor(song())
        val second = repo.waveformFor(song())
        assertThat(first).isEqualTo(waveform(0.9f))
        assertThat(second).isEqualTo(first)
        assertThat(decodes.get()).isEqualTo(1)
    }

    @Test
    fun `a new repository over the same cache directory, as after a restart of the process, still decodes nothing`() = runBlocking {
        val decodes = AtomicInteger()
        val repo = repository(decodes)
        repo.waveformFor(song())
        val afterRestart = WaveformRepository(context, dispatchers).also { it.decoder = { _, _, _ -> decodes.incrementAndGet(); null } }
        assertThat(afterRestart.waveformFor(song())).isEqualTo(waveform(0.9f))
        assertThat(decodes.get()).isEqualTo(1)
    }

    @Test
    fun `a file replaced on disk has another identity and is decoded again`() = runBlocking {
        val decodes = AtomicInteger()
        val repo = repository(decodes)
        repo.waveformFor(song())
        track.writeBytes(ByteArray(4096) { 1 })
        track.setLastModified(track.lastModified() + 60_000)
        repo.waveformFor(song())
        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun `two opens at once share one decode`() = runBlocking {
        withTimeout(20_000) {
            val decodes = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            File(context.cacheDir, "waveforms").deleteRecursively()
            val repo = WaveformRepository(context, dispatchers).also {
                it.decoder = { _, _, _ -> decodes.incrementAndGet(); started.complete(Unit); release.await(); waveform(0.7f) }
            }
            val a = async(Dispatchers.Default) { repo.waveformFor(song()) }
            started.await()
            val b = async(Dispatchers.Default) { repo.waveformFor(song()) }
            release.complete(Unit)
            assertThat(a.await()).isEqualTo(waveform(0.7f))
            assertThat(b.await()).isEqualTo(waveform(0.7f))
            assertThat(decodes.get()).isEqualTo(1)
        }
    }

    @Test
    fun `a decode that finds nothing is not cached as a silent waveform, and is tried again next time`() = runBlocking {
        val decodes = AtomicInteger()
        val repo = repository(decodes) { null }
        assertThat(repo.waveformFor(song())).isNull()
        assertThat(repo.waveformFor(song())).isNull()
        assertThat(decodes.get()).isEqualTo(2)
    }
}
