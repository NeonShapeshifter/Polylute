package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UserPreferencesRepositoryTest {

    @Test
    fun `clearPreferencesExceptKeys preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.clearPreferencesExceptKeys(emptySet())

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals(NavBarStyle.DEFAULT, repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `importPreferencesFromBackup clearExisting preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_style",
                        type = "string",
                        stringValue = "restored"
                    )
                ),
                clearExisting = true
            )

            assertTrue(repository.initialSetupDoneFlow.first())
            // `nav_bar_style` is one of the retired appearance keys, so it reads as the product
            // default even after a restore — that is the freeze, and it is why this assertion no
            // longer expects "restored". The import still writes the key, so a backup made on an
            // older build still round-trips; it just no longer decides anything on this one.
            assertEquals(NavBarStyle.DEFAULT, repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * The nav bar corner radius is retired: it is read as the product default, whatever the store
     * holds.
     *
     * This test used to pin the clamp — that a stored value outside 0..60 came back at the bound.
     * The clamp no longer describes the contract. The Settings row that wrote this preference was
     * removed, so the value still reaches a live render path (the player shelf, the external-player
     * sheet) with nothing able to change it back; it is read as a constant now so that a value left
     * in DataStore, or carried in by a restored backup, cannot decide part of the final appearance.
     * The clamp function itself is unchanged and still guards what the setter writes.
     */
    @Test
    fun `navBarCornerRadiusFlow reads the frozen product default whatever the store holds`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertEquals(DEFAULT_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.setNavBarCornerRadius(-1)
            assertEquals(DEFAULT_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.setNavBarCornerRadius(999)
            assertEquals(DEFAULT_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.setNavBarCornerRadius(12)
            assertEquals(DEFAULT_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            // A restored backup cannot reintroduce it either — which is the case this freeze exists
            // for, since the value still reaches the player shelf.
            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_corner_radius",
                        type = "int",
                        intValue = -1
                    )
                ),
                clearExisting = false
            )
            assertEquals(DEFAULT_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the clamp still bounds what the setter is allowed to store`() = runTest {
        assertEquals(MIN_NAV_BAR_CORNER_RADIUS, sanitizeNavBarCornerRadius(-1))
        assertEquals(MAX_NAV_BAR_CORNER_RADIUS, sanitizeNavBarCornerRadius(999))
        assertEquals(12, sanitizeNavBarCornerRadius(12))
    }

    /**
     * `last_storage_filter` could hold `"ONLINE"` on an install that predates the removal of
     * the server-music architecture. That filter selected cloud-only songs, so restoring it
     * would leave an upgrading user staring at an empty library. It must degrade to ALL.
     */
    @Test
    fun `legacy ONLINE storage filter restores as ALL`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
            )
            val repository = UserPreferencesRepository(dataStore = dataStore, json = Json)

            // Write the key by the exact name older versions persisted it under.
            dataStore.edit { it[stringPreferencesKey("last_storage_filter")] = "ONLINE" }
            assertEquals(
                com.lostf1sh.pixelplayeross.data.model.StorageFilter.ALL,
                repository.lastStorageFilterFlow.first()
            )

            // The surviving local filters still round-trip.
            repository.saveLastStorageFilter(
                com.lostf1sh.pixelplayeross.data.model.StorageFilter.OFFLINE
            )
            assertEquals(
                com.lostf1sh.pixelplayeross.data.model.StorageFilter.OFFLINE,
                repository.lastStorageFilterFlow.first()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
