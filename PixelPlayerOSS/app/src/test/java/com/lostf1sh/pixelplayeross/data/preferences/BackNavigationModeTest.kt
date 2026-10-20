package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class BackNavigationModeTest {

    @Test
    fun `the mode set is exactly buttons, gestures and both`() {
        // Three modes, and no fourth way to configure Back. The user-facing setting is described in
        // terms of these three, so a mode added here without a decision about the other two would
        // silently change what each of them means.
        assertEquals(
            listOf("buttons", "gestures", "both"),
            BackNavigationMode.entries.map { it.id },
        )
    }

    @Test
    fun `an install with no stored mode resolves to both`() {
        // Nothing is written on upgrade. An existing install reads as BOTH, which keeps every Back
        // affordance it already had and adds the gesture alongside rather than replacing anything.
        assertEquals(BackNavigationMode.BOTH, BackNavigationMode.DEFAULT)
        assertEquals(BackNavigationMode.BOTH, BackNavigationMode.fromId(null))
        assertEquals(BackNavigationMode.BOTH, BackNavigationMode.fromId(""))
    }

    @Test
    fun `an unrecognised stored mode degrades instead of breaking navigation`() {
        // The store is read on every launch. A value written by a newer build, or a corrupted one,
        // must leave the user with a working Back rather than with none.
        assertEquals(BackNavigationMode.BOTH, BackNavigationMode.fromId("swipe-from-orbit"))
    }

    @Test
    fun `every mode round-trips through its stored id`() {
        BackNavigationMode.entries.forEach { mode ->
            assertEquals(mode, BackNavigationMode.fromId(mode.id))
        }
    }

    @Test
    fun `gestures mode hides a back-only affordance and the other two draw it`() {
        assertFalse(BackNavigationMode.GESTURES.showsVisibleBackAffordance)
        assertTrue(BackNavigationMode.BUTTONS.showsVisibleBackAffordance)
        assertTrue(BackNavigationMode.BOTH.showsVisibleBackAffordance)
    }

    @Test
    fun `buttons mode is the only one that turns the app gesture off`() {
        assertFalse(BackNavigationMode.BUTTONS.edgeGestureEnabled)
        assertTrue(BackNavigationMode.GESTURES.edgeGestureEnabled)
        assertTrue(BackNavigationMode.BOTH.edgeGestureEnabled)
    }

    @Test
    fun `the mode persists through the preference repository`() = runTest {
        val tempDir = Files.createTempDirectory("back-navigation-mode-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
                ),
                json = Json,
            )

            // The declared default is what an install with nothing stored reads, and the repository
            // is the only thing that says so.
            assertEquals(BackNavigationMode.BOTH, repository.backNavigationModeFlow.first())

            repository.setBackNavigationMode(BackNavigationMode.GESTURES)
            assertEquals(BackNavigationMode.GESTURES, repository.backNavigationModeFlow.first())

            repository.setBackNavigationMode(BackNavigationMode.BUTTONS)
            assertEquals(BackNavigationMode.BUTTONS, repository.backNavigationModeFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
