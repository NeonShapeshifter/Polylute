package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B48-1: the device's one owner is wired into every party that touches the USB interfaces: the rebuild and the release of the sink, the hand-back of the device, the writer's
 * recovery (through its host) and the unplug. The behaviour is in [com.lostf1sh.pixelplayeross.data.usb.B48RecoveryOwnershipTest]; this pins that the production code goes through it.
 */
class B48OwnerWiringBoundaryTest {
    private val appRoot = File(".").canonicalFile
    private val libs = File(appRoot, "../../decent-player/libs").canonicalFile
    private fun text(file: File) = file.readText().replace("\r\n", "\n")
    private val sink = text(File(libs, "decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"))
    private val thread = text(File(libs, "decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbStreamingThread.kt"))
    private val device = text(File(libs, "decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt"))
    private val owner = text(File(libs, "decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbDeviceOwner.kt"))
    private val engine = text(File(appRoot, "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt"))

    @Test
    fun `the sink's rebuild and release are exclusive sections, the tail plays out before the rebuild takes the device, and the stream is published`() {
        val configure = sink.substringAfter("private fun configureUsbBitPerfect(").substringBefore("private fun rebuildUsbStream(")
        assertThat(configure).contains("usbAudioDevice.owner.exclusive(\"configure")
        assertThat(configure.indexOf("playOutPipelineTailBeforeRebuild()")).isLessThan(configure.indexOf("owner.exclusive("))
        assertThat(sink).contains("usbAudioDevice.owner.exclusive(\"release\") { releaseOwnedStream(stream) }")
        assertThat(sink.substringAfter("private fun releaseOwnedStream(")).contains("usbAudioDevice.owner.clearStream()")
        assertThat(sink.substringAfter("private fun rebuildUsbStream(")).contains("usbAudioDevice.owner.publishStream(sampleRate, altSetting)")
        // the recovery reads its own generation's facts: the sink's mutable rate and alt are not what a recovery restarts
        assertThat(sink).doesNotContain("currentAltSetting")
        assertThat(sink).doesNotContain("dacRecoveryHost")
        assertThat(sink).contains("OwnedRecoveryHost(usbAudioDevice.owner, streamingSequence, facts.generation)")
    }

    @Test
    fun `a new stream receives the attachments previous rate before publishing its own`() {
        assertThat(sink).contains("stream.start(usbAudioDevice.owner.previousStreamRateHz)")
        assertThat(sink.indexOf("stream.start(usbAudioDevice.owner.previousStreamRateHz)")).isLessThan(
            sink.indexOf("usbAudioDevice.owner.publishStream(sampleRate, altSetting)"),
        )
    }

    @Test
    fun `a dead stream whose DAC is gone tells the owner before it releases`() {
        val dead = sink.substringAfter("if (stream != null && !stream.isAlive) {").substringBefore("stream = null")
        assertThat(dead).contains("usbAudioDevice.owner.deviceDetached()")
        assertThat(dead.indexOf("deviceDetached()")).isLessThan(dead.indexOf("releaseUsbStream()"))
    }

    @Test
    fun `the hand-back to Android is an exclusive section that clears the stream's facts`() {
        val handBack = device.substringAfter("fun releaseToAndroid() {").substringBefore("private fun handBackToAndroid()")
        assertThat(handBack).contains("owner.exclusive(\"hand-back\")")
        assertThat(handBack).contains("owner.clearStream()")
        assertThat(handBack).contains("handBackToAndroid()")
        assertThat(device).contains("val owner = UsbDeviceOwner(")
    }

    @Test
    fun `the writer takes the device before it touches the ring and always lets go`() {
        val step = thread.substringAfter("private fun performDacRecovery(step: Int)").substringBefore("/** The next audio a pause can ramp down")
        assertThat(step.indexOf("host.begin(step)")).isLessThan(step.indexOf("usbStream.beginPauseFade()"))
        assertThat(step).contains("host.end(step)")
        assertThat(step.indexOf("} finally {")).isGreaterThan(step.indexOf("recoverWithTheDevice(host, step)"))
        assertThat(step).contains("recoveryOver = true")
    }

    @Test
    fun `the engine tells the owner when the bus has no DAC, after the pause and without waiting`() {
        val refresh = engine.substringAfter("fun refreshUsbAudioDiagnostics() {").substringBefore("val masterSink")
        assertThat(refresh).contains("if (usbDevice == null) usbAudioDevice.owner.deviceDetached()")
        assertThat(refresh.indexOf("observeUsbPresence(")).isLessThan(refresh.indexOf("deviceDetached()"))
    }

    @Test
    fun `the owner never holds its monitor across a device call`() {
        val exclusive = owner.substringAfter("fun <T> exclusive(who: String, block: () -> T): T {").substringBefore("private fun enter(")
        assertThat(exclusive).doesNotContain("synchronized")
        assertThat(exclusive).contains("enter(who)")
        assertThat(exclusive).contains("return block()")
        // the one log line a superseded recovery writes
        assertThat(owner).contains("DAC_RECOVERY step=\$step result=superseded gen=\$generation now=\$generationValue by=\$why")
        // asking the bus is done outside the monitor
        assertThat(owner.substringAfter("fun claimForRecovery(").substringBefore("synchronized(monitor)")).contains("val gone = deviceGone()")
    }
}
