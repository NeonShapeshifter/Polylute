package com.lostf1sh.pixelplayeross.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** #79: an `R128_TRACK_GAIN` of -438 was read as -438 dB (a factor of 10⁻²²) and the song played silent. R128 is Q7.8 relative to −23 LUFS. */
class ReplayGainR128Test {

    private fun tags(vararg pairs: Pair<String, String>): Map<String, Array<String>> =
        pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.toTypedArray() }

    private val trackKeys = listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN_DB", "R128_TRACK_GAIN")

    @Test
    fun `R128 -438 is about +3,3 dB of ReplayGain, not -438 dB`() {
        val db = ReplayGainTags.gainDb(tags("R128_TRACK_GAIN" to "-438"), trackKeys)!!
        assertThat(db).isWithin(0.01f).of(-438 / 256f + 5f)
        assertThat(ReplayGainManager().gainDbToVolume(db)).isGreaterThan(1f)
    }

    @Test
    fun `R128 0 is +5 dB and a loud master's R128 is a cut`() {
        assertThat(ReplayGainTags.parse("R128_TRACK_GAIN", "0")).isEqualTo(5f)
        assertThat(ReplayGainTags.parse("R128_ALBUM_GAIN", "-2560")).isEqualTo(-5f)
    }

    @Test
    fun `the ReplayGain text tag wins over R128 when a file has both`() {
        val map = tags("REPLAYGAIN_TRACK_GAIN" to "-6.54 dB", "R128_TRACK_GAIN" to "-438")
        assertThat(ReplayGainTags.gainDb(map, trackKeys)).isEqualTo(-6.54f)
    }

    @Test
    fun `ReplayGain text reads with or without dB and with a comma`() {
        assertThat(ReplayGainTags.parse("REPLAYGAIN_TRACK_GAIN", "+3.21 dB")).isEqualTo(3.21f)
        assertThat(ReplayGainTags.parse("REPLAYGAIN_TRACK_GAIN", "-8,2 DB")).isEqualTo(-8.2f)
        assertThat(ReplayGainTags.parse("REPLAYGAIN_TRACK_GAIN", "-7")).isEqualTo(-7f)
    }

    @Test
    fun `a value that is no real correction is ignored and the next tag is used`() {
        assertThat(ReplayGainTags.parse("REPLAYGAIN_TRACK_GAIN", "-438 dB")).isNull()
        assertThat(ReplayGainTags.parse("REPLAYGAIN_TRACK_GAIN", "60")).isNull()
        assertThat(ReplayGainTags.parse("REPLAYGAIN_TRACK_GAIN", "NaN")).isNull()
        assertThat(ReplayGainTags.parse("R128_TRACK_GAIN", "-1.5")).isNull()
        val map = tags("REPLAYGAIN_TRACK_GAIN" to "-438", "R128_TRACK_GAIN" to "-438")
        assertThat(ReplayGainTags.gainDb(map, trackKeys)).isWithin(0.01f).of(-438 / 256f + 5f)
    }

    @Test
    fun `a file with only broken tags gets no gain, so it plays at its own level`() {
        val map = tags("REPLAYGAIN_TRACK_GAIN" to "loud")
        assertThat(ReplayGainTags.gainDb(map, trackKeys)).isNull()
        assertThat(ReplayGainManager().getVolumeMultiplier(null)).isEqualTo(1f)
    }
}
