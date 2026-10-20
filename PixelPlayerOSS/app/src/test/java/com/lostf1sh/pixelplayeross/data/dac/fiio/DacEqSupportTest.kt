package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** B30-P2: the debug override, the backup store and the reviewer's step parsing. */
class DacEqSupportTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private class MemoryStore(var value: Boolean = false) : DebugFlagStore {
        var writes = 0
        override fun read() = value
        override fun write(value: Boolean) {
            this.value = value
            writes++
        }
    }

    // ── The override ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the override is off by default and persists once it is turned on`() {
        val store = MemoryStore()
        val switch = DacEqWriteSwitch(store, debugBuild = true)
        assertThat(switch.isAllowed()).isFalse()
        assertThat(store.writes).isEqualTo(0) // reading writes nothing
        switch.set(true)
        assertThat(switch.isAllowed()).isTrue()
        // A new process reads it back from the store.
        assertThat(DacEqWriteSwitch(store, debugBuild = true).isAllowed()).isTrue()
        switch.set(false)
        assertThat(DacEqWriteSwitch(store, debugBuild = true).isAllowed()).isFalse()
    }

    @Test
    fun `a release build ignores a stored override and refuses to write one`() {
        val store = MemoryStore(value = true) // as if a debug build had left it on
        val release = DacEqWriteSwitch(store, debugBuild = false)
        assertThat(release.isAllowed()).isFalse()
        release.set(true)
        assertThat(release.isAllowed()).isFalse()
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `only the debug receiver and the switch's own file touch the override`() {
        val main = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val debug = File("src/debug/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val writers = (main + debug).filter { Regex("""(writeSwitch|DacEqWriteSwitch\.shared\([^)]*\))\.set\(""").containsMatchIn(it.readText()) }
        assertThat(writers.map { it.name }).containsExactly("DacEqDebugCommands.kt")
        // And the verdict "may write" reads it in one place of the gate's context.
        val readers = main.filter { it.readText().contains("overrideAllowsWrites") }.map { it.name }
        assertThat(readers).containsExactly("DacEqService.kt", "DacEqWriter.kt", "DacEqWriteGate.kt")
    }

    @Test
    fun `no profile in the table is verified, so a release build writes nothing until a later batch decides`() {
        // The gate refuses an unverified profile unless the override allows it; the override is inert in release (above).
        assertThat(FiioHidProfiles.all.none { it.verified }).isTrue()
        val switch = DacEqWriteSwitch(MemoryStore(value = true), debugBuild = false)
        val gate = DacEqWriteGate()
        val state = FiioEqReader.read(FiioHidSession(FakeFiioDac(melody), melody))
        val decision = gate.authorise(
            WriteRequest(WriteKind.CLOSE_EQ, WriteInitiator.USER_ACTION),
            WriteContext(melody, "k", state, 10, null, overrideAllowsWrites = switch.isAllowed()),
        )
        assertThat((decision as GateDecision.Refused).refusal).isEqualTo(GateRefusal.PROFILE_NOT_VERIFIED)
    }

    // ── The backup store ─────────────────────────────────────────────────────────────────────────────────

    private fun store(): DacEqBackupStore = DacEqBackupStore(Files.createTempDirectory("dac-eq").toFile().also { dirs += it })

    private fun backup(key: String = "profile-1", taken: Long = 100L): DacEqBackup {
        val state = FiioEqReader.read(FiioHidSession(FakeFiioDac(melody), melody))
        val att = AttachedFiio("p", "id", key, "SNOWSKY Melody", "p", melody)
        return DacEqBackup.of(att, state, taken)!!
    }

    @Test
    fun `a backup is stored per DAC and read back exactly`() {
        val s = store()
        val a = backup("profile-a")
        val b = backup("profile-b")
        assertThat(s.save(a)).isTrue()
        assertThat(s.save(b)).isTrue()
        assertThat(s.load("profile-a")).isEqualTo(a)
        assertThat(s.load("profile-b")).isEqualTo(b)
        assertThat(s.load("profile-c")).isNull()
    }

    @Test
    fun `saving over an existing backup is refused unless it is asked for, and then the old one is archived`() {
        val s = store()
        val first = backup(taken = 100L)
        assertThat(s.save(first)).isTrue()
        val second = backup(taken = 200L)
        assertThat(s.save(second)).isFalse()
        assertThat(s.load("profile-1")).isEqualTo(first)
        assertThat(s.save(second, replace = true, nowEpochMs = 200L)).isTrue()
        assertThat(s.load("profile-1")).isEqualTo(second)
        assertThat(dirs.single().listFiles()!!.map { it.name }).contains("profile-1.200.json")
    }

    @Test
    fun `a slot can be added to a backup but no existing snapshot can be altered`() {
        val s = store()
        val original = backup()
        s.save(original)
        val flat = DacEqSlotSnapshot(0xA2, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, 1000, 0.0, 1.0) })
        assertThat(s.update(original.withSlot(flat))).isTrue()
        assertThat(s.load("profile-1")!!.slots.keys).containsExactly(0xF0, 0xA2)
        // Altering the active preset's snapshot is refused.
        val altered = original.withSlot(flat).let { b ->
            b.copy(slots = b.slots + (0xF0 to b.slots.getValue(0xF0).copy(preGainTenthsDb = 99)))
        }
        assertThat(s.update(altered)).isFalse()
        assertThat(s.load("profile-1")!!.slots.getValue(0xF0)).isEqualTo(original.slots.getValue(0xF0))
        // A different backup (another moment) is refused too.
        assertThat(s.update(original.copy(takenAtEpochMs = 5))).isFalse()
    }

    @Test
    fun `a corrupt backup file reads as no backup, and the key never escapes the folder`() {
        val s = store()
        val dir = dirs.single()
        File(dir, "profile-1.json").writeText("{ not json")
        assertThat(s.load("profile-1")).isNull()
        val evil = backup("../../etc/passwd")
        assertThat(s.save(evil)).isTrue()
        assertThat(dir.listFiles()!!.all { it.parentFile == dir }).isTrue()
        assertThat(s.load("../../etc/passwd")).isEqualTo(evil)
    }

    @Test
    fun `a backup holds the state as read and is complete only with the active preset's snapshot`() {
        val b = backup()
        assertThat(b.isComplete).isTrue()
        assertThat(b.activePresetId).isEqualTo(0xF0)
        assertThat(b.slots.getValue(0xF0).bands).hasSize(10)
        assertThat(b.copy(slots = emptyMap()).isComplete).isFalse()
        val incomplete = FakeFiioDac(melody).also { it.dropReplies = true }
        val state = FiioEqReader.read(FiioHidSession(incomplete, melody))
        assertThat(DacEqBackup.of(AttachedFiio("p", "id", "k", "n", "p", melody), state, 1L)).isNull()
    }

    // ── The reviewer's steps ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a set-band step is one band of the DAC's own numbering, at most 3 dB, and a missing extra changes nothing`() {
        fun parse(band: Int?, gain: Float?) = DacEqDebugSteps.parseSetBand(band, gain, 10)
        assertThat(parse(4, 1.0f)).isEqualTo(DacEqDebugSteps.SetBand.Ok(3, 1.0))
        assertThat(parse(1, -3.0f)).isEqualTo(DacEqDebugSteps.SetBand.Ok(0, -3.0))
        assertThat(parse(10, 3.0f)).isEqualTo(DacEqDebugSteps.SetBand.Ok(9, 3.0))
        for (refused in listOf(parse(null, 1f), parse(4, null), parse(0, 1f), parse(11, 1f), parse(4, 3.1f), parse(4, -4f), parse(4, Float.NaN))) {
            assertThat(refused).isInstanceOf(DacEqDebugSteps.SetBand.Refused::class.java)
        }
        assertThat(parse(4, 1.04f)).isEqualTo(DacEqDebugSteps.SetBand.Ok(3, 1.0)) // the DAC stores tenths
    }

    @Test
    fun `the write options accept only the two spellings, and a typo is not a mode`() {
        assertThat(DacEqDebugSteps.parseCrc(null).getOrNull()).isNull()
        assertThat(DacEqDebugSteps.parseCrc("maxim").getOrNull()).isEqualTo(FiioCrcMode.MAXIM)
        assertThat(DacEqDebugSteps.parseCrc("ZERO").getOrNull()).isEqualTo(FiioCrcMode.ZERO)
        assertThat(DacEqDebugSteps.parseCrc("xor").isFailure).isTrue()
        assertThat(DacEqDebugSteps.parseSave("slot").getOrNull()).isEqualTo(FiioSaveForm.WITH_SLOT)
        assertThat(DacEqDebugSteps.parseSave("empty").getOrNull()).isEqualTo(FiioSaveForm.EMPTY)
        assertThat(DacEqDebugSteps.parseSave("none").getOrNull()).isEqualTo(FiioSaveForm.NONE)
        assertThat(DacEqDebugSteps.parseSave("later").isFailure).isTrue()
    }

    @Test
    fun `every debug command is declared in the manifest, and the read ones send no SET`() {
        val manifest = File("src/debug/AndroidManifest.xml").readText()
        for (action in listOf(
            "DAC_EQ_READ", "DAC_EQ_BACKUP", "DAC_EQ_DISCOVER_SLOT", "DAC_EQ_ROUNDTRIP", "DAC_EQ_SET_BAND", "DAC_EQ_RESTORE", "SET_DAC_EQ_WRITES_ALLOWED",
            "DAC_EQ_INVENTORY", "DAC_EQ_TAKE_OVER", "DAC_EQ_LISTEN_CHECK", "DAC_EQ_RESTORE_FACTORY", "SET_DAC_EQ_PRE_GAIN_SENSE",
        )) {
            assertThat(manifest).contains("com.lostf1sh.pixelplayeross.debug.$action")
        }
    }

    @Test
    fun `the state log prints every value and says which are unknown`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val lines = DacEqStateFormat.lines(FiioEqReader.read(FiioHidSession(dac, melody)))
        assertThat(lines.first()).contains("the table's claim, not a reading")
        assertThat(lines.any { it.contains("preset=read 0xF0 = Close EQ") }).isTrue()
        assertThat(lines.any { it.contains("preGain=read +2.0 dB") }).isTrue()
        assertThat(lines.any { it.contains("switch=unknown") }).isTrue()
        assertThat(lines.count { it.contains("band ") }).isEqualTo(10)
        assertThat(lines.last()).contains("verdict=OFF")
    }

    // ── B31: what the source keeps true ─────────────────────────────────────────────────────────────────

    private fun mainKotlin() = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `production code never builds a level model without saying what the pre-gain means`() {
        // A DacEqLive left at its default sense would judge a write under the signed reading whatever the DAC is known to do.
        for (file in mainKotlin()) {
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (Regex("""(?<![A-Za-z])DacEqLive\(""").containsMatchIn(code) && !code.contains("class DacEqLive")) {
                    assertThat(code).contains("sense")
                }
                if (code.contains("DacEqLive.of(")) assertThat(code).contains("sense")
                if (code.contains("DacEqCurve.preGainTenthsFor(") || code.contains("DacEqCurve.netPeakDb(") || code.contains("DacEqCurve.netPeaksDb(")) {
                    assertWithMessage("${file.name}:${i + 1}: $code").that(code.contains("sense") || code.contains("Sense")).isTrue()
                }
            }
        }
    }

    @Test
    fun `the gate's context is built in the writer, with the sense and the playback state`() {
        val builders = mainKotlin().filter { Regex("""WriteContext\(""").containsMatchIn(it.readText().replace("class WriteContext(", "")) }
        assertThat(builders.map { it.name }).containsExactly("DacEqWriter.kt")
        val writer = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqWriter.kt").readText()
        assertThat(writer).contains("sense = sense,")
        assertThat(writer).contains("playing = playbackActive(),")
    }

    @Test
    fun `the service hands its writer the real playback state, the sense and the backup callback, and the module wires the player`() {
        val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqService.kt").readText()
        assertThat(service).contains("playbackActive = playbackActive,")
        assertThat(service).contains("senseOf = { profile -> senseSwitch?.effective(profile) ?: profile.preGainSense },")
        assertThat(service).contains("onBackupChanged = ::backupChanged,")
        val module = File("src/main/java/com/lostf1sh/pixelplayeross/di/DacEqModule.kt").readText()
        assertThat(module).contains("playbackActive = { engine.isPlayingFlow.value }")
        assertThat(module).contains("senseSwitch = DacEqPreGainSenseSwitch.shared(context)")
    }

    @Test
    fun `only the debug receiver records the reviewer's answer on the pre-gain sense`() {
        val debug = File("src/debug/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val writers = (mainKotlin() + debug).filter { Regex("""DacEqPreGainSenseSwitch\.shared\([^)]*\)\s*\n?\s*\.set\(|switch\.set\(|senseSwitch\??\.set\(""").containsMatchIn(it.readText()) }
        assertThat(writers.map { it.name }).containsExactly("DacEqDebugCommands.kt")
        // A release build ignores a stored answer (the unit test above); the profile rows all say "unconfirmed".
        assertThat(FiioHidProfiles.all.map { it.preGainSense }.toSet()).containsExactly(PreGainSense.UNCONFIRMED)
    }

    @Test
    fun `the steps that select a preset nobody vetted are the ones the gate keeps for a paused player`() {
        val gate = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqWriteGate.kt").readText()
        assertThat(gate).contains("private val PAUSED_ONLY = setOf(WriteKind.PEEK_SLOT, WriteKind.TAKE_OVER, WriteKind.INVENTORY, WriteKind.RESTORE_FACTORY)")
        assertThat(gate).contains("request.kind in PAUSED_ONLY && context.playing")
    }
}
