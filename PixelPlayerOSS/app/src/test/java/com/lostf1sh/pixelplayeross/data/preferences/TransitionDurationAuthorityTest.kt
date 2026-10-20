package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.TransitionDao
import com.lostf1sh.pixelplayeross.data.database.TransitionRuleEntity
import com.lostf1sh.pixelplayeross.data.model.Curve
import com.lostf1sh.pixelplayeross.data.model.TransitionMode
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.model.TransitionSource
import com.lostf1sh.pixelplayeross.data.repository.TransitionRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * There must be exactly one global transition duration, and it must be the one playback reads.
 *
 * The defect these pin was not a wrong number, it was two numbers: the Settings slider wrote
 * `crossfade_duration`, and `globalTransitionSettingsFlow` re-imposed that key over the duration
 * stored in the global transition configuration, so the value the global editor showed and saved
 * was discarded on every read. Editing the editor changed nothing; editing the slider changed what
 * playback used but was invisible to the editor.
 *
 * These tests drive the real repository, the real DataStore and the real `TransitionRepositoryImpl`
 * — the same object playback resolves through — so they fail if the two ever diverge again.
 */
class TransitionDurationAuthorityTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun tempStore(): DataStore<Preferences> {
        val file = Files.createTempFile("transition-authority", ".preferences_pb").toFile()
        file.delete()
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    private fun preferences(store: DataStore<Preferences>) =
        UserPreferencesRepository(dataStore = store, json = json)

    /** No rules stored, so resolution falls through to the global configuration. */
    private fun emptyDao(): TransitionDao = mockk<TransitionDao>().apply {
        every { getSpecificRule(any(), any(), any()) } returns MutableStateFlow(null)
        every { getPlaylistDefaultRule(any()) } returns MutableStateFlow(null)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // --- G: the duration the editor writes is the duration playback resolves --------------------

    @Test
    fun `a duration saved by the global editor is the duration playback resolves`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)
        val repository = TransitionRepositoryImpl(emptyDao(), prefs)

        repository.saveGlobalSettings(
            TransitionSettings(
                mode = TransitionMode.OVERLAP,
                durationMs = 7500,
                curveIn = Curve.LINEAR,
                curveOut = Curve.EXP,
            )
        )

        val resolved = repository.resolveTransitionSettings("playlist-1", "from", "to").first()

        assertThat(resolved.source).isEqualTo(TransitionSource.GLOBAL_DEFAULT)
        assertThat(resolved.settings.durationMs).isEqualTo(7500)
        // The rest of the configuration survives the same trip, so the duration is not being read
        // from somewhere that only happens to know about durations.
        assertThat(resolved.settings.mode).isEqualTo(TransitionMode.OVERLAP)
        assertThat(resolved.settings.curveIn).isEqualTo(Curve.LINEAR)
        assertThat(resolved.settings.curveOut).isEqualTo(Curve.EXP)
    }

    @Test
    fun `changing only the duration changes the value playback resolves`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)
        val repository = TransitionRepositoryImpl(emptyDao(), prefs)

        repository.saveGlobalSettings(TransitionSettings(durationMs = 3000))
        assertThat(repository.getGlobalSettings().first().durationMs).isEqualTo(3000)

        repository.saveGlobalSettings(repository.getGlobalSettings().first().copy(durationMs = 9000))

        assertThat(repository.resolveTransitionSettings("p", "a", "b").first().settings.durationMs)
            .isEqualTo(9000)
    }

    // --- H: the retired preference is not an authority -------------------------------------------

    @Test
    fun `the retired crossfade duration preference does not override the global configuration`() =
        runTest {
            val store = tempStore()
            val prefs = preferences(store)
            val repository = TransitionRepositoryImpl(emptyDao(), prefs)

            repository.saveGlobalSettings(TransitionSettings(durationMs = 4000))

            // Exactly what the old slider used to write, written directly.
            store.edit { it[intPreferencesKey("crossfade_duration")] = 11000 }

            assertThat(repository.getGlobalSettings().first().durationMs).isEqualTo(4000)
            assertThat(repository.resolveTransitionSettings("p", "a", "b").first().settings.durationMs)
                .isEqualTo(4000)
        }

    @Test
    fun `the legacy duration is adopted once when there is no authored configuration`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)

        // An install from before the global editor was reachable: a slider value, no authored
        // global configuration. That value was the real duration the user chose, so it is carried
        // over rather than dropped.
        store.edit { it[intPreferencesKey("crossfade_duration")] = 8000 }

        prefs.migrateLegacyCrossfadeDurationIfNeeded()

        assertThat(prefs.globalTransitionSettingsFlow.first().durationMs).isEqualTo(8000)
    }

    @Test
    fun `migration never overwrites an authored global configuration`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)

        prefs.saveGlobalTransitionSettings(
            TransitionSettings(
                mode = TransitionMode.SMOOTH,
                durationMs = 2500,
                curveIn = Curve.LOG,
                curveOut = Curve.S_CURVE,
            )
        )
        store.edit { it[intPreferencesKey("crossfade_duration")] = 11000 }

        prefs.migrateLegacyCrossfadeDurationIfNeeded()

        val settings = prefs.globalTransitionSettingsFlow.first()
        assertThat(settings.durationMs).isEqualTo(2500)
        assertThat(settings.mode).isEqualTo(TransitionMode.SMOOTH)
        assertThat(settings.curveIn).isEqualTo(Curve.LOG)
        assertThat(settings.curveOut).isEqualTo(Curve.S_CURVE)
    }

    @Test
    fun `migration retires the legacy key and is idempotent`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)

        store.edit { it[intPreferencesKey("crossfade_duration")] = 6000 }
        prefs.migrateLegacyCrossfadeDurationIfNeeded()

        // Gone, so nothing can read it back as a duration — not a later screen, not a restore.
        assertThat(store.data.first().contains(intPreferencesKey("crossfade_duration"))).isFalse()

        // Running again must not disturb what the first run produced.
        val afterFirst = prefs.globalTransitionSettingsFlow.first()
        prefs.migrateLegacyCrossfadeDurationIfNeeded()
        assertThat(prefs.globalTransitionSettingsFlow.first()).isEqualTo(afterFirst)
    }

    @Test
    fun `migration leaves playlist overrides alone`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)

        // A playlist rule is a row in `transition_rules`, which this migration cannot reach: it
        // only ever touches two preference keys. The override is asserted through the resolver so
        // the claim is about behaviour, not about which keys were written.
        val override = TransitionSettings(durationMs = 600, curveIn = Curve.EXP)
        val dao = mockk<TransitionDao>().apply {
            every { getSpecificRule(any(), any(), any()) } returns MutableStateFlow(
                TransitionRuleEntity(
                    id = 1L,
                    playlistId = "playlist-1",
                    fromTrackId = "from",
                    toTrackId = "to",
                    settings = override,
                )
            )
            every { getPlaylistDefaultRule(any()) } returns MutableStateFlow(null)
        }
        val repository = TransitionRepositoryImpl(dao, prefs)

        store.edit { it[intPreferencesKey("crossfade_duration")] = 11000 }
        prefs.migrateLegacyCrossfadeDurationIfNeeded()

        val resolved = repository.resolveTransitionSettings("playlist-1", "from", "to").first()
        assertThat(resolved.source).isEqualTo(TransitionSource.PLAYLIST_SPECIFIC)
        assertThat(resolved.settings.durationMs).isEqualTo(600)
    }

    @Test
    fun `a fresh install resolves the documented default duration`() = runTest {
        val prefs = preferences(tempStore())

        // Nothing stored at all: the global configuration's own default, unmodified by any
        // preference outside it.
        assertThat(prefs.globalTransitionSettingsFlow.first())
            .isEqualTo(TransitionSettings())
        assertThat(TransitionSettings().durationMs).isEqualTo(2000)
    }

    @Test
    fun `a malformed stored configuration falls back rather than failing`() = runTest {
        val store = tempStore()
        val prefs = preferences(store)

        store.edit { it[stringPreferencesKey("global_transition_settings_json")] = "{ not json" }

        assertThat(prefs.globalTransitionSettingsFlow.first()).isEqualTo(TransitionSettings())
    }
}
