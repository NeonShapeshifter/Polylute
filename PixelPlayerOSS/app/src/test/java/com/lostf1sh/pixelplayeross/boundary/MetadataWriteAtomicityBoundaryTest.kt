package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-034: the tag editor.
 *
 * - TagLib opened the original file `MODE_READ_WRITE` and saved over it, so a power cut or crash
 *   mid-save could corrupt the audio. Every write now goes to a copy that replaces the original
 *   (sibling temp + fsync + rename) — the path the extension-mismatch case already used.
 * - When TagLib declined a high-resolution FLAC it returned `true` without writing anything, so the
 *   edit was reported as saved and lost on the next scan. Declining now reports failure, which lets
 *   the JAudioTagger fallback write the file.
 *
 * Source-level: the editor needs a Context, MediaStore and the native TagLib.
 */
class MetadataWriteAtomicityBoundaryTest {

    private val editor = File("src/main/java/com/lostf1sh/pixelplayeross/data/media/SongMetadataEditor.kt").readText()

    private fun body(signature: String): String {
        val start = editor.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = editor.indexOf('{', start)
        var depth = 0
        for (i in open until editor.length) {
            when (editor[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return editor.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `every file write goes through the copy-and-replace path`() {
        val edit = body("suspend fun editSongMetadata(")
        assertThat(edit).doesNotContain("runPipeline(finalFilePath)")
        assertThat(edit).contains("writeMetadataViaExtensionSwap(finalFilePath, effectiveExtension, runPipeline)")
    }

    @Test
    fun `a high-resolution FLAC declined by TagLib is not reported as written`() {
        val tagLib = body("private fun updateFileMetadataWithTagLib(")
        val skip = tagLib.substringAfter("is FlacAnalysisResult.Problematic ->").substringBefore("else ->")
        assertThat(skip).doesNotContain("return true")
        assertThat(skip).contains("return false")
    }
}
