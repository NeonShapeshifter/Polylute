package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Invented text only: UTF-8, LF, umlauts, a blank line and a literal backslash. */
internal val syntheticPlainLrc: String = "Prüfzeile äöü ß \\ Trennzeichen\n\nZweite erfundene Testzeile\n".let {
    it + "synthetic words ".repeat(100).take(900 - it.toByteArray(Charsets.UTF_8).size)
}

class B625PlainLrcTest {
    private fun validate(raw: String, fileName: String = "synthetic.lrc") = LyricsImportSecurity.validateImportedLyricsFile(
        fileName, "text/plain", raw.byteInputStream(Charsets.UTF_8), raw.toByteArray(Charsets.UTF_8).size.toLong(),
    )

    @Test fun importAcceptsPlainUtf8AndPreservesUmlautsAndTheSeparator() {
        assertThat(syntheticPlainLrc.toByteArray(Charsets.UTF_8).size).isEqualTo(900)
        val parsed = LyricsUtils.parseLyrics(syntheticPlainLrc)
        println("B625 plain fixture bytes=${syntheticPlainLrc.toByteArray().size} lines=${syntheticPlainLrc.lines().size} plainLines=${parsed.plain?.size} direct=${LyricsImportSecurity.validateImportedLrcContent(syntheticPlainLrc)::class.simpleName}")
        val result = validate(syntheticPlainLrc)
        assertThat(result).isInstanceOf(LyricsImportValidationResult.Valid::class.java)
        val valid = (result as LyricsImportValidationResult.Valid).value
        assertThat(valid.parsedLyrics.synced.isNullOrEmpty()).isTrue()
        assertThat(valid.parsedLyrics.plain!!.first()).isEqualTo("Prüfzeile äöü ß \\ Trennzeichen")
        assertThat(valid.sanitizedContent).contains("\n\n")
        assertThat(valid.parsedLyrics.areFromRemote).isFalse()
    }

    @Test fun plainTextUsesTheExistingSanitizerAndSizeLimit() {
        val result = LyricsImportSecurity.validateImportedLrcContent("\uFEFF\u202ePrüfzeile\r\n\r\nTesttext") as LyricsImportValidationResult.Valid
        assertThat(result.value.sanitizedContent).isEqualTo("Prüfzeile\n\nTesttext")
        assertThat(LyricsImportSecurity.validateImportedLrcContent("x".repeat(LyricsImportSecurity.MAX_LYRICS_TEXT_CHARS + 1)))
            .isEqualTo(LyricsImportValidationResult.Invalid(LyricsImportFailureReason.FILE_TOO_LARGE))
    }

    @Test fun unreadableDocumentsStayRejectedAndTtmlStillRequiresTiming() {
        for (raw in listOf("", "  \n\t", "\u0000\u0001\u0002", "[ar:Synthetic]\n[al:Fixture]")) {
            assertThat(validate(raw)).isInstanceOf(LyricsImportValidationResult.Invalid::class.java)
        }
        val binary = LyricsImportSecurity.validateImportedLyricsFile("synthetic.lrc", "application/octet-stream", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47).inputStream())
        assertThat(binary).isInstanceOf(LyricsImportValidationResult.Invalid::class.java)
        assertThat(validate(syntheticPlainLrc, "synthetic.ttml")).isInstanceOf(LyricsImportValidationResult.Invalid::class.java)
    }

    @Test fun syncedAndEnhancedTimingAreUnchanged() {
        val timed = (validate("[00:01.00]Synthetic line") as LyricsImportValidationResult.Valid).value.parsedLyrics
        assertThat(timed.synced!!.single().time).isEqualTo(1000)
        val enhanced = (validate("[00:01.00]<00:01.00>Synthetic <00:02.00>words") as LyricsImportValidationResult.Valid).value.parsedLyrics
        assertThat(enhanced.synced!!.single().words!!.map { it.time }).containsExactly(1000, 2000).inOrder()
    }
}
