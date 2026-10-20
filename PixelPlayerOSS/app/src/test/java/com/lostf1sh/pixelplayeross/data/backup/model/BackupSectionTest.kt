package com.lostf1sh.pixelplayeross.data.backup.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupSectionTest {

    @Test
    fun `all sections have unique keys`() {
        val keys = BackupSection.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size, "BackupSection keys must be unique")
    }

    @Test
    fun `all sections have unique label resources`() {
        // Labels are resource ids now. Two sections pointing at the same string would render as two
        // identical choices, which is a defect the id comparison catches just as well as the
        // literal comparison did.
        val labels = BackupSection.entries.map { it.labelRes }
        assertEquals(labels.size, labels.distinct().size, "BackupSection labels must be unique")
    }

    @Test
    fun `fromKey returns correct section for known keys`() {
        assertEquals(BackupSection.PLAYLISTS, BackupSection.fromKey("playlists"))
        assertEquals(BackupSection.GLOBAL_SETTINGS, BackupSection.fromKey("global_settings"))
        assertEquals(BackupSection.FAVORITES, BackupSection.fromKey("favorites"))
        assertEquals(BackupSection.LYRICS, BackupSection.fromKey("lyrics"))
        assertEquals(BackupSection.SEARCH_HISTORY, BackupSection.fromKey("search_history"))
        assertEquals(BackupSection.TRANSITIONS, BackupSection.fromKey("transitions"))
        assertEquals(BackupSection.ENGAGEMENT_STATS, BackupSection.fromKey("engagement_stats"))
        assertEquals(BackupSection.PLAYBACK_HISTORY, BackupSection.fromKey("playback_history"))
        assertEquals(BackupSection.QUICK_FILL, BackupSection.fromKey("quick_fill"))
        assertEquals(BackupSection.ARTIST_IMAGES, BackupSection.fromKey("artist_images"))
        assertEquals(BackupSection.EQUALIZER, BackupSection.fromKey("equalizer"))
    }

    @Test
    fun `fromKey returns null for unknown key`() {
        assertNull(BackupSection.fromKey("nonexistent"))
        assertNull(BackupSection.fromKey(""))
    }

    @Test
    fun `defaultSelection contains all sections`() {
        assertEquals(BackupSection.entries.toSet(), BackupSection.defaultSelection)
    }

    @Test
    fun `there are exactly 12 backup sections`() {
        // Raised from 11 in Phase 12.4, which added SMART_COLLECTIONS. This assertion exists so a
        // section cannot be added or removed without someone noticing — it is a tripwire, not a
        // specification, so the number is updated deliberately alongside the change that moves it.
        assertEquals(12, BackupSection.entries.size)
    }

    @Test
    fun `new sections have sinceVersion 3`() {
        assertEquals(3, BackupSection.QUICK_FILL.sinceVersion)
        assertEquals(3, BackupSection.ARTIST_IMAGES.sinceVersion)
        assertEquals(3, BackupSection.EQUALIZER.sinceVersion)
    }

    @Test
    fun `original sections have sinceVersion 1`() {
        val originalSections = listOf(
            BackupSection.PLAYLISTS,
            BackupSection.GLOBAL_SETTINGS,
            BackupSection.FAVORITES,
            BackupSection.LYRICS,
            BackupSection.SEARCH_HISTORY,
            BackupSection.TRANSITIONS,
            BackupSection.ENGAGEMENT_STATS,
            BackupSection.PLAYBACK_HISTORY
        )
        originalSections.forEach { section ->
            assertEquals(1, section.sinceVersion, "${section.key} should have sinceVersion 1")
        }
    }

    @Test
    fun `all sections have non-empty labels and descriptions`() {
        BackupSection.entries.forEach { section ->
            assertTrue(section.labelRes != 0, "${section.key} should have a label resource")
            assertTrue(section.descriptionRes != 0, "${section.key} should have a description resource")
        }
    }
}
