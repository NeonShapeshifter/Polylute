package com.lostf1sh.pixelplayeross.data.preferences

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * The position checkpoint in its own tiny SharedPreferences file (AUD-113): written every 5 s while
 * playing, it must not rewrite the settings DataStore (which holds the queue snapshot) nor wake its
 * readers. `apply()` persists asynchronously and is flushed by the platform before the process is
 * killed by the system.
 */
@Singleton
class SharedPreferencesPlaybackPositionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : PlaybackPositionStore {
    private val prefs = context.getSharedPreferences("playback_position", Context.MODE_PRIVATE)

    override fun write(mark: PlaybackPositionMark) {
        prefs.edit().putString(KEY, json.encodeToString(PlaybackPositionMark.serializer(), mark)).apply()
    }

    override fun read(): PlaybackPositionMark? = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(PlaybackPositionMark.serializer(), it) }.getOrNull()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "mark"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackPositionStoreModule {
    @Binds
    abstract fun bindPlaybackPositionStore(store: SharedPreferencesPlaybackPositionStore): PlaybackPositionStore
}
