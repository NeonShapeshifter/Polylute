package com.lostf1sh.pixelplayeross.data.headphones

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Phase 8.5 added an EQ mode and a second band store to the headphone profile payload. Those are
 * additions to a record that also holds a user's headphone identity, their safety ceiling and their
 * DAC routing, so a migration that is merely *close* is a data-loss bug wearing a green test suite.
 *
 * Every case here is driven from raw JSON rather than from constructed Kotlin objects, because the
 * rename of the serialised `customBands` field is one of the things that could break and a test that
 * builds objects directly would not exercise it.
 */
class HeadphoneProfileMigrationTest {

    // Matches the application's own Json configuration.
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = HeadphoneProfilesCodec.decode(json, raw)

    private val tenZeroes = List(10) { 0 }.joinToString(",")

    @Test
    fun `legacy effective EQ is adopted intact when no headphone profile exists`() {
        val legacy = HeadphoneEqState(
            enabled = true,
            presetName = "custom",
            bands10 = List(10) { it - 5 },
            mode = HeadphoneEqMode.GRAPHIC_15,
            bands15 = List(15) { 15 - it },
            bassBoostEnabled = true,
            bassBoostStrength = 321,
        )

        val migrated = adoptLegacyEqualizerAuthority(HeadphoneProfileState(), legacy, 10L)

        assertThat(migrated.selectedProfile?.equalizer).isEqualTo(legacy.normalized())
        assertThat(migrated.selectedProfile?.profileId).isEqualTo("headphone-migrated-eq")
    }

    @Test
    fun `an existing selected profile remains authoritative over legacy mirror values`() {
        val profileEq = HeadphoneEqState(enabled = true, bands10 = List(10) { 4 })
        val existing = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "Existing", profileEq, 5L, "headphone-existing", "dac-a"
        )

        val migrated = adoptLegacyEqualizerAuthority(
            existing,
            HeadphoneEqState(enabled = true, bands10 = List(10) { -9 }),
            10L,
        )

        assertThat(migrated).isEqualTo(existing)
        assertThat(migrated.dacAssociations).containsExactly("dac-a", "headphone-existing")
    }

    private fun payload(
        schemaVersion: Int,
        equalizer: String,
        safetyMax: String = """{"enabled":true,"calibrations":{}}""",
        selectedProfileId: String = "hp-1",
        dacAssociations: String = """{"dac-a":"hp-1"}""",
    ) = """
        {
          "schemaVersion": $schemaVersion,
          "profiles": [
            {
              "profileId": "hp-1",
              "displayName": "Studio headphones",
              "equalizer": $equalizer,
              "createdAtEpochMs": 1700000000000,
              "updatedAtEpochMs": 1700000001000,
              "safetyMax": $safetyMax
            }
          ],
          "selectedProfileId": "$selectedProfileId",
          "dacAssociations": $dacAssociations
        }
    """.trimIndent()

    // --- The curve a pre-8.5 profile was actually playing ----------------------------------

    @Test
    fun `a v1 named preset migrates to the curve that preset was playing, not to its stored zeros`() {
        // The pre-8.5 engine read `customBands` only when `presetName` was "custom". For every named
        // preset it resolved the preset's own bands and ignored the stored ones. These zeros were
        // therefore NOT what the user heard, and migrating them verbatim would flatten a chosen EQ.
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """{"enabled":true,"presetName":"rock","customBands":[$tenZeroes]}""",
            )
        )

        val equalizer = state.profiles.single().equalizer
        assertThat(equalizer.presetName).isEqualTo("rock")
        assertThat(equalizer.bands10).isEqualTo(EqualizerPreset.ROCK.bandLevels)
        // Non-vacuous: the rock curve is not a row of zeros.
        assertThat(EqualizerPreset.ROCK.bandLevels.any { it != 0 }).isTrue()
    }

    @Test
    fun `a v1 custom preset keeps the values the user actually dialled in`() {
        val dialled = listOf(1, -2, 3, -4, 5, -6, 7, -8, 9, -10)
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """{"enabled":true,"presetName":"custom","customBands":[${dialled.joinToString(",")}]}""",
            )
        )

        assertThat(state.profiles.single().equalizer.bands10).isEqualTo(dialled)
    }

    @Test
    fun `a v1 preset name this build does not know falls back to flat exactly as the old engine did`() {
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """{"enabled":true,"presetName":"some-removed-preset","customBands":[$tenZeroes]}""",
            )
        )

        assertThat(state.profiles.single().equalizer.bands10).isEqualTo(List(10) { 0 })
    }

    @Test
    fun `a v2 profile migrates the same way a v1 profile does`() {
        val state = decode(
            payload(
                schemaVersion = 2,
                equalizer = """{"enabled":true,"presetName":"jazz","customBands":[$tenZeroes]}""",
            )
        )

        assertThat(state.profiles.single().equalizer.bands10).isEqualTo(EqualizerPreset.JAZZ.bandLevels)
    }

    @Test
    fun `a migrated profile starts on the ten band mode with a flat fifteen band store`() {
        val state = decode(
            payload(
                schemaVersion = 2,
                equalizer = """{"enabled":true,"presetName":"rock","customBands":[$tenZeroes]}""",
            )
        )

        val equalizer = state.profiles.single().equalizer
        // The mode the user was already in, so nothing about what they hear changes on upgrade.
        assertThat(equalizer.mode).isEqualTo(HeadphoneEqMode.GRAPHIC_10)
        // Fifteen bands start flat rather than interpolated from the ten-band curve: an invented
        // curve would be presented to the user as if they had chosen it.
        assertThat(equalizer.bands15).isEqualTo(List(HeadphoneEqState.BAND_COUNT_15) { 0 })
    }

    @Test
    fun `a migration never lets the fifteen band store shadow the ten band one`() {
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """{"enabled":true,"presetName":"rock","customBands":[$tenZeroes]}""",
            )
        )

        val equalizer = state.profiles.single().equalizer
        // The two stores are independent, and the active one is the one the mode names.
        assertThat(equalizer.activeBands).isEqualTo(EqualizerPreset.ROCK.bandLevels)
    }

    // --- Everything else must survive untouched --------------------------------------------

    @Test
    fun `profile identity, naming and timestamps survive migration`() {
        val state = decode(
            payload(schemaVersion = 1, equalizer = """{"enabled":true,"presetName":"pop","customBands":[$tenZeroes]}""")
        )

        val profile = state.profiles.single()
        assertThat(profile.profileId).isEqualTo("hp-1")
        assertThat(profile.displayName).isEqualTo("Studio headphones")
        assertThat(profile.createdAtEpochMs).isEqualTo(1_700_000_000_000)
        assertThat(profile.updatedAtEpochMs).isEqualTo(1_700_000_001_000)
    }

    @Test
    fun `the selected profile and the dac association survive migration`() {
        val state = decode(
            payload(schemaVersion = 1, equalizer = """{"enabled":true,"presetName":"pop","customBands":[$tenZeroes]}""")
        )

        assertThat(state.selectedProfileId).isEqualTo("hp-1")
        assertThat(state.dacAssociations).containsExactly("dac-a", "hp-1")
        assertThat(state.selectedProfile).isNotNull()
    }

    @Test
    fun `a safety max calibration survives migration unchanged`() {
        val calibrations = """
            {"enabled":true,"calibrations":{"usb-dac":{"outputContextId":"usb-dac",
            "backendKind":"FIXED_OUTPUT","ceilingRaw":80,"backendMinRaw":0,"backendMaxRaw":100,
            "calibratedAtEpochMs":1700000002000}}}
        """.trimIndent()

        val state = decode(
            payload(
                schemaVersion = 2,
                equalizer = """{"enabled":true,"presetName":"rock","customBands":[$tenZeroes]}""",
                safetyMax = calibrations,
            )
        )

        val calibration = state.profiles.single().safetyMax
            .calibrationFor("usb-dac", SafetyVolumeBackendKind.FIXED_OUTPUT)!!
        assertThat(calibration.ceilingRaw).isEqualTo(80)
        assertThat(calibration.backendKind).isEqualTo(SafetyVolumeBackendKind.FIXED_OUTPUT)
        assertThat(calibration.backendMinRaw).isEqualTo(0)
        assertThat(calibration.backendMaxRaw).isEqualTo(100)
        assertThat(calibration.calibratedAtEpochMs).isEqualTo(1_700_000_002_000)
        assertThat(state.profiles.single().safetyMax.enabled).isTrue()
    }

    @Test
    fun `migration invents no safety max data for a profile that had none`() {
        // A v1 payload predates the policy entirely. Defaults must be the empty, un-calibrated ones
        // and never a guess at a user's safe listening level.
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """{"enabled":true,"presetName":"flat","customBands":[$tenZeroes]}""",
                safetyMax = """{"enabled":true,"calibrations":{}}""",
            )
        )

        assertThat(state.profiles.single().safetyMax.calibrations).isEmpty()
    }

    @Test
    fun `the other profile effects survive migration`() {
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """
                    {"enabled":true,"presetName":"pop","customBands":[$tenZeroes],
                     "bassBoostEnabled":true,"bassBoostStrength":600,
                     "virtualizerEnabled":true,"virtualizerStrength":400,
                     "loudnessEnhancerEnabled":true,"loudnessEnhancerStrength":250}
                """.trimIndent(),
            )
        )

        val equalizer = state.profiles.single().equalizer
        assertThat(equalizer.bassBoostEnabled).isTrue()
        assertThat(equalizer.bassBoostStrength).isEqualTo(600)
        assertThat(equalizer.virtualizerEnabled).isTrue()
        assertThat(equalizer.virtualizerStrength).isEqualTo(400)
        assertThat(equalizer.loudnessEnhancerEnabled).isTrue()
        assertThat(equalizer.loudnessEnhancerStrength).isEqualTo(250)
    }

    @Test
    fun `a migrated ten band curve is still clamped to the supported range`() {
        val state = decode(
            payload(
                schemaVersion = 1,
                equalizer = """{"enabled":true,"presetName":"custom","customBands":[99,-99,0,0,0,0,0,0,0,0]}""",
            )
        )

        assertThat(state.profiles.single().equalizer.bands10.take(2)).containsExactly(15, -15).inOrder()
    }

    // --- Idempotence and forward compatibility ---------------------------------------------

    @Test
    fun `a v3 payload passes through migration with both stores and the mode intact`() {
        val state = decode(
            payload(
                schemaVersion = 3,
                equalizer = """
                    {"enabled":true,"presetName":"custom","mode":"GRAPHIC_15",
                     "customBands":[1,2,3,4,5,6,7,8,9,10],
                     "bands15":[15,14,13,12,11,10,9,8,7,6,5,4,3,2,1]}
                """.trimIndent(),
            )
        )

        val equalizer = state.profiles.single().equalizer
        assertThat(equalizer.mode).isEqualTo(HeadphoneEqMode.GRAPHIC_15)
        assertThat(equalizer.bands15).isEqualTo(listOf(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1))
        // The inactive store is preserved too, so switching back finds the user's curve.
        assertThat(equalizer.bands10).isEqualTo(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
    }

    @Test
    fun `decoding is idempotent so a round trip does not drift`() {
        val raw = payload(
            schemaVersion = 1,
            equalizer = """{"enabled":true,"presetName":"rock","customBands":[$tenZeroes]}""",
        )
        val once = decode(raw)
        val twice = decode(HeadphoneProfilesCodec.encode(json, once))

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `a payload from a newer schema is preserved rather than downgraded`() {
        val state = decode(
            payload(
                schemaVersion = 99,
                equalizer = """{"enabled":true,"presetName":"custom","mode":"GRAPHIC_15",
                    "customBands":[1,1,1,1,1,1,1,1,1,1],"bands15":[2,2,2,2,2,2,2,2,2,2,2,2,2,2,2],
                    "someFutureField":{"nested":true}}""",
            )
        )

        // The user's profiles must not be destroyed just because this build is older than the file.
        assertThat(state.profiles.single().equalizer.bands15).isEqualTo(List(15) { 2 })
        assertThat(state.profiles.single().equalizer.mode).isEqualTo(HeadphoneEqMode.GRAPHIC_15)
    }

    @Test
    fun `an unreadable payload resets to empty rather than half-loading a corrupt record`() {
        assertThat(decode("{ not json at all")).isEqualTo(HeadphoneProfileState())
        assertThat(HeadphoneProfilesCodec.decode(json, null)).isEqualTo(HeadphoneProfileState())
        assertThat(HeadphoneProfilesCodec.decode(json, "   ")).isEqualTo(HeadphoneProfileState())
    }
}
