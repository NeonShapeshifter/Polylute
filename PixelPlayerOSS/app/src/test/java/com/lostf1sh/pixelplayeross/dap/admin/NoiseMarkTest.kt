package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * B27-A5 (review N-07): the "mark noise now" file. The output keeps the seconds (usb-second-ring-test.cpp: the last 30 s and
 * nothing more); this is what is written around them, and that it never overwrites an earlier mark.
 */
class NoiseMarkTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-16 14:41:00.123 UTC, the owner's BIAF<3 at 96 kHz
    private val wall = 1_792_161_660_123L

    private val facts = NoiseMark.Facts(
        wall,
        listOf(
            "build" to "0.3.0 (3) debug",
            "song" to "4814 BIAF<3",
            "route" to "USB_DIRECT DIRECT_ACTIVE",
            "usb.altSetting" to "1",
            "transport" to "96000 Hz, 32 bit, 2 ch",
            "feedback.policy" to "auto",
            "volume.dac" to "USB_HARDWARE_VOLUME step 17 of 0..60, confirmed by the DAC true",
        ),
    )

    private val seconds = "usb-seconds window=30s samples=2 policy=auto stream[known rate=96000]\n" +
        "t-3s feedback n=1000 fpmf min=12.00490 max=12.00490 mean=12.00490 nominal=12.00000 deviating=1000 refused=0 | iso urbs=1000 packets=8000 errors=0 short=0\n" +
        "t-2s feedback n=1000 fpmf min=12.00490 max=12.00490 mean=12.00490 nominal=12.00000 deviating=1000 refused=0 | iso urbs=1000 packets=8000 errors=0 short=0\n"

    @Test
    fun `the file is named after the moment, in the phone's clock`() {
        assertThat(NoiseMark.fileName(wall, utc)).isEqualTo("noise-20261016-144100.txt")
        assertThat(NoiseMark.fileName(wall, TimeZone.getTimeZone("Europe/Stockholm"))).isEqualTo("noise-20261016-164100.txt")
    }

    @Test
    fun `the text has the moment, every field of the stream's state, and the seconds as the output reported them`() {
        val text = NoiseMark.compose(facts, seconds = 30, secondSummaries = seconds, zone = utc)

        assertThat(text).startsWith("NOISE MARK\n")
        assertThat(text).contains("when=2026-10-16 14:41:00.123 UTC wallMs=1792161660123")
        assertThat(text).contains("window=30s")
        for ((name, value) in facts.fields) assertThat(text).contains("$name=$value\n")
        assertThat(text).contains(seconds)
        // the state comes before the seconds, and the fields keep the order they were given in
        assertThat(text.indexOf("route=")).isLessThan(text.indexOf("volume.dac="))
        assertThat(text.indexOf("volume.dac=")).isLessThan(text.indexOf("t-3s feedback"))
    }

    @Test
    fun `an output that reported nothing is said, not left blank`() {
        val text = NoiseMark.compose(facts, seconds = 30, secondSummaries = "", zone = utc)

        assertThat(text).contains("(the output reported nothing)")
    }

    @Test
    fun `a mark is written under its directory, and a second one in the same second does not replace the first`(@TempDir dir: File) {
        val first = NoiseMark.write(File(dir, NoiseMark.DIRECTORY), wall, "first", utc)
        val second = NoiseMark.write(File(dir, NoiseMark.DIRECTORY), wall, "second", utc)

        assertThat(first.name).isEqualTo("noise-20261016-144100.txt")
        assertThat(second.name).isEqualTo("noise-20261016-144100-1.txt")
        assertThat(first.readText()).isEqualTo("first")
        assertThat(second.readText()).isEqualTo("second")
        assertThat(first.parentFile!!.name).isEqualTo("noise-marks")
    }

    @Test
    fun `the default window is the thirty seconds the output is asked for`() {
        assertThat(NoiseMark.DEFAULT_SECONDS).isEqualTo(30)
    }
}
