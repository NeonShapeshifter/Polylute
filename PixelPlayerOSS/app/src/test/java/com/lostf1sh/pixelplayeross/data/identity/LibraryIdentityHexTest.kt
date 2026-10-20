package com.lostf1sh.pixelplayeross.data.identity

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.jupiter.api.Test

/**
 * B13: `sha1Hex` was `digest.joinToString("") { "%02x".format(it) }`, ~200,000 `String.format` calls per library
 * scan on a 4,870-song library, and it is now a lookup. The strings are stored identities, so the output must be
 * byte for byte what the formatter produced: this compares the two over every digest the arithmetic can reach for
 * a wide spread of inputs.
 */
class LibraryIdentityHexTest {

    private fun reference(payload: String): String =
        MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    @Test
    fun `the file signature is what the String format version produced`() {
        var checked = 0
        for (duration in listOf(0L, 1L, 59_999L, 241_000L, 3_600_000L, Long.MAX_VALUE / 2)) {
            for (size in listOf(0L, 1L, 4_096L, 31_999_999L, 912_345_678L)) {
                // The identity's payload is `duration SEP size` with SEP = U+0001 (see LibraryIdentity.SEP).
                val expected = reference("${duration.coerceAtLeast(0L)}\u0001${size.coerceAtLeast(0L)}")
                assertThat(LibraryIdentity.computeFileSignature(duration, size)).isEqualTo(expected)
                checked++
            }
        }
        assertThat(checked).isEqualTo(30)
    }

    @Test
    fun `every possible byte value is written as two lowercase hex digits`() {
        // Drive many payloads so that digests contain all 256 byte values, including the ones >= 0x80 that are
        // negative as a Kotlin Byte (the case a naive lookup gets wrong).
        val seen = BooleanArray(256)
        var n = 0
        while (seen.any { !it } && n < 20_000) {
            val signature = LibraryIdentity.computeFileSignature(n.toLong(), (n * 7919L) % 1_000_003L)
            assertThat(signature).matches("[0-9a-f]{40}")
            for (i in 0 until 40 step 2) seen[signature.substring(i, i + 2).toInt(16)] = true
            n++
        }
        assertThat(seen.all { it }).isTrue()
    }

    @Test
    fun `the lookup agrees with the formatter on every byte value`() {
        // The formatter's rule for one byte, against the lookup's, for all 256 values.
        val digits = "0123456789abcdef".toCharArray()
        for (v in -128..127) {
            val byte = v.toByte()
            val viaFormat = "%02x".format(byte)
            val unsigned = byte.toInt() and 0xFF
            val viaLookup = "${digits[unsigned ushr 4]}${digits[unsigned and 0x0F]}"
            assertThat(viaLookup).isEqualTo(viaFormat)
        }
    }
}
