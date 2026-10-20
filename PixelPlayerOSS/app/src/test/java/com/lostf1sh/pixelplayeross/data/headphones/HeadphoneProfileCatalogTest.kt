package com.lostf1sh.pixelplayeross.data.headphones

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class HeadphoneProfileCatalogTest {
    private val eqA = HeadphoneEqState(
        enabled = true,
        presetName = "rock",
        bands10 = List(10) { it - 5 },
        bassBoostEnabled = true,
        bassBoostStrength = 350,
    )
    private val eqB = HeadphoneEqState(
        enabled = true,
        presetName = "jazz",
        bands10 = List(10) { 5 - it },
        virtualizerEnabled = true,
        virtualizerStrength = 275,
    )
    private val speakerCalibration = SafetyMaxCalibration(
        outputContextId = "android:built_in_speaker",
        backendKind = SafetyVolumeBackendKind.ANDROID_MEDIA,
        ceilingRaw = 8,
        backendMinRaw = 0,
        backendMaxRaw = 15,
        calibratedAtEpochMs = 50L,
    )

    @Test
    fun `empty and malformed payloads fail to safe empty state`() {
        val json = Json { ignoreUnknownKeys = true }

        assertThat(HeadphoneProfilesCodec.decode(json, null)).isEqualTo(HeadphoneProfileState())
        assertThat(HeadphoneProfilesCodec.decode(json, "not-json")).isEqualTo(HeadphoneProfileState())
    }

    @Test
    fun `create trims name selects profile and adopts current equalizer`() {
        val state = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "  DT 770 Pro  ", eqA, 10L, "headphone-a"
        )

        assertThat(state.profiles).hasSize(1)
        assertThat(state.selectedProfileId).isEqualTo("headphone-a")
        assertThat(state.selectedProfile?.displayName).isEqualTo("DT 770 Pro")
        assertThat(state.selectedProfile?.equalizer).isEqualTo(eqA)
    }

    @Test
    fun `encoded catalog retains selection eq and associations after reload`() {
        val json = Json { ignoreUnknownKeys = true }
        val state = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "HE4XX", eqA, 10L, "headphone-a", "dac-a"
        )

        val reloaded = HeadphoneProfilesCodec.decode(json, HeadphoneProfilesCodec.encode(json, state))

        assertThat(reloaded).isEqualTo(state)
        assertThat(reloaded.dacAssociations["dac-a"]).isEqualTo("headphone-a")
    }

    @Test
    fun `phase 82 payload migrates with identity EQ selection and association intact`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"schemaVersion":1,"profiles":[{"profileId":"headphone-a","displayName":"A","equalizer":{"enabled":true,"presetName":"rock","customBands":[0,0,0,0,0,0,0,0,0,0]},"createdAtEpochMs":1,"updatedAtEpochMs":2}],"selectedProfileId":"headphone-a","dacAssociations":{"dac-a":"headphone-a"}}"""

        val migrated = HeadphoneProfilesCodec.decode(json, legacy)

        assertThat(migrated.selectedProfile?.profileId).isEqualTo("headphone-a")
        assertThat(migrated.selectedProfile?.equalizer?.presetName).isEqualTo("rock")
        assertThat(migrated.selectedProfile?.safetyMax?.calibrations).isEmpty()
        assertThat(migrated.selectedProfile?.safetyMax?.enabled).isTrue()
        assertThat(migrated.dacAssociations).containsExactly("dac-a", "headphone-a")
    }

    @Test
    fun `safety calibration persists without changing EQ identity or association`() {
        val json = Json { ignoreUnknownKeys = true }
        val created = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a", "dac-a"
        )
        val calibrated = HeadphoneProfileCatalog.calibrateSafetyMax(
            created, "headphone-a", speakerCalibration, 60L
        )
        val reloaded = HeadphoneProfilesCodec.decode(
            json, HeadphoneProfilesCodec.encode(json, calibrated)
        )

        assertThat(reloaded.selectedProfile?.equalizer).isEqualTo(eqA)
        assertThat(reloaded.selectedProfile?.safetyMax?.calibrations)
            .containsExactly(speakerCalibration.storageKey, speakerCalibration)
        assertThat(reloaded.dacAssociations).containsExactly("dac-a", "headphone-a")
    }

    @Test
    fun `different output contexts never inherit one another calibration`() {
        val created = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a"
        )
        val calibrated = HeadphoneProfileCatalog.calibrateSafetyMax(
            created, "headphone-a", speakerCalibration.copy(outputContextId = "dac:fosi"), 60L
        )

        val kind = speakerCalibration.backendKind
        assertThat(calibrated.selectedProfile?.safetyMax?.calibrationFor("dac:fosi", kind)).isNotNull()
        assertThat(calibrated.selectedProfile?.safetyMax?.calibrationFor("dac:melody", kind)).isNull()
    }

    @Test
    fun `disable retains calibration EQ and current profile selection`() {
        val created = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a"
        )
        val calibrated = HeadphoneProfileCatalog.calibrateSafetyMax(
            created, "headphone-a", speakerCalibration, 60L
        )
        val disabled = HeadphoneProfileCatalog.setSafetyMaxEnabled(
            calibrated, "headphone-a", false, 70L
        )

        assertThat(disabled.selectedProfileId).isEqualTo("headphone-a")
        assertThat(disabled.selectedProfile?.equalizer).isEqualTo(eqA)
        assertThat(disabled.selectedProfile?.safetyMax?.enabled).isFalse()
        assertThat(disabled.selectedProfile?.safetyMax?.calibrations).isNotEmpty()
    }

    @Test
    fun `invalid zero calibration is rejected`() {
        val created = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a"
        )
        val invalid = speakerCalibration.copy(ceilingRaw = 0)
        val result = HeadphoneProfileCatalog.calibrateSafetyMax(
            created, "headphone-a", invalid, 60L
        )

        assertThat(result.selectedProfile?.safetyMax?.calibrations).isEmpty()
    }

    @Test
    fun `duplicate display names retain distinct identities`() {
        val first = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "Studio", eqA, 10L, "headphone-a"
        )
        val second = HeadphoneProfileCatalog.createAndSelect(
            first, "Studio", eqB, 20L, "headphone-b"
        )

        assertThat(second.profiles.map { it.profileId }).containsExactly("headphone-a", "headphone-b")
        assertThat(second.profiles.map { it.displayName }).containsExactly("Studio", "Studio")
    }

    @Test
    fun `rename preserves profile identity equalizer and DAC association`() {
        val created = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "Old", eqA, 10L, "headphone-a", "dac-a"
        )
        val renamed = HeadphoneProfileCatalog.rename(created, "headphone-a", "New", 20L)

        assertThat(renamed.selectedProfile?.profileId).isEqualTo("headphone-a")
        assertThat(renamed.selectedProfile?.displayName).isEqualTo("New")
        assertThat(renamed.selectedProfile?.equalizer).isEqualTo(eqA)
        assertThat(renamed.dacAssociations["dac-a"]).isEqualTo("headphone-a")
    }

    @Test
    fun `selection persists without a DAC context`() {
        val a = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a"
        )
        val both = HeadphoneProfileCatalog.createAndSelect(a, "B", eqB, 20L, "headphone-b")
        val selected = HeadphoneProfileCatalog.select(both, "headphone-a", 30L)

        assertThat(selected.selectedProfileId).isEqualTo("headphone-a")
        assertThat(selected.dacAssociations).isEmpty()
    }

    @Test
    fun `each selected profile keeps isolated equalizer state`() {
        val first = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a"
        )
        val both = HeadphoneProfileCatalog.createAndSelect(first, "B", eqB, 20L, "headphone-b")
        val changedB = HeadphoneProfileCatalog.updateSelectedEqualizer(
            both, eqB.copy(loudnessEnhancerEnabled = true), 30L
        )
        val selectedA = HeadphoneProfileCatalog.select(changedB, "headphone-a", 40L)
        val selectedBAgain = HeadphoneProfileCatalog.select(selectedA, "headphone-b", 50L)

        assertThat(selectedA.selectedProfile?.equalizer).isEqualTo(eqA)
        assertThat(selectedBAgain.selectedProfile?.equalizer)
            .isEqualTo(eqB.copy(loudnessEnhancerEnabled = true))
        assertThat(selectedBAgain.profiles.first { it.profileId == "headphone-a" }.equalizer)
            .isEqualTo(eqA)
    }

    @Test
    fun `DAC associations restore their remembered profile deterministically`() {
        val a = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a", "dac-a"
        )
        val b = HeadphoneProfileCatalog.createAndSelect(
            a, "B", eqB, 20L, "headphone-b", "dac-b"
        )

        assertThat(HeadphoneProfileCatalog.associatedProfile(b, "dac-a")?.profileId)
            .isEqualTo("headphone-a")
        assertThat(HeadphoneProfileCatalog.associatedProfile(b, "dac-b")?.profileId)
            .isEqualTo("headphone-b")
    }

    @Test
    fun `forget selected profile clears selection and every stale association only`() {
        val a = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", eqA, 10L, "headphone-a", "dac-a"
        )
        val b = HeadphoneProfileCatalog.createAndSelect(
            a, "B", eqB, 20L, "headphone-b", "dac-b"
        )
        val forgotten = HeadphoneProfileCatalog.forget(b, "headphone-b")

        assertThat(forgotten.selectedProfileId).isNull()
        assertThat(forgotten.profiles.map { it.profileId }).containsExactly("headphone-a")
        assertThat(forgotten.dacAssociations).containsExactly("dac-a", "headphone-a")
    }

    @Test
    fun `normalization rejects stale selection associations and invalid values`() {
        val badEq = HeadphoneEqState(
            presetName = " ", bands10 = listOf(-99, 99), bassBoostStrength = 5000
        )
        val state = HeadphoneProfileState(
            profiles = listOf(HeadphoneProfile("headphone-a", "A", badEq, 1L, 1L)),
            selectedProfileId = "missing",
            dacAssociations = mapOf("dac-a" to "missing", "" to "headphone-a"),
        )

        val normalized = HeadphoneProfileCatalog.normalize(state)

        assertThat(normalized.selectedProfileId).isNull()
        assertThat(normalized.dacAssociations).isEmpty()
        assertThat(normalized.profiles.single().equalizer.presetName).isEqualTo("flat")
        assertThat(normalized.profiles.single().equalizer.bands10).hasSize(10)
        assertThat(normalized.profiles.single().equalizer.bands10.take(2)).containsExactly(-15, 15)
        assertThat(normalized.profiles.single().equalizer.bassBoostStrength).isEqualTo(1000)
    }
}
