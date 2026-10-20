package com.lostf1sh.pixelplayeross.ui.ds

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The accent preference contract.
 *
 * The accent is stored as a string key, which means every install that predates accents has no
 * value at all, and every future install may hold a key this build has never heard of. Both of
 * those have to resolve to the appearance the product already had, not to an error and not to a
 * silently different colour.
 */
class DapAccentTest {

    @Test
    fun `the default is the accent every existing install already had`() {
        assertThat(DapAccent.DEFAULT).isEqualTo(DapAccent.Sage)
        assertThat(DapAccent.DEFAULT.storageKey).isEqualTo("sage")
    }

    @Test
    fun `a store with no accent resolves to the default`() {
        // Every install upgrading into accents reads exactly this.
        assertThat(DapAccent.fromStorageKey(null)).isEqualTo(DapAccent.DEFAULT)
    }

    @Test
    fun `an unknown accent resolves to the default rather than throwing`() {
        // A key written by a newer build, or a corrupted store. Falling back is the only safe
        // answer: an accent is decoration, and it must never be able to break startup.
        assertThat(DapAccent.fromStorageKey("chartreuse")).isEqualTo(DapAccent.DEFAULT)
        assertThat(DapAccent.fromStorageKey("")).isEqualTo(DapAccent.DEFAULT)
        assertThat(DapAccent.fromStorageKey("SAGE")).isEqualTo(DapAccent.DEFAULT)
    }

    @Test
    fun `every accent round-trips through its storage key`() {
        DapAccent.entries.forEach { accent ->
            assertThat(DapAccent.fromStorageKey(accent.storageKey)).isEqualTo(accent)
        }
    }

    @Test
    fun `storage keys are unique, non-blank and stable-looking`() {
        val keys = DapAccent.entries.map { it.storageKey }
        assertThat(keys).containsNoDuplicates()
        assertThat(keys.none { it.isBlank() }).isTrue()
        // Snake-case, lowercase: these are persisted, so they must not be renamed casually.
        keys.forEach { key ->
            assertThat(key).matches("[a-z]+")
        }
    }

    @Test
    fun `the set stays in the curated band the design specifies`() {
        // "Approximately 10-15 curated accents" — a bound worth pinning, because the failure mode
        // is a list that quietly grows into an arbitrary colour picker.
        assertThat(DapAccent.entries.size).isAtLeast(10)
        assertThat(DapAccent.entries.size).isAtMost(16)
    }

    @Test
    fun `no two accents are the same colour or the same label`() {
        assertThat(DapAccent.entries.map { it.color }).containsNoDuplicates()
        assertThat(DapAccent.entries.map { it.label }).containsNoDuplicates()
    }

    @Test
    fun `the original accents keep their colours`() {
        // These shipped before the picker existed. Changing one would silently re-tint the app for
        // anyone who never opens the setting.
        assertThat(DapAccent.Sage.color).isEqualTo(androidx.compose.ui.graphics.Color(0xFF93B9A0))
        assertThat(DapAccent.Sky.color).isEqualTo(androidx.compose.ui.graphics.Color(0xFF8FB3C4))
        assertThat(DapAccent.Lilac.color).isEqualTo(androidx.compose.ui.graphics.Color(0xFFB9AEC9))
        assertThat(DapAccent.Sand.color).isEqualTo(androidx.compose.ui.graphics.Color(0xFFC9B48F))
        assertThat(DapAccent.Clay.color).isEqualTo(androidx.compose.ui.graphics.Color(0xFFC49A8F))
        assertThat(DapAccent.Graphite.color).isEqualTo(androidx.compose.ui.graphics.Color(0xFFA8B0B8))
    }

    @Test
    fun `every accent stays opaque, so no accent can be silently translucent on a surface`() {
        DapAccent.entries.forEach { accent ->
            assertThat(accent.color.alpha).isWithin(0.001f).of(1f)
        }
    }
}
