package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.DacLevelGuard
import com.decent.usbaudio.FeatureUnitPort
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.ResetClaimer
import com.decent.usbaudio.ResetLevelRestorer
import com.decent.usbaudio.ResetLevelRestorer.ClaimedInterfaces
import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B40-P4, corrected by B40-fix1 and B40-fix2: the bus reset that [com.decent.usbaudio.UsbAudioDevice.releaseToAndroid] falls back to restarts the DAC,
 * which can boot back at its default level (often 100 %), and the kernel rebinds `snd-usb-audio` to every interface before the reset's
 * ioctl returns. So the order is: reset, both interfaces claimed again at once, the level put back and read back, and only then the
 * interfaces go back to Android (streaming, control, kernel driver). Unconfirmed, nothing is released at all.
 *
 * The driver's own restore ([DacLevelGuard]) runs against a simulated DAC that comes back at 0 dB, by the order the release takes.
 */
class ResetLevelRestoreTest {
    private val range = UsbVolumeRange(-127 * 256, 0, 256)

    /** A DAC that rebooted: its level is the default until something writes it. */
    private class RebootedDac(
        var units: Int,
        val range: UsbVolumeRange,
        val refuseWrites: Boolean = false,
        val unreadable: Boolean = false,
    ) : FeatureUnitPort {
        override fun readRange() = range
        override fun readUnits(): Int? = if (unreadable) null else units
        override fun writeUnits(units: Int): Int? {
            if (!refuseWrites) this.units = range.clamp(units)
            return if (unreadable) null else this.units
        }
    }

    private val events = mutableListOf<String>()
    private val both = ClaimedInterfaces(streaming = true, control = true)

    /** The release's part from the reset on, as the driver wires it: reset, claim, the guard's restore, then give back, keep or hand over. */
    private fun restorer(
        dac: RebootedDac,
        saved: Int?,
        guardTarget: Int?,
        resetWorks: Boolean = true,
        claimed: ClaimedInterfaces = both,
        restoreThrows: Boolean = false,
    ) = ResetLevelRestorer(
        reset = { events += "reset"; resetWorks },
        holdInterfaces = { events += "hold"; claimed },
        restoreLevel = {
            events += "restore"
            if (restoreThrows) throw IllegalStateException("the transfer blew up")
            val check = DacLevelGuard {}.restoreAfterReconfigure(dac, saved, guardTarget, "bus-reset")
            events += "level=${dac.units}"
            check
        },
        releaseStreaming = { events += "release streaming" },
        releaseControl = { events += "release control" },
        reattachKernelDriver = { events += "reattach" },
        keepHeld = { held -> events += "keep(streaming=${held.streaming},control=${held.control})" },
    )

    @Test
    fun `a DAC that comes back at 100 percent is put back to the confirmed level and read back before anything is given to Android`() {
        val confirmed = -17 * 256
        val dac = RebootedDac(units = 0, range = range)
        val outcome = restorer(dac, saved = confirmed, guardTarget = null).run(hasHardwareVolume = true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.RESTORED)
        assertThat(dac.units).isEqualTo(confirmed)
        assertThat(outcome.levelConfirmed).isTrue()
        assertThat(outcome.deviceKept).isFalse()
        assertThat(outcome.dacWithAndroid).isFalse()
        // the reset, both interfaces claimed at once, the level restored and read, and only then streaming, control, the kernel driver
        assertThat(events).containsExactly(
            "reset", "hold", "restore", "level=$confirmed", "release streaming", "release control", "reattach",
        ).inOrder()
    }

    @Test
    fun `a DAC with no confirmed level before gets the first-use safe level, not 100 percent`() {
        val firstUse = UsbHardwareVolumePolicy.firstUseUnits(range)
        val dac = RebootedDac(units = 0, range = range)
        val guardTarget = UsbHardwareVolumePolicy.openTarget(dac.units, null, null, firstUse)
        assertThat(restorer(dac, saved = null, guardTarget = guardTarget).run(true)).isEqualTo(ResetLevelRestorer.Outcome.RESTORED)
        assertThat(dac.units).isEqualTo(firstUse)
        assertThat(dac.units).isAtMost(-30 * 256)
    }

    @Test
    fun `it never raises - a DAC that came back quieter than the confirmed level is left`() {
        val dac = RebootedDac(units = -60 * 256, range = range)
        assertThat(restorer(dac, saved = -17 * 256, guardTarget = null).run(true)).isEqualTo(ResetLevelRestorer.Outcome.RESTORED)
        assertThat(dac.units).isEqualTo(-60 * 256)
    }

    // ── both interfaces held, the level not confirmed: Polylute keeps both ───────────────────────

    @Test
    fun `a level the DAC does not take is not confirmed and Polylute keeps both interfaces`() {
        val dac = RebootedDac(units = 0, range = range, refuseWrites = true)
        val outcome = restorer(dac, saved = -17 * 256, guardTarget = null).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        assertThat(outcome.levelConfirmed).isFalse()
        assertThat(outcome.deviceKept).isTrue()
        assertThat(outcome.polyluteHoldsStreaming).isTrue()
        assertThat(outcome.polyluteHoldsControl).isTrue()
        assertThat(outcome.dacWithAndroid).isFalse()
        // the B40 order gave the control interface back here, in a finally, and Android got a DAC at 100 %
        assertThat(events).containsExactly("reset", "hold", "restore", "level=0", "keep(streaming=true,control=true)").inOrder()
        assertThat(events).containsNoneIn(listOf("release streaming", "release control", "reattach"))
    }

    @Test
    fun `a level that cannot be read is not confirmed and nothing is given back`() {
        val outcome = restorer(RebootedDac(units = 0, range = range, unreadable = true), saved = -17 * 256, guardTarget = null).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        assertThat(outcome.deviceKept).isTrue()
        assertThat(events).containsNoneIn(listOf("release streaming", "release control", "reattach"))
        assertThat(events.last()).isEqualTo("keep(streaming=true,control=true)")
    }

    @Test
    fun `a restore that throws is an unconfirmed level, not a release`() {
        val outcome = restorer(RebootedDac(units = 0, range = range), saved = null, guardTarget = null, restoreThrows = true).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        assertThat(events).containsExactly("reset", "hold", "restore", "keep(streaming=true,control=true)").inOrder()
    }

    // ── an interface that could not be claimed: the kernel holds it, the DAC is with Android ────

    @Test
    fun `a control interface that cannot be claimed is not kept - the level is unknown and the DAC is with Android`() {
        val outcome = restorer(
            RebootedDac(units = 0, range = range), saved = -17 * 256, guardTarget = null,
            claimed = ClaimedInterfaces(streaming = true, control = false),
        ).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.CONTROL_NOT_HELD)
        assertThat(outcome.levelConfirmed).isFalse()
        assertThat(outcome.deviceKept).isFalse()
        assertThat(outcome.dacWithAndroid).isTrue()
        assertThat(outcome.polyluteHoldsStreaming).isFalse()
        assertThat(outcome.polyluteHoldsControl).isFalse()
        // the level is not looked at (it cannot be reached); what was held (streaming) goes back, and nothing is "kept"
        assertThat(events).containsExactly("reset", "hold", "release streaming", "reattach").inOrder()
    }

    @Test
    fun `a streaming interface that cannot be claimed leaves the level not confirmed, whatever the restore finds, and the DAC with Android`() {
        val dac = RebootedDac(units = 0, range = range)
        val outcome = restorer(
            dac, saved = -17 * 256, guardTarget = null, claimed = ClaimedInterfaces(streaming = false, control = true),
        ).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.STREAMING_NOT_HELD)
        assertThat(outcome.levelConfirmed).isFalse()
        assertThat(outcome.deviceKept).isFalse()
        assertThat(outcome.dacWithAndroid).isTrue()
        // best effort: the level is still brought down through the control interface it holds; then control goes back too, never "kept"
        assertThat(dac.units).isEqualTo(-17 * 256)
        assertThat(events).containsExactly("reset", "hold", "restore", "level=${-17 * 256}", "release control", "reattach").inOrder()
        assertThat(events).doesNotContain("release streaming")
        assertThat(events.none { it.startsWith("keep") }).isTrue()
    }

    @Test
    fun `neither interface claimed - nothing to release, nothing looked at, the DAC is with Android`() {
        val outcome = restorer(
            RebootedDac(units = 0, range = range), saved = null, guardTarget = null, claimed = ClaimedInterfaces(streaming = false, control = false),
        ).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.NOT_HELD)
        assertThat(outcome.levelConfirmed).isFalse()
        assertThat(outcome.deviceKept).isFalse()
        assertThat(outcome.dacWithAndroid).isTrue()
        assertThat(events).containsExactly("reset", "hold").inOrder()
    }

    @Test
    fun `a fixed-output DAC has no level to restore, nothing is claimed after the reset and the host may share it`() {
        val outcome = restorer(RebootedDac(units = 0, range = range), saved = null, guardTarget = null).run(hasHardwareVolume = false)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.NO_HARDWARE_VOLUME)
        assertThat(outcome.levelConfirmed).isTrue()
        assertThat(outcome.deviceKept).isFalse()
        assertThat(events).containsExactly("reset")
    }

    @Test
    fun `a reset that failed changed nothing, claims nothing and tells the host nothing`() {
        val outcome = restorer(RebootedDac(units = 0, range = range), saved = null, guardTarget = null, resetWorks = false).run(true)
        assertThat(outcome).isEqualTo(ResetLevelRestorer.Outcome.RESET_FAILED)
        assertThat(outcome.reportsToHost).isFalse()
        assertThat(outcome.deviceKept).isFalse()
        assertThat(events).containsExactly("reset")
    }

    @Test
    fun `every outcome that is not confirmed is either kept by Polylute or with Android, never neither`() {
        for (outcome in ResetLevelRestorer.Outcome.values()) {
            if (outcome == ResetLevelRestorer.Outcome.RESET_FAILED || outcome.levelConfirmed) continue
            assertThat(outcome.deviceKept || outcome.dacWithAndroid).isTrue()
            assertThat(outcome.deviceKept && outcome.dacWithAndroid).isFalse()
        }
    }

    @Test
    fun `the restore reports a verified level through the same check every open uses`() {
        assertThat(OpenLevelCheck.values().toList())
            .containsExactly(OpenLevelCheck.VERIFIED, OpenLevelCheck.NO_HARDWARE_VOLUME, OpenLevelCheck.UNVERIFIED)
    }

    // ── the claims: streaming first, each failed one tried once more, and only once ───────────────

    private fun claimer(streaming: List<Boolean>, control: List<Boolean>): ResetClaimer {
        val s = streaming.iterator()
        val c = control.iterator()
        return ResetClaimer(
            claimStreaming = { events += "claim streaming"; s.hasNext() && s.next() },
            claimControl = { events += "claim control"; c.hasNext() && c.next() },
        )
    }

    @Test
    fun `the claim order is streaming then control`() {
        val claimed = claimer(listOf(true), listOf(true)).claim()
        assertThat(claimed).isEqualTo(both)
        assertThat(events).containsExactly("claim streaming", "claim control").inOrder()
    }

    @Test
    fun `a claim that failed is tried once more, in the same order, and only once`() {
        val claimed = claimer(listOf(false, false), listOf(false, false)).claim()
        assertThat(claimed).isEqualTo(ClaimedInterfaces(streaming = false, control = false))
        assertThat(events).containsExactly("claim streaming", "claim control", "claim streaming", "claim control").inOrder()
    }

    @Test
    fun `a retry that takes makes the interface held`() {
        val claimed = claimer(listOf(false, true), listOf(true)).claim()
        assertThat(claimed).isEqualTo(both)
        assertThat(events).containsExactly("claim streaming", "claim control", "claim streaming").inOrder()
        events.clear()
        assertThat(claimer(listOf(true), listOf(false, true)).claim()).isEqualTo(both)
        assertThat(events).containsExactly("claim streaming", "claim control", "claim control").inOrder()
    }

    @Test
    fun `an interface the device does not have is never claimed and is not reported as held`() {
        val claimed = claimer(listOf(true, true), listOf(true)).claim(hasStreaming = false)
        assertThat(claimed).isEqualTo(ClaimedInterfaces(streaming = false, control = true))
        assertThat(events).containsExactly("claim control")
    }

    // ── the release, at source level (the device class cannot be built on the JVM) ───────────────────

    private val driverDir = "../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio"
    private val driver = File("$driverDir/UsbAudioDevice.kt").readText().replace("\r\n", "\n")
    private val restorerSource = File("$driverDir/ResetLevelRestorer.kt").readText().replace("\r\n", "\n")

    @Test
    fun `boundary - in the restorer nothing is released or reattached before the claims are known and, to keep both, the level is confirmed`() {
        val run = restorerSource.substringAfter("    fun run(hasHardwareVolume: Boolean): Outcome {").substringBefore("\n}\n")
        val reset = run.indexOf("reset()")
        val hold = run.indexOf("holdInterfaces()")
        val restore = run.indexOf("restoreLevel()")
        val firstRelease = listOf("releaseStreaming()", "releaseControl()", "reattachKernelDriver()").map { run.indexOf(it) }.filter { it >= 0 }.min()
        assertThat(reset).isAtLeast(0)
        assertThat(hold).isGreaterThan(reset)
        // control is the way to the level: a streaming-only hand-back is decided before the restore; the others come after it
        assertThat(restore).isGreaterThan(hold)
        assertThat(firstRelease).isGreaterThan(hold)
        // the verified hand-back releases streaming, then control, then the kernel driver (AUD-003), after the unverified branch returned
        val unverified = run.indexOf("OpenLevelCheck.UNVERIFIED) {")
        val verifiedStreaming = run.indexOf("releaseStreaming()", unverified)
        val verifiedControl = run.indexOf("releaseControl()", verifiedStreaming)
        val verifiedReattach = run.indexOf("reattachKernelDriver()", verifiedControl)
        assertThat(unverified).isGreaterThan(restore)
        assertThat(verifiedStreaming).isGreaterThan(unverified)
        assertThat(verifiedControl).isGreaterThan(verifiedStreaming)
        assertThat(verifiedReattach).isGreaterThan(verifiedControl)
        // "keep" exists in exactly one place: both held, level unverified
        assertThat(run.split("keepHeld(").size - 1).isEqualTo(1)
        assertThat(run).doesNotContain("finally")
    }

    @Test
    fun `boundary - the claims are streaming first, with nothing between them, and the log comes after`() {
        val claimer = restorerSource.substringAfter("class ResetClaimer(")
        val claim = claimer.substringAfter("fun claim(").substringBefore("\n    }\n")
        assertThat(claim.indexOf("claimStreaming()")).isAtLeast(0)
        assertThat(claim.indexOf("claimStreaming()")).isLessThan(claim.indexOf("claimControl()"))
        assertThat(claim).doesNotContain("Log.")
        // in the device the claims are the lambdas the claimer is given, and the line about them is written after `.claim(`
        val hold = driver.substringAfter("holdInterfaces = {").substringBefore("restoreLevel = ")
        assertThat(hold.indexOf(".claim(")).isAtLeast(0)
        assertThat(hold.indexOf("Log.")).isGreaterThan(hold.indexOf(".claim("))
        assertThat(hold.indexOf("claimStreaming =")).isLessThan(hold.indexOf("claimControl ="))
    }

    @Test
    fun `boundary - what the device records as held is what was claimed, and it releases only that`() {
        val release = driver.substringAfter("    fun releaseToAndroid() {").substringBefore("    /**\n     * B40-fix1: a DAC held since a bus reset")
        // the fields of the kept state come from the claims, never from the interfaces that were meant to be claimed
        val keep = release.substringAfter("keepHeld = { claimed ->").substringBefore("},\n            ).run(")
        assertThat(keep).contains("claimedInterface = if (claimed.streaming) streaming else null")
        assertThat(keep).contains("claimedControlInterface = if (claimed.control) controlOnRelease else null")
        // the release lambdas let go of an interface only if it was held
        assertThat(release).contains("if (heldStreaming) streaming?.let { conn.releaseInterface(it) }")
        assertThat(release).contains("if (heldControl) control?.let { conn.releaseInterface(it) }")
        // closeDevice lets go of the fields' interfaces, which are only ever held ones
        val close = driver.substringAfter("    fun closeDevice() {").substringBefore("Log.i(TAG, \"USB device closed\")")
        assertThat(close).contains("claimedInterface?.let")
        assertThat(close).contains("claimedControlInterface?.let")
    }

    @Test
    fun `boundary - the device releases nothing after the reset except through the restorer, and does not close a DAC it kept`() {
        val release = driver.substringAfter("    fun releaseToAndroid() {").substringBefore("    /**\n     * B40-fix1: a DAC held since a bus reset")
        val afterReset = release.substringAfter("resetRet = busReset(fd)")
        val restorerCall = afterReset.substringBefore(").run(hasHardwareVolume")
        // inside the restorer's construction the three releases exist once each; after it there is none
        assertThat(restorerCall.split("conn.releaseInterface(").size - 1).isEqualTo(2)
        assertThat(restorerCall.split("nativeReattachKernelDriverIfUnbound").size - 1).isEqualTo(1)
        val beyond = afterReset.substringAfter(").run(hasHardwareVolume")
        assertThat(beyond).doesNotContain("releaseInterface(")
        assertThat(beyond).doesNotContain("nativeReattachKernelDriverIfUnbound")
        // a kept DAC returns before closeDevice()
        val kept = beyond.indexOf("if (outcome.deviceKept) {")
        val ret = beyond.indexOf("return", kept)
        val close = beyond.indexOf("closeDevice()")
        assertThat(kept).isAtLeast(0)
        assertThat(close).isGreaterThan(ret)
        // the reset is the JNI call behind one seam, and only that
        assertThat(driver).contains("{ fd -> runCatching { UsbAudioStream.nativeUsbResetForRebind(fd) }.getOrDefault(-1) }")
    }

    @Test
    fun `boundary - a DAC held for its level is looked at again by the next open and by any later hand-back, and closing clears it`() {
        val open = driver.substringAfter("fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {").substringBefore("// Close any stale connection")
        assertThat(open).contains("if (levelOwedAfterReset) settleOwedLevel(")
        val release = driver.substringAfter("    fun releaseToAndroid() {").substringBefore("val streaming = claimedInterface")
        assertThat(release).contains("if (levelOwedAfterReset && !settleOwedLevel(conn)) {")
        assertThat(release).contains("claimedInterface != null && claimedControlInterface != null")
        assertThat(driver.substringAfter("    fun closeDevice() {").substringBefore("volumeTopology = null")).contains("levelOwedAfterReset = false")
        // the retry of a claim that failed is streaming first, and sets a field only by a claim that took
        val settle = driver.substringAfter("private fun settleOwedLevel(").substringBefore("restoreDirectLevelOnOpen()")
        assertThat(settle.indexOf("claimedInterface = iface")).isAtLeast(0)
        assertThat(settle.indexOf("claimedInterface = iface")).isLessThan(settle.indexOf("claimedControlInterface = iface"))
        assertThat(settle).contains("if (runCatching { conn.claimInterface(iface, true) }.getOrDefault(false)) claimedInterface = iface")
        assertThat(settle).contains("if (runCatching { conn.claimInterface(iface, true) }.getOrDefault(false)) claimedControlInterface = iface")
    }

    @Test
    fun `wiring - an unconfirmed level is not announced as shared, the host takes the DAC back, and says what is true`() {
        val engine = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt").readText().replace("\r\n", "\n")
        assertThat(engine).contains("UsbAudioDevice.getInstance(context).resetLevelListener = { outcome ->")
        assertThat(engine).contains("if (!outcome.levelConfirmed) dacShare.onReleaseNotConfirmed()")
        // the outcome is kept before the host is asked, and the host's words follow it
        assertThat(engine.indexOf("lastResetOutcome = outcome")).isLessThan(engine.indexOf("dacShare.onReleaseNotConfirmed()"))
        assertThat(engine).contains("val withAndroid = outcome?.dacWithAndroid == true")
        assertThat(engine).contains("if (withAndroid) R.string.dac_share_release_with_android else R.string.dac_share_release_unconfirmed")
        val controller = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DacShareController.kt").readText().replace("\r\n", "\n")
        assertThat(controller.substringAfter("fun onReleaseNotConfirmed() {").substringBefore("\n    }\n")).contains("host.setReleased(false)")
    }

    @Test
    fun `wiring - the S22 path where the kernel rebinds by itself never reaches the reset or the restore`() {
        val release = driver.substringAfter("    fun releaseToAndroid() {").substringBefore("    /**\n     * B40-fix1: a DAC held since a bus reset")
        assertThat(release.substringBefore("resetRet = busReset(fd)")).doesNotContain("restoreLevelAfterReconfigure")
        assertThat(release).contains("if (unbound.isNotEmpty() && !skipReset) {")
    }
}
