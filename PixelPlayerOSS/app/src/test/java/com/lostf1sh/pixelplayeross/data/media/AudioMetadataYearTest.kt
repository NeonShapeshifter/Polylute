package com.lostf1sh.pixelplayeross.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** FIX-3: the year a file's tags give, from the keys taggers use (Vorbis `DATE`, ID3 `YEAR`, ...). */
class AudioMetadataYearTest {

    private fun tags(vararg pairs: Pair<String, String>): Map<String, Array<String>> =
        pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.toTypedArray() }

    @Test
    fun `DATE as a full date gives its year, the case of the owner's FLACs`() {
        assertThat(AudioMetadataReader.yearFromPropertyMap(tags("DATE" to "1998-01-01"))).isEqualTo(1998)
    }

    @Test
    fun `DATE wins over the other keys, which are only fallbacks`() {
        val map = tags("DATE" to "1998-01-01", "YEAR" to "2005", "ORIGINALDATE" to "1990")
        assertThat(AudioMetadataReader.yearFromPropertyMap(map)).isEqualTo(1998)
    }

    @Test
    fun `a garbage DATE falls back to the next key that has a year`() {
        assertThat(AudioMetadataReader.yearFromPropertyMap(tags("DATE" to "garbage", "YEAR" to "2003"))).isEqualTo(2003)
        assertThat(AudioMetadataReader.yearFromPropertyMap(tags("DATE" to "0000", "ORIGINALDATE" to "1971-03-04"))).isEqualTo(1971)
    }

    @Test
    fun `a multi-valued tag uses the first value that is a year`() {
        val map = mapOf("DATE" to arrayOf("", "n/a", "1999"))
        assertThat(AudioMetadataReader.yearFromPropertyMap(map)).isEqualTo(1999)
    }

    @Test
    fun `no usable tag is no year`() {
        assertThat(AudioMetadataReader.yearFromPropertyMap(emptyMap())).isNull()
        assertThat(AudioMetadataReader.yearFromPropertyMap(tags("TITLE" to "1998"))).isNull()
        assertThat(AudioMetadataReader.yearFromPropertyMap(tags("DATE" to "abcd"))).isNull()
    }
}
