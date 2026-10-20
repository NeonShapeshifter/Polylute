package com.lostf1sh.pixelplayeross.data.headphones

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * B30-P3 (N-06): presets of the DAC's own EQ are owned by the headphones and made for a pair; a recognised pair gets its selection
 * back and nothing else; another pair's presets are listed for reuse, with how they fit; software EQ gains are never converted.
 */
class DacEqPresetsTest {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; coerceInputValues = true }

    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val melodyDac = DacEqPairDac("dac-melody", "snowsky-melody", "SNOWSKY Melody")
    private val otherDac = DacEqPairDac("dac-ka15", "fiio-ka15", "FiiO KA15")

    private fun filters(vararg gains: Pair<Int, Double>): List<DacEqFilterDef> {
        val map = gains.toMap()
        val freqs = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        return (0 until 10).map { DacEqFilterDef.of(DacEqBand.of(it, FiioFilterType.PEAK, freqs[it], map[it] ?: 0.0, 0.71)) }
    }

    private fun profiles(): HeadphoneProfileState {
        var s = HeadphoneProfileCatalog.createAndSelect(HeadphoneProfileState(), "TYGR", HeadphoneEqState(), 1L, "hp-tygr")
        s = HeadphoneProfileCatalog.createAndSelect(s, "Studio", HeadphoneEqState(), 2L, "hp-studio")
        return s
    }

    private fun HeadphoneProfileState.profile(id: String) = profiles.single { it.profileId == id }

    private fun save(
        state: HeadphoneProfileState,
        headphone: String = "hp-tygr",
        dac: DacEqPairDac = melodyDac,
        name: String = "Warm",
        gains: List<DacEqFilterDef> = filters(2 to 3.0),
        id: String? = null,
        now: Long = 100L,
        newId: String = "preset-${state.profiles.sumOf { it.dacEqPresets.size }}",
    ) = DacEqPresetCatalog.save(state, headphone, dac, name, -31, gains, null, id, now) { newId }

    // ── Ownership and the pair key ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a preset belongs to the headphones and records the DAC it was made for`() {
        val s = save(profiles())
        val preset = s.profile("hp-tygr").dacEqPresets.single()
        assertThat(preset.dacProfileId).isEqualTo("dac-melody")
        assertThat(preset.dacModelId).isEqualTo("snowsky-melody")
        assertThat(preset.dacName).isEqualTo("SNOWSKY Melody")
        assertThat(preset.preGainTenthsDb).isEqualTo(-31)
        assertThat(preset.filters).hasSize(10)
        assertThat(preset.filters[2].gainTenthsDb).isEqualTo(30)
        // Not the other headphones', and found through the pair.
        assertThat(s.profile("hp-studio").dacEqPresets).isEmpty()
        assertThat(DacEqPresetCatalog.presetsFor(s.profile("hp-tygr"), "dac-melody")).containsExactly(preset)
        assertThat(DacEqPresetCatalog.presetsFor(s.profile("hp-tygr"), "dac-ka15")).isEmpty()
    }

    @Test
    fun `a preset stores the full filter definition and what the DAC accepted apart from what was asked`() {
        val asked = filters(2 to 3.0)
        val accepted = asked.mapIndexed { i, f -> if (i == 2) f.copy(qHundredths = 70) else f }
        val s = DacEqPresetCatalog.save(profiles(), "hp-tygr", melodyDac, "Warm", -31, accepted, asked, null, 5L) { "p1" }
        val preset = s.profile("hp-tygr").dacEqPresets.single()
        assertThat(preset.filters).isEqualTo(accepted)
        assertThat(preset.requested).isEqualTo(asked)
        // When the DAC took it as asked there is nothing to record apart.
        val same = DacEqPresetCatalog.save(profiles(), "hp-tygr", melodyDac, "Warm", -31, asked, asked, null, 5L) { "p1" }
        assertThat(same.profile("hp-tygr").dacEqPresets.single().requested).isNull()
        assertThat(preset.filters[0]).isEqualTo(DacEqFilterDef(FiioFilterType.PEAK.code, 32, 0, 71))
    }

    @Test
    fun `editing for one pair never overwrites another pair's preset, whatever the name`() {
        var s = save(profiles(), dac = melodyDac, name = "Warm", gains = filters(2 to 3.0), newId = "p-melody")
        s = save(s, dac = otherDac, name = "Warm", gains = filters(5 to -2.0), newId = "p-ka15")
        val tygr = s.profile("hp-tygr")
        assertThat(tygr.dacEqPresets.map { it.presetId }).containsExactly("p-melody", "p-ka15")

        // Editing the Melody's "Warm" by name changes only the Melody's.
        s = save(s, dac = melodyDac, name = "Warm", gains = filters(2 to 6.0), now = 200L)
        val after = s.profile("hp-tygr")
        assertThat(after.dacEqPresets.single { it.presetId == "p-melody" }.filters[2].gainTenthsDb).isEqualTo(60)
        assertThat(after.dacEqPresets.single { it.presetId == "p-ka15" }.filters[5].gainTenthsDb).isEqualTo(-20)
        assertThat(after.dacEqPresets).hasSize(2)

        // Naming another pair's preset id for this pair does not overwrite it: a new preset is made.
        s = save(s, dac = melodyDac, name = "Cold", gains = filters(7 to -4.0), id = "p-ka15", newId = "p-melody-2")
        val third = s.profile("hp-tygr")
        assertThat(third.dacEqPresets.single { it.presetId == "p-ka15" }.filters[5].gainTenthsDb).isEqualTo(-20)
        assertThat(third.dacEqPresets.single { it.presetId == "p-melody-2" }.dacProfileId).isEqualTo("dac-melody")
    }

    @Test
    fun `renaming and deleting are scoped to the pair`() {
        var s = save(profiles(), dac = melodyDac, name = "Warm", newId = "p1")
        s = save(s, dac = otherDac, name = "Cold", newId = "p2")
        // The wrong pair cannot touch a preset.
        val untouched = DacEqPresetCatalog.rename(s, "hp-tygr", "dac-ka15", "p1", "Hacked", 300L)
        assertThat(untouched.profile("hp-tygr").dacEqPresets.single { it.presetId == "p1" }.name).isEqualTo("Warm")
        val notDeleted = DacEqPresetCatalog.delete(s, "hp-tygr", "dac-ka15", "p1", 300L)
        assertThat(notDeleted.profile("hp-tygr").dacEqPresets.map { it.presetId }).containsExactly("p1", "p2")
        // The right one can; a name already used in the pair is refused.
        val renamed = DacEqPresetCatalog.rename(s, "hp-tygr", "dac-melody", "p1", "Warmer", 300L)
        assertThat(renamed.profile("hp-tygr").dacEqPresets.single { it.presetId == "p1" }.name).isEqualTo("Warmer")
        val deleted = DacEqPresetCatalog.delete(s, "hp-tygr", "dac-melody", "p1", 300L)
        assertThat(deleted.profile("hp-tygr").dacEqPresets.map { it.presetId }).containsExactly("p2")
        assertThat(DacEqPresetCatalog.selectionFor(deleted.profile("hp-tygr"), "dac-melody")).isNull()
    }

    // ── Silent restore ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a recognised pair gets its selection back, an unknown one gets nothing`() {
        val s = save(profiles(), dac = melodyDac, newId = "p1")
        val tygr = s.profile("hp-tygr")
        assertThat(DacEqPresetCatalog.selectionFor(tygr, "dac-melody")?.presetId).isEqualTo("p1")
        // A DAC the headphones were never used with: nothing, not even the Melody's preset because it also has ten bands.
        assertThat(DacEqPresetCatalog.selectionFor(tygr, "dac-ka15")).isNull()
        assertThat(DacEqPresetCatalog.selectionFor(tygr, "dac-new")).isNull()
        // Other headphones on the Melody: nothing either.
        assertThat(DacEqPresetCatalog.selectionFor(s.profile("hp-studio"), "dac-melody")).isNull()
    }

    @Test
    fun `a pair that had the EQ off restores to off, and a selected preset that is gone restores to nothing`() {
        var s = save(profiles(), dac = melodyDac, newId = "p1")
        s = DacEqPresetCatalog.select(s, "hp-tygr", "dac-melody", presetId = null, eqOn = false, nowEpochMs = 2L)
        assertThat(DacEqPresetCatalog.selectionFor(s.profile("hp-tygr"), "dac-melody")).isNull()
        assertThat(s.profile("hp-tygr").dacEqSelections.getValue("dac-melody")).isEqualTo(DacEqSelection(null, false))
        // Selecting a preset of another pair is not accepted.
        s = save(s, dac = otherDac, name = "Cold", newId = "p2")
        val refused = DacEqPresetCatalog.select(s, "hp-tygr", "dac-melody", "p2", true, 3L)
        assertThat(refused.profile("hp-tygr").dacEqSelections.getValue("dac-melody")).isEqualTo(DacEqSelection(null, false))
    }

    @Test
    fun `restoring a selection is a read of the state and nothing else, never a write to the DAC`() {
        // The catalog is pure state; it has no way to reach the DAC. (A write needs the gate, which needs a user action.)
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/headphones/DacEqPresets.kt").readText()
        assertThat(source).doesNotContain("DacEqWriter")
        assertThat(source).doesNotContain("DacEqService")
        assertThat(source).doesNotContain("FiioHidWrites")
    }

    // ── Reuse and compatibility ──────────────────────────────────────────────────────────────────────────

    private fun capability(id: String) = DacEqCapability.of(FiioHidProfiles.all.single { it.id == id })

    private fun preset(filters: List<DacEqFilterDef>) = DacEqPreset("p", "P", "dac-melody", "snowsky-melody", "SNOWSKY Melody", -30, filters, null, 1L, 1L)

    @Test
    fun `the same filters on a DAC with the same limits fit, whatever its name`() {
        val p = preset(filters(2 to 3.0, 5 to -4.0))
        assertThat(DacEqCompat.fit(p, capability("snowsky-melody"))).isEqualTo(DacEqFit.Fits)
        assertThat(DacEqCompat.fit(p, capability("fiio-ka15"))).isEqualTo(DacEqFit.Fits)
        assertThat(DacEqCompat.fit(p, capability("fiio-k13-r2r"))).isEqualTo(DacEqFit.Fits)
    }

    @Test
    fun `a gain, Q or frequency outside the target's range is approximated, and said`() {
        val p = preset(filters(2 to -18.0, 3 to 3.0))
        val fit = DacEqCompat.fit(p, capability("snowsky-melody"))
        assertThat(fit).isInstanceOf(DacEqFit.Approximate::class.java)
        assertThat((fit as DacEqFit.Approximate).changes.single()).contains("filter 3")
        assertThat(DacEqCompat.fit(p, capability("fiio-k13-r2r"))).isEqualTo(DacEqFit.Fits) // the K13 goes down to -24 dB
        val bands = DacEqCompat.bandsFor(p, capability("snowsky-melody"))!!
        assertThat(bands[2].gainTenthsDb).isEqualTo(-120)
        assertThat(bands[3].gainTenthsDb).isEqualTo(30)
        assertThat(bands).hasSize(10)
    }

    @Test
    fun `band count alone is never the key - ten filters on ten bands are incompatible when one is a filter the app does not write`() {
        val peaks = preset(filters(2 to 3.0))
        val withPass = preset(filters(2 to 3.0).mapIndexed { i, f -> if (i == 0) f.copy(typeCode = FiioFilterType.LOW_PASS.code, gainTenthsDb = 0) else f })
        // Both have ten filters and every target below has ten bands; only the definitions decide.
        for (id in listOf("snowsky-melody", "fiio-btr17", "fiio-k13-r2r")) {
            assertThat(DacEqCompat.fit(peaks, capability(id))).isEqualTo(DacEqFit.Fits)
            val verdict = DacEqCompat.fit(withPass, capability(id))
            assertThat(verdict).isInstanceOf(DacEqFit.Incompatible::class.java)
            assertThat((verdict as DacEqFit.Incompatible).kinds).containsExactly(DacEqIncompatibility.FILTER_TYPE)
            assertThat(verdict.reasons.single()).contains("LOW_PASS")
            assertThat(DacEqCompat.bandsFor(withPass, capability(id))).isNull()
        }
        // A type the table says the DAC lacks is incompatible for the same reason, even a type the app would otherwise write.
        val noShelves = capability("fiio-ja11").copy(filterTypes = setOf(FiioFilterType.PEAK))
        val shelf = preset(filters().mapIndexed { i, f -> if (i == 1) f.copy(typeCode = FiioFilterType.LOW_SHELF.code, gainTenthsDb = 20) else f })
        assertThat(DacEqCompat.fit(shelf, noShelves)).isInstanceOf(DacEqFit.Incompatible::class.java)
    }

    @Test
    fun `more filters that do something than the DAC has bands is incompatible, and filters that do nothing are not counted`() {
        val seven = (0 until 10).map { i ->
            DacEqFilterDef(FiioFilterType.PEAK.code, 100 * (i + 1), if (i < 7) 20 else 0, 71)
        }
        val fiveBands = capability("fiio-ja11") // 5 bands, core filters
        val incompatible = DacEqCompat.fit(preset(seven), fiveBands)
        assertThat(incompatible).isInstanceOf(DacEqFit.Incompatible::class.java)
        assertThat((incompatible as DacEqFit.Incompatible).reasons.single()).contains("7 filters")
        // Three that do something on ten listed: fits a five-band DAC, the rest being flat.
        val three = (0 until 10).map { i -> DacEqFilterDef(FiioFilterType.PEAK.code, 100 * (i + 1), if (i < 3) 20 else 0, 71) }
        assertThat(DacEqCompat.fit(preset(three), fiveBands)).isEqualTo(DacEqFit.Fits)
        assertThat(DacEqCompat.bandsFor(preset(three), fiveBands)).hasSize(5)
    }

    @Test
    fun `a DAC without a writable EQ has no usable EQ for any preset`() {
        val p = preset(filters(2 to 3.0))
        assertThat(DacEqCompat.fit(p, null)).isInstanceOf(DacEqFit.Incompatible::class.java)
        assertThat(DacEqCompat.fit(p, capability("snowsky-melody"), targetCanBeWritten = false)).isInstanceOf(DacEqFit.Incompatible::class.java)
    }

    @Test
    fun `presets of other pairs are offered for reuse with their verdict, the pair's own are not`() {
        var s = save(profiles(), dac = melodyDac, name = "Warm", newId = "p1")
        s = save(s, dac = otherDac, name = "Cold", gains = filters(4 to -20.0), newId = "p2")
        val tygr = s.profile("hp-tygr")
        val reuse = DacEqPresetCatalog.reusableOn(tygr, "dac-melody", capability("snowsky-melody"))
        assertThat(reuse.map { it.preset.presetId }).containsExactly("p2")
        assertThat(reuse.single().fit).isInstanceOf(DacEqFit.Approximate::class.java) // -20 dB on a DAC that goes to -12
        // Nothing is selected for the pair by being listed.
        assertThat(DacEqPresetCatalog.selectionFor(tygr, "dac-melody")?.presetId).isEqualTo("p1")
    }

    // ── Software EQ gains are never hardware filters ─────────────────────────────────────────────────────

    @Test
    fun `nothing converts the software graphic EQ into DAC filters`() {
        val dir = File("src/main/java")
        val kotlin = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val presets = File("src/main/java/com/lostf1sh/pixelplayeross/data/headphones/DacEqPresets.kt").readText()
        for (software in listOf("HeadphoneEqState", "EqualizerPreset", "bands10", "bands15", "GraphicEqBands")) {
            assertThat(presets).doesNotContain(software)
        }
        // And no file that knows both the software EQ's bands and a DAC band builds one from the other.
        val bridges = kotlin.filter { f ->
            val text = f.readText()
            Regex("""(bands10|bands15|bandLevels)""").containsMatchIn(text) && Regex("""DacEqBand\.of\(|DacEqFilterDef\(""").containsMatchIn(text)
        }
        assertThat(bridges.map { it.name }).isEmpty()
    }

    // ── Safety Max is independent ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a preset carries no Safety Max and saving one leaves the calibrations as they were`() {
        val calibration = SafetyMaxCalibration("ctx", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 40, 0, 100, 9L)
        var s = profiles()
        s = HeadphoneProfileCatalog.calibrateSafetyMax(s, "hp-tygr", calibration, 10L)
        val before = s.profile("hp-tygr").safetyMax
        s = save(s)
        assertThat(s.profile("hp-tygr").safetyMax).isEqualTo(before)
        val fields = DacEqPreset::class.java.declaredFields.map { it.name }
        assertThat(fields.none { it.contains("safety", ignoreCase = true) || it.contains("ceiling", ignoreCase = true) }).isTrue()
    }

    // ── The schema ───────────────────────────────────────────────────────────────────────────────────────

    private val v3 = """
        {"schemaVersion":3,"profiles":[{"profileId":"hp-1","displayName":"Studio headphones",
          "equalizer":{"enabled":true,"presetName":"rock","customBands":[1,2,3,4,5,6,7,8,9,10],"mode":"GRAPHIC_10",
                       "bands15":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]},
          "createdAtEpochMs":1700000000000,"updatedAtEpochMs":1700000001000,
          "safetyMax":{"enabled":true,"calibrations":{"ctx|USB_HARDWARE_VOLUME":{"outputContextId":"ctx","backendKind":"USB_HARDWARE_VOLUME",
            "ceilingRaw":40,"backendMinRaw":0,"backendMaxRaw":100,"calibratedAtEpochMs":1700000000500}}}}],
         "selectedProfileId":"hp-1","dacAssociations":{"dac-a":"hp-1"}}
    """.trimIndent()

    @Test
    fun `a version 3 payload loads as it was, with no DAC EQ presets`() {
        val state = HeadphoneProfilesCodec.decode(json, v3)
        val profile = state.profiles.single()
        assertThat(profile.dacEqPresets).isEmpty()
        assertThat(profile.dacEqSelections).isEmpty()
        assertThat(profile.equalizer.bands10).isEqualTo(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        assertThat(profile.equalizer.presetName).isEqualTo("rock")
        assertThat(profile.safetyMax.calibrations.getValue("ctx|USB_HARDWARE_VOLUME").ceilingRaw).isEqualTo(40)
        assertThat(state.selectedProfileId).isEqualTo("hp-1")
        assertThat(state.dacAssociations).containsExactly("dac-a", "hp-1")
        assertThat(HeadphoneProfilesCodec.CURRENT_SCHEMA_VERSION).isEqualTo(4)
    }

    @Test
    fun `presets survive a round trip, and the profile around them is unchanged`() {
        val loaded = HeadphoneProfilesCodec.decode(json, v3)
        val saved = DacEqPresetCatalog.save(loaded, "hp-1", melodyDac, "Warm", -31, filters(2 to 3.0), filters(2 to 3.0), null, 5L) { "p1" }
        val reloaded = HeadphoneProfilesCodec.decode(json, HeadphoneProfilesCodec.encode(json, saved))
        assertThat(reloaded).isEqualTo(saved)
        val profile = reloaded.profiles.single()
        assertThat(profile.dacEqPresets.single().presetId).isEqualTo("p1")
        assertThat(profile.dacEqSelections.getValue("dac-melody")).isEqualTo(DacEqSelection("p1", true))
        // Everything the v3 payload held is still there.
        assertThat(profile.equalizer).isEqualTo(loaded.profiles.single().equalizer)
        assertThat(profile.safetyMax).isEqualTo(loaded.profiles.single().safetyMax)
        assertThat(reloaded.dacAssociations).isEqualTo(loaded.dacAssociations)
        assertThat(reloaded.selectedProfileId).isEqualTo(loaded.selectedProfileId)
    }

    @Test
    fun `decoding a version 3 payload and re-encoding it does not drift`() {
        val once = HeadphoneProfilesCodec.decode(json, v3)
        val twice = HeadphoneProfilesCodec.decode(json, HeadphoneProfilesCodec.encode(json, once))
        assertThat(twice).isEqualTo(once)
    }
}
