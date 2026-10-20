package com.lostf1sh.pixelplayeross.data.image

import android.content.Context
import androidx.core.content.edit
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses synthetic song ids and files only; never rewrites library audio or preferences. */
@RunWith(AndroidJUnit4::class)
class ArtworkInvalidationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val id = Long.MAX_VALUE - 101
    private val neighbor = id - 1
    private val uri = LocalArtworkUri.buildSongUri(id)

    @Test
    fun forceRefreshInvalidatesBothFilesAndAllMemoryVariants() = runBlocking {
        val source = File(context.cacheDir, "art-refresh-fixture.mp3")
        try {
            fixture("red", source)
            val small = AlbumArtUtils.ensureAlbumArtThumbnailFile(context, id, source.path)!!
            assertColor(Color.RED, small)
            assertColor(Color.RED, AlbumArtUtils.ensureAlbumArtCachedFile(context, id, source.path)!!)
            val neighborFile = AlbumArtUtils.getCachedThumbnailFile(context, neighbor).apply { writeBytes(byteArrayOf(7)) }
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val keys = listOf("${uri}_96x96", "${uri}?t=old_2048x2048", "${uri}_256x256_rev_old")
            keys.forEach { context.imageLoader.memoryCache!!.set(MemoryCache.Key(it), MemoryCache.Value(bitmap)) }
            val disk = context.imageLoader.diskCache!!
            val diskKey = "${uri}_96x96"
            disk.openEditor(diskKey)!!.let { editor ->
                editor.metadata.toFile().writeText("old")
                editor.data.toFile().writeBytes(byteArrayOf(1))
                editor.commit()
            }
            val legacy = File(context.cacheDir, "song_art_${id}_v4.jpg").apply { writeText("stale legacy cover") }
            val oldGeneration = LocalArtworkCache.generation(context, id)
            fixture("blue", source)
            val full = AlbumArtUtils.ensureAlbumArtCachedFile(context, id, source.path, forceRefresh = true)!!
            assertColor(Color.BLUE, full)
            assertFalse(small.exists())
            assertNull(disk.openSnapshot(diskKey))
            assertFalse(legacy.exists())
            AlbumArtUtils.migrateLegacyCacheLocation(context)
            keys.forEach { assertNull(context.imageLoader.memoryCache!![MemoryCache.Key(it)]) }
            assertArrayEquals(byteArrayOf(7), neighborFile.readBytes())
            assertNotEquals(oldGeneration, LocalArtworkCache.generation(context, id))
            assertEquals(LocalArtworkCache.generation(context, id), LocalArtworkCache.generation(context.createPackageContext(context.packageName, 0), id))
            assertColor(Color.BLUE, AlbumArtUtils.ensureAlbumArtThumbnailFile(context, id, source.path)!!)
            // Stable URI player and Library size requests use the regenerated files and revision keys.
            for (edge in listOf(96, 2048)) {
                val result = context.imageLoader.execute(ImageRequest.Builder(context).data(uri).size(edge).allowHardware(false).build())
                assertTrue(result is SuccessResult)
                val image = ((result as SuccessResult).drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue(Color.blue(image.getPixel(0, 0)) > 200)
            }
        } finally {
            AlbumArtUtils.clearCacheForSong(context, id)
            AlbumArtUtils.clearCacheForSong(context, neighbor)
            context.getSharedPreferences("artwork_generations", 0).edit(commit = true) { remove(id.toString()); remove(neighbor.toString()) }
            source.delete()
        }
    }

    @Test
    fun concurrentGenerationCannotRestoreArtAfterInvalidation() = runBlocking {
        val source = File(context.cacheDir, "art-race-fixture.mp3")
        try {
            fixture("red", source)
            // Concurrent full/thumbnail writers use the same lock as invalidation.
            val writes = (1..12).map { n -> async(kotlinx.coroutines.Dispatchers.IO) {
                if (n % 2 == 0) AlbumArtUtils.ensureAlbumArtCachedFile(context, id, source.path)
                else AlbumArtUtils.ensureAlbumArtThumbnailFile(context, id, source.path)
            } }
            AlbumArtUtils.clearCacheForSong(context, id)
            writes.forEach { it.await() }
            fixture("blue", source)
            AlbumArtUtils.getAlbumArtUriForLibraryScan(context, id, forceRefresh = true)
            assertFalse(AlbumArtUtils.getCachedAlbumArtFile(context, id).exists())
            assertFalse(AlbumArtUtils.getCachedThumbnailFile(context, id).exists())
            assertColor(Color.BLUE, AlbumArtUtils.ensureAlbumArtThumbnailFile(context, id, source.path)!!)
            assertColor(Color.BLUE, AlbumArtUtils.ensureAlbumArtCachedFile(context, id, source.path)!!)
        } finally {
            AlbumArtUtils.clearCacheForSong(context, id)
            context.getSharedPreferences("artwork_generations", 0).edit(commit = true) { remove(id.toString()) }
            source.delete()
        }
    }

    @Test
    fun lateCoilResultRetriesWithCurrentGeneration() = runBlocking {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val original = ImageRequest.Builder(context).data(uri).size(96).memoryCacheKey("${uri}_96x96").build()
        val keys = mutableListOf<String>()
        val chain = object : coil.intercept.Interceptor.Chain {
            override val request = original
            override val size = coil.size.Size(96, 96)
            override fun withRequest(request: ImageRequest): coil.intercept.Interceptor.Chain = error("unused")
            override fun withSize(size: coil.size.Size): coil.intercept.Interceptor.Chain = error("unused")
            override suspend fun proceed(request: ImageRequest): coil.request.ImageResult {
                keys += request.memoryCacheKey!!.key
                if (keys.size == 1) { ready.complete(Unit); release.await() }
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                context.imageLoader.memoryCache!!.set(request.memoryCacheKey!!, MemoryCache.Value(bitmap))
                return SuccessResult(android.graphics.drawable.BitmapDrawable(context.resources, bitmap), request,
                    coil.decode.DataSource.DISK, memoryCacheKey = request.memoryCacheKey)
            }
        }
        try {
            val result = async { LocalArtworkGenerationInterceptor().intercept(chain) }
            ready.await()
            AlbumArtUtils.clearCacheForSong(context, id)
            release.complete(Unit)
            result.await()
            assertEquals(2, keys.size)
            assertNull(context.imageLoader.memoryCache!![MemoryCache.Key(keys[0])])
            assertNotNull(context.imageLoader.memoryCache!![MemoryCache.Key(keys[1])])
            assertNotEquals(keys[0], keys[1])
            assertTrue(keys[1].endsWith("_rev_${LocalArtworkCache.generation(context, id)}"))
        } finally {
            release.complete(Unit)
            AlbumArtUtils.clearCacheForSong(context, id)
            context.getSharedPreferences("artwork_generations", 0).edit(commit = true) { remove(id.toString()) }
        }
    }

    /** The external validation invokes prepare and verify in separate instrumentation processes. */
    @Test
    fun refreshSurvivesProcessRestart() = runBlocking {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("artRestartPhase")
        if (phase != "verify") prepareRefreshForProcessRestart()
        if (phase != "prepare") verifyRefreshAfterProcessRestart()
    }

    private fun prepareRefreshForProcessRestart() {
        val source = File(context.cacheDir, "art-restart-fixture.mp3")
        fixture("red", source)
        AlbumArtUtils.ensureAlbumArtThumbnailFile(context, id, source.path)
        AlbumArtUtils.ensureAlbumArtCachedFile(context, id, source.path)
        fixture("blue", source)
        AlbumArtUtils.ensureAlbumArtCachedFile(context, id, source.path, forceRefresh = true)
        File(context.cacheDir, "art-restart-generation").writeText(LocalArtworkCache.generation(context, id))
    }

    private fun verifyRefreshAfterProcessRestart() = runBlocking {
        val source = File(context.cacheDir, "art-restart-fixture.mp3")
        val expected = File(context.cacheDir, "art-restart-generation")
        try {
            assertEquals(expected.readText(), LocalArtworkCache.generation(context, id))
            for (edge in listOf(96, 2048)) {
                val result = context.imageLoader.execute(ImageRequest.Builder(context).data(uri).size(edge).allowHardware(false).build())
                assertTrue(result is SuccessResult)
                val bitmap = ((result as SuccessResult).drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue(Color.blue(bitmap.getPixel(0, 0)) > 200)
            }
        } finally {
            AlbumArtUtils.clearCacheForSong(context, id)
            context.getSharedPreferences("artwork_generations", 0).edit(commit = true) { remove(id.toString()) }
            source.delete()
            expected.delete()
        }
    }

    private fun fixture(color: String, target: File) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("art-$color.mp3").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    private fun assertColor(expected: Int, file: File) {
        val bitmap = BitmapFactory.decodeFile(file.path)!!
        val actual = bitmap.getPixel(0, 0)
        assertTrue("expected $expected, got $actual", if (expected == Color.RED) Color.red(actual) > 200 && Color.blue(actual) < 40 else Color.blue(actual) > 200 && Color.red(actual) < 40)
        bitmap.recycle()
    }
}
