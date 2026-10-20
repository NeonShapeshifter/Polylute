package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-061: the metadata editor logged its progress and successes — with file paths, and the file's
 * full tag list — at ERROR, so every edit left error-level lines (and personal data) in the log. Only
 * real failures stay at ERROR; progress is DEBUG and the tag contents are not logged.
 */
class MetadataEditorLogLevelBoundaryTest {
    private val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/media/SongMetadataEditor.kt").readText()

    @Test
    fun `no progress or success line is logged as an error`() {
        val errorLines = source.lines().filter { it.contains(".e(\"") }
        val progress = listOf("SUCCESS", "Successfully", "Opening file", "Getting existing", "Updated property map",
            "savePropertyMap result", "Reading Opus", "Existing tags", "Updated tags", "Writing to temp file")
        for (line in errorLines) {
            for (word in progress) assertThat(line).doesNotContain(word)
        }
    }

    @Test
    fun `tag contents are never logged`() {
        assertThat(source).doesNotContain("\${tags.allComments}")
    }
}
