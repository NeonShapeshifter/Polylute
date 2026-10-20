package com.lostf1sh.pixelplayeross.data.headphones

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import org.junit.jupiter.api.Test

/**
 * The DAC ↔ headphone association is keyed by **DAC profile id**, and must stay that way.
 *
 * The stored key has always been a profile id — `HeadphoneProfileState.dacAssociations` maps
 * `dacProfileId → headphoneProfileId` — so a DAC rename cannot break it: renaming changes
 * `displayName` and nothing else. What was name-based was one *read*: the DAC profile screen
 * matched `associatedDacName == profile.displayName`, which broke on rename and picked arbitrarily
 * between two DACs sharing a visible name.
 *
 * These tests pin both halves: the stored key survives a rename, two same-named DACs stay distinct,
 * and a payload that somehow acquired a display-name key is re-keyed when that is unambiguous and
 * left alone when it is not.
 */
class DacHeadphoneAssociationIdentityTest {

    private fun dac(
        profileId: String,
        displayName: String,
        serial: String? = null,
        vendorId: Int = 0x262A,
        productId: Int = 0x0001,
    ) = DacProfile(
        profileId = profileId,
        deviceIdentityKey = "dac-$profileId",
        identityScope = if (serial != null) DacIdentityScope.UNIT_SPECIFIC else DacIdentityScope.MODEL_SCOPED,
        displayName = displayName,
        vendorId = vendorId,
        productId = productId,
        serialNumber = serial,
        createdAtEpochMs = 1_000L,
        lastSeenEpochMs = 1_000L,
    )

    private fun headphone(profileId: String, displayName: String = "Headphones") = HeadphoneProfile(
        profileId = profileId,
        displayName = displayName,
        equalizer = HeadphoneEqState(),
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    // --- K: renaming a DAC does not break the association ---------------------------------------

    @Test
    fun `renaming a DAC does not break the headphone association`() {
        val dacProfile = dac("profile-a", "Fosi Audio DS2", serial = "5000000001")
        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-1")),
            selectedProfileId = "hp-1",
            dacAssociations = mapOf("profile-a" to "hp-1"),
        )

        val renamed = DacProfileCatalog.rename(listOf(dacProfile), "profile-a", "My desk DAC")

        assertThat(renamed.single().displayName).isEqualTo("My desk DAC")
        // The identity the association is stored under is untouched by the rename, so the lookup
        // still finds the remembered headphones.
        assertThat(renamed.single().profileId).isEqualTo("profile-a")
        assertThat(HeadphoneProfileCatalog.associatedProfile(state, "profile-a")?.profileId)
            .isEqualTo("hp-1")
    }

    // --- L: two DACs with the same visible name stay distinct -----------------------------------

    @Test
    fun `two DAC profiles with the same display name keep separate associations`() {
        // Two units of the same model, both renamed to the same string — indistinguishable by name
        // and entirely distinguishable by id.
        val first = dac("profile-a", "USB DAC", serial = "AA")
        val second = dac("profile-b", "USB DAC", serial = "BB")

        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-a", "IEMs"), headphone("hp-b", "Over-ears")),
            selectedProfileId = "hp-a",
            dacAssociations = mapOf("profile-a" to "hp-a", "profile-b" to "hp-b"),
        )

        assertThat(first.displayName).isEqualTo(second.displayName)
        assertThat(first.profileId).isNotEqualTo(second.profileId)

        assertThat(HeadphoneProfileCatalog.associatedProfile(state, "profile-a")?.profileId)
            .isEqualTo("hp-a")
        assertThat(HeadphoneProfileCatalog.associatedProfile(state, "profile-b")?.profileId)
            .isEqualTo("hp-b")
    }

    @Test
    fun `the id-keyed association is unaffected by a duplicated display name`() {
        // A name-keyed reconciliation must not silently pick one of two equal names, and the state
        // it is handed already carries both associations under their own ids.
        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-a"), headphone("hp-b")),
            dacAssociations = mapOf("profile-a" to "hp-a", "profile-b" to "hp-b"),
        )
        val dacs = listOf(dac("profile-a", "USB DAC"), dac("profile-b", "USB DAC"))

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(state, dacs)

        assertThat(migration.migrated).isEmpty()
        assertThat(migration.needsAttention).isFalse()
        assertThat(migration.state.dacAssociations)
            .containsExactly("profile-a", "hp-a", "profile-b", "hp-b")
    }

    // --- M: an unambiguous legacy association is re-keyed ----------------------------------------

    @Test
    fun `an association stored under a display name is re-keyed when exactly one DAC matches`() {
        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-1")),
            selectedProfileId = "hp-1",
            dacAssociations = mapOf("Fosi Audio DS2" to "hp-1"),
        )
        val dacs = listOf(
            dac("profile-a", "Fosi Audio DS2"),
            dac("profile-b", "Some Other DAC"),
        )

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(state, dacs)

        assertThat(migration.migrated.map { it.key }).containsExactly("Fosi Audio DS2")
        assertThat(migration.migrated.single().resolvedDacProfileId).isEqualTo("profile-a")
        assertThat(migration.state.dacAssociations).containsExactly("profile-a", "hp-1")
        // And the lookup the rest of the code performs now succeeds.
        assertThat(HeadphoneProfileCatalog.associatedProfile(migration.state, "profile-a")?.profileId)
            .isEqualTo("hp-1")
    }

    @Test
    fun `re-keying never overwrites an association already stored under the profile id`() {
        // The id-keyed entry is the one a build that knew what it was doing wrote, so it wins and
        // the name-keyed leftover is dropped rather than replacing it.
        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-real"), headphone("hp-stale")),
            dacAssociations = mapOf(
                "Fosi Audio DS2" to "hp-stale",
                "profile-a" to "hp-real",
            ),
        )
        val dacs = listOf(dac("profile-a", "Fosi Audio DS2"))

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(state, dacs)

        assertThat(migration.state.dacAssociations).containsExactly("profile-a", "hp-real")
        assertThat(migration.state.profiles.map { it.profileId })
            .containsExactly("hp-real", "hp-stale")
    }

    // --- N: an ambiguous legacy association is not silently misbound -----------------------------

    @Test
    fun `an association matching more than one DAC is left unbound and reported`() {
        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-1")),
            selectedProfileId = "hp-1",
            dacAssociations = mapOf("USB DAC" to "hp-1"),
        )
        val dacs = listOf(dac("profile-a", "USB DAC"), dac("profile-b", "USB DAC"))

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(state, dacs)

        assertThat(migration.ambiguous.map { it.key }).containsExactly("USB DAC")
        assertThat(migration.migrated).isEmpty()
        // Nothing was guessed, and nothing was lost: the entry is exactly as it was stored, still
        // pointing at the same headphone profile, so an explicit re-association can still see it.
        assertThat(migration.state.dacAssociations).containsExactly("USB DAC", "hp-1")
        assertThat(migration.state.profiles.map { it.profileId }).containsExactly("hp-1")
        assertThat(migration.needsAttention).isTrue()
    }

    @Test
    fun `an association matching no DAC is left as stored and reported`() {
        // A DAC the user forgot, whose key outlives the record. Deleting it would discard a
        // remembered pairing; binding it to something would invent one.
        val state = HeadphoneProfileState(
            profiles = listOf(headphone("hp-1")),
            dacAssociations = mapOf("profile-gone" to "hp-1", "Unknown DAC" to "hp-1"),
        )

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(
            state,
            listOf(dac("profile-a", "Fosi Audio DS2")),
        )

        assertThat(migration.unresolved.map { it.key })
            .containsExactly("profile-gone", "Unknown DAC")
        assertThat(migration.state.dacAssociations)
            .containsExactly("profile-gone", "hp-1", "Unknown DAC", "hp-1")
    }

    @Test
    fun `an empty association map is a pure no-op`() {
        // Every install that never paired a DAC with headphones — the state the HONOR is in.
        val state = HeadphoneProfileState(profiles = listOf(headphone("hp-1")))

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(
            state,
            listOf(dac("profile-a", "Fosi Audio DS2")),
        )

        assertThat(migration.state).isEqualTo(state)
        assertThat(migration.outcomes).isEmpty()
        assertThat(migration.needsAttention).isFalse()
    }

    @Test
    fun `reconciliation preserves the equalizer and safety max of every profile`() {
        val calibrated = headphone("hp-1").copy(
            equalizer = HeadphoneEqState(bands15 = List(15) { it - 7 }, mode = HeadphoneEqMode.GRAPHIC_15),
            safetyMax = HeadphoneSafetyPolicy(
                calibrations = mapOf(
                    "android:usb_device" to SafetyMaxCalibration(
                        outputContextId = "android:usb_device",
                        backendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
                        ceilingRaw = 40,
                        backendMinRaw = 0,
                        backendMaxRaw = 64,
                        calibratedAtEpochMs = 5L,
                    )
                )
            ),
        )
        val state = HeadphoneProfileState(
            profiles = listOf(calibrated),
            selectedProfileId = "hp-1",
            dacAssociations = mapOf("Fosi Audio DS2" to "hp-1"),
        )

        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(
            state,
            listOf(dac("profile-a", "Fosi Audio DS2")),
        )

        val after = migration.state.profiles.single()
        assertThat(after.equalizer).isEqualTo(calibrated.equalizer)
        assertThat(after.safetyMax).isEqualTo(calibrated.safetyMax)
        assertThat(migration.state.selectedProfileId).isEqualTo("hp-1")
    }
}
