package com.lostf1sh.pixelplayeross.data.musicinfo

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B44StartupRepairTest {
    @Test fun `startup drops untrustworthy missing entries and broken HTML photos once while keeping valid photos`() = runBlocking {
        val dir = Files.createTempDirectory("b44-startup").toFile()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = dir
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dao = mockk<MusicDao>(relaxed = true)
        val info = MusicInfoRepository(context, mockk<UserPreferencesRepository>(relaxed = true), OkHttpClient(), dao, scope)
        val cache = info.cache
        val html = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "broken", "<html>not a photo</html>".toByteArray(), "jpg", null)
        val png = ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, stream)
                recycle()
            }
        }.toByteArray()
        val photo = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "valid", png, "png", null)
        cache.putMissing(MusicInfoCache.Kind.ARTIST_PHOTO, "old")
        cache.putMissing(MusicInfoCache.Kind.COVER, "old-cover")
        try {
            // Run the startup entry point if present; the old lazy repair is the before behavior.
            val entry = MusicInfoRepository::class.java.methods.firstOrNull { it.name == "repairAtStartupOnce" }
            suspend fun startup() {
                if (entry == null) info.repairBrokenImagesOnce()
                else kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    val result = entry.invoke(info, continuation)
                    if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resumeWith(Result.success(Unit))
                }
            }
            startup()
            assertThat(cache.isMissing(MusicInfoCache.Kind.ARTIST_PHOTO, "old")).isFalse()
            assertThat(cache.isMissing(MusicInfoCache.Kind.COVER, "old-cover")).isFalse()
            assertThat(html.exists()).isFalse()
            coVerify { dao.clearArtistImagesUnder(html.absolutePath) }
            assertThat(photo.readBytes()).isEqualTo(png)
            coVerify(exactly = 0) { dao.clearArtistImagesUnder(photo.absolutePath) }
            cache.putMissing(MusicInfoCache.Kind.ARTIST_PHOTO, "new")
            startup()
            assertThat(cache.isMissing(MusicInfoCache.Kind.ARTIST_PHOTO, "new")).isTrue()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
