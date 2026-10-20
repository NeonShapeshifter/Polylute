package com.lostf1sh.pixelplayeross.data.headphones

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerManager
import com.lostf1sh.pixelplayeross.data.preferences.EqualizerPreferencesRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Persistence at the real boundary: an actual `DataStore` on a temp file, the real repository, the
 * real coordinator, and the real codec.
 *
 * The EQ round-trip cannot be proven with helper tests. The defect was that a band edit reached
 * *a* profile rather than *the* profile the screen was open on, so the only meaningful test drives
 * the repository and reads back what was actually written.
 */
class HeadphoneEqPersistenceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun tempStore(): Pair<DataStore<Preferences>, File> {
        val file = Files.createTempFile("hp-eq-test", ".preferences_pb").toFile()
        file.delete()
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        return store to file
    }

    private fun repository(store: DataStore<Preferences>) =
        HeadphoneProfileRepository(dataStore = store, json = json, appScope = scope)

    /**
     * The legacy reader is mocked rather than real: it is migration input, already covered by its
     * own tests, and a relaxed DataStore mock would leave it reading an empty flow.
     */
    private fun coordinator(repo: HeadphoneProfileRepository) = HeadphoneEqualizerCoordinator(
        profiles = repo,
        legacyPreferences = mockk<EqualizerPreferencesRepository>(relaxed = true).also {
            coEvery { it.readLegacyStateForMigration() } returns HeadphoneEqState()
        },
        equalizerManager = mockk<EqualizerManager>(relaxed = true),
        appScope = scope,
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** Two profiles, so "the right profile was written" is a question the test can actually ask. */
    private suspend fun seedTwoProfiles(
        repo: HeadphoneProfileRepository,
        coordinator: HeadphoneEqualizerCoordinator,
    ): Pair<String, String> {
        val a = coordinator.createAndSelect("Profile A")!!.profileId
        val b = repo.createAndSelect("Profile B", HeadphoneEqState())!!.profileId
        repo.select(a)
        return a to b
    }

    @Test
    fun `a band edit lands on the profile the screen is open on, not the selected one`() = runTest {
        val (store, file) = tempStore()
        val repo = repository(store)
        val coordinator = coordinator(repo)
        val (a, b) = seedTwoProfiles(repo, coordinator)

        // The screen is open on B; the selection is on A. This is the shape the device produced:
        // DacHeadphoneAssociationController re-selects an associated profile while a debounced band
        // edit is still in flight, and the edit used to follow the selection to the wrong record.
        val edited = listOf(7, 6, 5, 4, 3, 2, 1, 0, -1, -2)
        coordinator.updateEqualizer(b) { it.copy(bands10 = edited) }

        assertThat(repo.currentState().profiles.single { it.profileId == b }.equalizer.bands10)
            .isEqualTo(edited)
        assertThat(repo.currentState().profiles.single { it.profileId == a }.equalizer.bands10)
            .isEqualTo(List(10) { 0 })

        // And it is on disk, not only in the flow.
        assertThat(file.readText()).contains("7")
    }

    @Test
    fun `editing one profile cannot mutate another`() = runTest {
        val (store, _) = tempStore()
        val repo = repository(store)
        val coordinator = coordinator(repo)
        val (a, b) = seedTwoProfiles(repo, coordinator)

        coordinator.updateEqualizer(a) { it.copy(bands10 = List(10) { 3 }) }
        coordinator.updateEqualizer(b) { it.copy(bands10 = List(10) { -4 }) }

        val state = repo.currentState()
        assertThat(state.profiles.single { it.profileId == a }.equalizer.bands10)
            .isEqualTo(List(10) { 3 })
        assertThat(state.profiles.single { it.profileId == b }.equalizer.bands10)
            .isEqualTo(List(10) { -4 })
    }

    @Test
    fun `ten and fifteen band curves are independent and both round-trip`() = runTest {
        val (store, _) = tempStore()
        val repo = repository(store)
        val coordinator = coordinator(repo)
        val (_, b) = seedTwoProfiles(repo, coordinator)

        val ten = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val fifteen = List(15) { it - 7 }

        coordinator.updateEqualizer(b) { it.copy(bands10 = ten, mode = HeadphoneEqMode.GRAPHIC_10) }
        coordinator.updateEqualizer(b) { it.copy(bands15 = fifteen, mode = HeadphoneEqMode.GRAPHIC_15) }
        coordinator.updateEqualizer(b) { it.copy(mode = HeadphoneEqMode.GRAPHIC_10) }
        coordinator.updateEqualizer(b) { it.copy(mode = HeadphoneEqMode.GRAPHIC_15) }

        val eq = repo.currentState().profiles.single { it.profileId == b }.equalizer
        assertThat(eq.bands10).isEqualTo(ten)
        assertThat(eq.bands15).isEqualTo(fifteen)
        assertThat(eq.mode).isEqualTo(HeadphoneEqMode.GRAPHIC_15)
    }

    @Test
    fun `a reset to flat clears only the active mode`() = runTest {
        val (store, _) = tempStore()
        val repo = repository(store)
        val coordinator = coordinator(repo)
        val (_, b) = seedTwoProfiles(repo, coordinator)

        val ten = List(10) { 5 }
        val fifteen = List(15) { -5 }
        coordinator.updateEqualizer(b) { it.copy(bands10 = ten, bands15 = fifteen) }

        // Reset while the ten-band editor is the one on screen.
        coordinator.updateEqualizer(b) {
            it.copy(bands10 = List(10) { 0 }, presetName = "flat")
        }

        val eq = repo.currentState().profiles.single { it.profileId == b }.equalizer
        assertThat(eq.bands10).isEqualTo(List(10) { 0 })
        assertThat(eq.bands15).isEqualTo(fifteen)
        assertThat(eq.presetName).isEqualTo("flat")
    }

    @Test
    fun `a newer edit survives a later write of an older snapshot`() = runTest {
        val (store, _) = tempStore()
        val repo = repository(store)
        val coordinator = coordinator(repo)
        val (_, b) = seedTwoProfiles(repo, coordinator)

        val newer = List(10) { 8 }
        coordinator.updateEqualizer(b) { it.copy(bands10 = newer) }

        // A stale full-snapshot write of the kind the old onCleared flush performed.
        coordinator.replaceEffectiveState(HeadphoneEqState(bands10 = List(10) { 0 }))

        assertThat(repo.currentState().profiles.single { it.profileId == b }.equalizer.bands10)
            .isEqualTo(newer)
    }

    @Test
    fun `the serialized payload reconstructs the same curves`() = runTest {
        val (store, _) = tempStore()
        val repo = repository(store)
        val coordinator = coordinator(repo)
        val (_, b) = seedTwoProfiles(repo, coordinator)

        val ten = listOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        coordinator.updateEqualizer(b) { it.copy(bands10 = ten, presetName = "custom") }

        // A second repository over the same file is a process restart as far as this layer knows.
        val reopened = repository(store)
        val eq = reopened.currentState().profiles.single { it.profileId == b }.equalizer
        assertThat(eq.bands10).isEqualTo(ten)
        assertThat(eq.presetName).isEqualTo("custom")
    }
}
