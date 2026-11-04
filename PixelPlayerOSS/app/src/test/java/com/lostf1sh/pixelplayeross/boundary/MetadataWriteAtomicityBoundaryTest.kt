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
        // B57-fix1 changes this on purpose: each writer is tried in turn, each on its own fresh copy of the original, and a copy that does not carry the original's audio replaces nothing
        assertThat(edit).contains("writeMetadataViaExtensionSwap(finalFilePath, effectiveExtension, writerFor(writer))")
        assertThat(editor).contains("AudioPayloadGuard.sameAudio(originalFile, tempFile, correctExtension)")
    }

    @Test
    fun `a writer that wrote nothing is never reported as having written`() {
        // B57-fix1 5.3 changes this on purpose: TagLib no longer declines high-resolution FLAC (the host probe shows it writes it safely, and the audio guard is the net under it), so there is no
        // declined file left to report. What the test keeps from AUD-034: nothing is reported written before anything was written.
        val tagLib = body("private fun updateFileMetadataWithTagLib(")
        assertThat(tagLib).doesNotContain("FlacAnalysisResult")
        assertThat(tagLib.substringBefore("TagLib.savePropertyMap")).doesNotContain("return true")
    }
}
