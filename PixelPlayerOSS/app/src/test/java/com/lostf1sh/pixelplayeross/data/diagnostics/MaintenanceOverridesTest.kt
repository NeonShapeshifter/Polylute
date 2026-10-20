package com.lostf1sh.pixelplayeross.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the Maintenance override model.
 *
 * The properties that matter are negative ones: an override must be removable, must not invent
 * state, and must not silently persist an id the current build cannot represent.
 */
class MaintenanceOverridesTest {

    @Test fun `nothing is overridden by default`() {
        val overrides = MaintenanceOverrides()
        assertThat(overrides.isEmpty).isTrue()
        assertThat(overrides.count).isEqualTo(0)
        assertThat(overrides.forcedAndroidAudio()).isFalse()
        assertThat(overrides.directTransitionDisabled()).isFalse()
    }

    @Test fun `applying an override records exactly that override`() {
        val overrides = MaintenanceOverrides()
            .applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
        assertThat(overrides.count).isEqualTo(1)
        assertThat(overrides.forcedAndroidAudio()).isTrue()
        assertThat(overrides.directTransitionDisabled()).isFalse()
        assertThat(overrides.isActive(MaintenanceOverride.FORCE_ANDROID_AUDIO)).isTrue()
    }

    /** Removal is a delete of an id, not a restore of a shadowed value — so it is exact. */
    @Test fun `removing an override restores the exact previous state`() {
        val before = MaintenanceOverrides()
        val during = before.applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
        val after = during.applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = false)
        assertThat(after).isEqualTo(before)
    }

    @Test fun `applying an already-active override changes nothing`() {
        val once = MaintenanceOverrides().applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
        val twice = once.applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
        assertThat(twice).isEqualTo(once)
    }

    @Test fun `removing an inactive override changes nothing`() {
        val overrides = MaintenanceOverrides()
        assertThat(overrides.applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = false))
            .isEqualTo(overrides)
    }

    @Test fun `overrides are independent of one another`() {
        val overrides = MaintenanceOverrides()
            .applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
            .applied(MaintenanceOverride.DISABLE_DIRECT_TRANSITION, active = true)
        assertThat(overrides.count).isEqualTo(2)
        assertThat(overrides.forcedAndroidAudio()).isTrue()
        assertThat(overrides.directTransitionDisabled()).isTrue()

        val one = overrides.applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = false)
        assertThat(one.forcedAndroidAudio()).isFalse()
        assertThat(one.directTransitionDisabled()).isTrue()
    }

    @Test fun `diagnostic overrides are counted separately from feature kills`() {
        val overrides = MaintenanceOverrides()
            .applied(MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION, active = true)
            .applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
        assertThat(overrides.count).isEqualTo(2)
        assertThat(overrides.diagnosticCount).isEqualTo(1)
    }

    /**
     * A stored id this build cannot represent must not survive: it would be an armed switch with
     * no surface to show it and no way for a user to clear it.
     */
    @Test fun `an unknown stored id is dropped on read`() {
        val overrides = MaintenanceOverrides.fromStoredIds(
            setOf("force_android_audio", "some_removed_override_from_a_past_build")
        )
        assertThat(overrides.activeIds).containsExactly(MaintenanceOverride.FORCE_ANDROID_AUDIO.id)
        assertThat(overrides.count).isEqualTo(1)
    }

    @Test fun `a null or empty stored set reads as nothing overridden`() {
        assertThat(MaintenanceOverrides.fromStoredIds(null).isEmpty).isTrue()
        assertThat(MaintenanceOverrides.fromStoredIds(emptySet()).isEmpty).isTrue()
    }

    @Test fun `every stored valid id is preserved`() {
        val allIds = MaintenanceOverride.entries.map { it.id }.toSet()
        assertThat(MaintenanceOverrides.fromStoredIds(allIds).activeIds).containsExactlyElementsIn(allIds)
    }

    @Test fun `override ids are unique`() {
        val ids = MaintenanceOverride.entries.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test fun `override ids are stable and lookup by id round-trips`() {
        MaintenanceOverride.entries.forEach { override ->
            assertThat(MaintenanceOverride.fromId(override.id)).isEqualTo(override)
        }
        assertThat(MaintenanceOverride.fromId("not_an_override")).isNull()
    }

    /** The kinds are the whole point: a feature kill and a diagnostic override are not the same. */
    @Test fun `each override declares its kind`() {
        assertThat(MaintenanceOverride.FORCE_ANDROID_AUDIO.kind)
            .isEqualTo(MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE)
        assertThat(MaintenanceOverride.DISABLE_DIRECT_TRANSITION.kind)
            .isEqualTo(MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE)
        assertThat(MaintenanceOverride.DISABLE_DSP_GRAPH.kind)
            .isEqualTo(MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE)
        assertThat(MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION.kind)
            .isEqualTo(MaintenanceOverrideKind.FEATURE_KILL)
    }

    /**
     * No override may gate core playback. Every diagnostic override here redirects or disables a
     * *transition*; none of them stops audio being produced.
     */
    @Test fun `no override targets core playback`() {
        val corePlaybackIds = setOf("playback", "audio_output", "library", "database")
        MaintenanceOverride.entries.forEach { override ->
            assertThat(corePlaybackIds.any { override.id.contains(it) }).isFalse()
        }
    }
}
