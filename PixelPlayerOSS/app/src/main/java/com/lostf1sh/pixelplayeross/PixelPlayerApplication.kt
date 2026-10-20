package com.lostf1sh.pixelplayeross

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lostf1sh.pixelplayeross.data.diagnostics.AdvancedPerformanceDiagnosticsController
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.startup.LegacyCloudDataCleanup
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.headphones.DacHeadphoneAssociationController
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryStateHolder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ThemeStateHolder
import com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.AppLocaleManager
import com.lostf1sh.pixelplayeross.utils.CrashHandler
import com.lostf1sh.pixelplayeross.utils.MediaMetadataRetrieverPool
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class PixelPlayerApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var localArtworkCoilFetcherFactory: dagger.Lazy<com.lostf1sh.pixelplayeross.data.image.LocalArtworkCoilFetcher.Factory>

    @Inject
    lateinit var themeStateHolder: dagger.Lazy<ThemeStateHolder>

    @Inject
    lateinit var artistImageRepository: dagger.Lazy<ArtistImageRepository>

    @Inject
    lateinit var libraryStateHolder: dagger.Lazy<LibraryStateHolder>

    @Inject
    lateinit var userPreferencesRepository: dagger.Lazy<UserPreferencesRepository>

    @Inject
    lateinit var syncManager: dagger.Lazy<com.lostf1sh.pixelplayeross.data.worker.SyncManager>

    @Inject
    lateinit var advancedPerformanceDiagnosticsController: dagger.Lazy<AdvancedPerformanceDiagnosticsController>

    @Inject
    lateinit var dacProfileRepository: dagger.Lazy<DacProfileRepository>

    @Inject
    lateinit var safetyVolumeController: dagger.Lazy<SafetyVolumeController>

    @Inject
    lateinit var dacHeadphoneAssociationController: dagger.Lazy<DacHeadphoneAssociationController>

    @Inject
    lateinit var dacEqController: dagger.Lazy<com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqController>

    @Inject
    lateinit var libraryMetadataRepair: dagger.Lazy<com.lostf1sh.pixelplayeross.data.metadata.LibraryMetadataRepair>

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pixelplayer_music_channel"

        /** What Android's notification settings call the channel. The id above is an identifier and keeps its old spelling. */
        const val NOTIFICATION_CHANNEL_NAME = "Polylute Music Playback"
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            libraryStateHolder.get().restoreAfterTrimIfNeeded()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.BUILD_TYPE != "benchmark") {
            CrashHandler.install(this)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        syncManager.get().start()

        advancedPerformanceDiagnosticsController.get().start(startupScope)
        dacProfileRepository.get().start()
        dacHeadphoneAssociationController.get().start()
        // B30: idle until a FiiO/Snowsky DAC is attached and USB Direct carries a stream; then one read-only GET pass.
        dacEqController.get().start(startupScope)
        safetyVolumeController.get().start()
        // FIX-3 (B11-fix): packed track numbers and missing years, repaired in the background.
        libraryMetadataRepair.get().start(startupScope)

        startupScope.launch {
            // Retire the legacy crossfade-duration preference before anything can read a
            // transition duration, so no screen and no playback path can observe the old key.
            runCatching { userPreferencesRepository.get().migrateLegacyCrossfadeDurationIfNeeded() }
                .onFailure { error ->
                    Timber.tag("Preferences")
                        .e(error, "Unable to migrate the legacy crossfade duration")
                }
            AlbumArtUtils.migrateLegacyCacheLocation(this@PixelPlayerApplication)
            LegacyCloudDataCleanup.purge(this@PixelPlayerApplication)
            val savedLimit = runCatching {
                userPreferencesRepository.get().albumArtCacheLimitMbFlow.first()
            }.getOrNull()
            if (savedLimit != null) {
                AlbumArtCacheManager.configuredCacheLimitMb = savedLimit.toLong()
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoader.get().newBuilder()
            .components {
                add(localArtworkCoilFetcherFactory.get())
                add(com.lostf1sh.pixelplayeross.data.image.LocalArtworkGenerationInterceptor())
            }
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        imageLoader.get().memoryCache?.trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            themeStateHolder.get().trimMemory(level)
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            artistImageRepository.get().clearCache()
            MediaMetadataRetrieverPool.clear()
        }

        libraryStateHolder.get().trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            imageLoader.get().memoryCache?.clear()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
