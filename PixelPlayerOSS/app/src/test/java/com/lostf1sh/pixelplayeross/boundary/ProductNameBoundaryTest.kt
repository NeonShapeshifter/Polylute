package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.PixelPlayerApplication
import com.lostf1sh.pixelplayeross.data.backup.format.BackupCrypto
import com.lostf1sh.pixelplayeross.presentation.SpecialThanksFixtures
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.utils.CrashLogData
import com.lostf1sh.pixelplayeross.utils.ZipShareHelper
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * B38 (E2): the old product name, "PixelPlayerOSS" (and "PixelPlayer"), is not in any text a user can see.
 *
 * Two scans and one check per renamed thing. The scans read every string value of `values*` (main and debug) and every string literal of the
 * Kotlin sources (main and debug; comments are not text a user sees, so a small lexer drops them). Each hit must be on the allow-list below,
 * and each allow-list entry says why the name stays; a new hit fails with its file and line. The checks call the real code: the file names, the
 * folder, the share subject, the channel name and the report titles say Polylute.
 *
 * What is not text a user sees, and so is not looked at: package, class, resource and style names, the intent actions, the Room, DataStore and
 * SharedPreferences names, the notification channel *id*, the cache-file prefixes and the logcat tags. They are identifiers; some name the
 * owner's data and a rename would orphan it.
 */
class ProductNameBoundaryTest {

    private val old = Regex("PixelPlayer", RegexOption.IGNORE_CASE)

    private fun sourceFiles(): List<File> =
        listOf("src/main/java", "src/debug/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    private fun valueFiles(): List<File> =
        listOf("src/main/res", "src/debug/res").flatMap { root ->
            File(root).listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().toList() }
        }

    // --- the resources -----------------------------------------------------------------------------------------------------------------

    /**
     * Every line of a `values*` file that names the old product in its *value* (the `name="..."` of a resource is an identifier and is
     * dropped before looking). Each one allowed is allowed for a reason.
     */
    private val allowedResourceHits: Map<String, String> = mapOf(
        "about_special_thanks_pixelplayer" to
            "the credit: the thanks name PixelPlayer, the app Polylute was made from, in English, Spanish and Swedish",
        "pixelplayer.disableReleaseSigning" to
            "the Gradle property's own name, quoted in a line of upstream's 0.7.0 changelog that no screen reads (lint: unused)",
    )

    @Test
    fun `no string value of any language names the old product except the credits`() {
        val offenders = mutableListOf<String>()
        var allowedSeen = 0
        valueFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val value = line.replace(Regex("""name="[^"]*""""), "")
                if (!old.containsMatchIn(value)) return@forEachIndexed
                if (allowedResourceHits.keys.any { it in line }) allowedSeen++ else offenders += "${file.path}:${index + 1}: ${line.trim().take(120)}"
            }
        }
        assertWithMessage("a value that shows the old name").that(offenders).isEmpty()
        // guard the guard: the scan did read the three credit lines and the changelog line (3 + 1), so it is not looking at nothing
        assertThat(allowedSeen).isEqualTo(4)
    }

    @Test
    fun `the backup file name format and the export folder say Polylute`() {
        val en = SpecialThanksFixtures.stringValues("values")
        assertThat(en.getValue("backup_file_name_format")).isEqualTo("Polylute_Backup_%1\$d.pxpl")
        assertThat(String.format(en.getValue("backup_file_name_format"), 1760000000000L)).isEqualTo("Polylute_Backup_1760000000000.pxpl")
        // the folder the exports go to, and the words the toast uses for it, are one name
        assertThat(PlaylistViewModel.EXPORT_FOLDER_NAME).isEqualTo("Polylute Exports")
        assertThat(en.getValue("playlist_export_folder_display")).isEqualTo("Music/" + PlaylistViewModel.EXPORT_FOLDER_NAME)
    }

    // --- the code ----------------------------------------------------------------------------------------------------------------------

    /** String literals of the Kotlin sources that hold the old name as a word, and why each stays. */
    private val allowedLiteralHits: Map<String, String> = mapOf(
        "MusicService_PixelPlayer" to "a logcat tag (an identifier read by developers and by the Maintenance log export, not shown on a screen)",
    )

    @Test
    fun `no string literal in the sources names the old product except the allow-listed log tag`() {
        val offenders = mutableListOf<String>()
        var literalsRead = 0
        var allowedSeen = 0
        // "PixelPlayer" or "PixelPlayerOSS" as a word: not the start of PixelPlayerGlanceWidget or PixelPlayerBenchmark, not the package pixelplayeross
        val word = Regex("PixelPlayer(?:OSS)?(?![A-Za-z0-9])")
        sourceFiles().forEach { file ->
            KotlinLiterals.of(file.readText()).forEach { literal ->
                literalsRead++
                if (!word.containsMatchIn(literal.text)) return@forEach
                if (literal.text in allowedLiteralHits) allowedSeen++ else offenders += "${file.path}:${literal.line}: \"${literal.text.take(100)}\""
            }
        }
        assertWithMessage("a literal that shows the old name").that(offenders).isEmpty()
        // guard the guard: thousands of literals were read, and the log tag was among them (MusicService.kt twice, ReplayGainProcessor.kt once)
        assertThat(literalsRead).isGreaterThan(5000)
        assertThat(allowedSeen).isEqualTo(3)
    }

    @Test
    fun `the notification channel is named Polylute and keeps its id`() {
        assertThat(PixelPlayerApplication.NOTIFICATION_CHANNEL_NAME).isEqualTo("Polylute Music Playback")
        // the id is an identifier: Android keys the user's channel settings on it
        assertThat(PixelPlayerApplication.NOTIFICATION_CHANNEL_ID).isEqualTo("pixelplayer_music_channel")
    }

    @Test
    fun `the crash report says Polylute in its title`() {
        val log = CrashLogData(timestamp = 1L, formattedDate = "d", exceptionMessage = "e", stackTrace = "s").getFullLog()
        assertThat(log.lines().first()).isEqualTo("=== Polylute Crash Report ===")
    }

    @Test
    fun `the share zip and its subject say Polylute`() {
        assertThat(ZipShareHelper.zipFileName("20261020_120000")).isEqualTo("Polylute_Songs_20261020_120000.zip")
        assertThat(ZipShareHelper.shareSubject(3)).isEqualTo("Polylute: 3 Songs")
    }

    @Test
    fun `a file that is not an encrypted backup is refused with a message that says Polylute`() {
        val error = assertThrows<IllegalArgumentException> {
            BackupCrypto.decryptingStream(ByteArrayInputStream(ByteArray(64)), "passphrase")
        }
        assertThat(error).hasMessageThat().isEqualTo("Not an encrypted Polylute backup.")
    }

    @Test
    fun `the messages and report titles that live inside larger code say Polylute`() {
        // These are literals inside long functions (a progress report, a bottom sheet, a text rendering); the scan above is what proves the old
        // name is gone from them, and this pins the new wording so a revert to a neutral word is also caught.
        fun source(path: String) = File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText()
        assertThat(source("data/backup/AppDataBackupManager.kt")).contains("Your Polylute backup was created successfully.")
        assertThat(source("presentation/components/FileExplorerBottomSheet.kt")).contains("while Polylute scans the available subfolders.")
        assertThat(source("data/diagnostics/DebugPerformanceReport.kt")).contains("Polylute performance report (schema v")
    }
}

/**
 * The string literals of a Kotlin source file, with their line numbers. Comments are skipped, `"..."` and `"""..."""` are read with their
 * escapes left as written, and a `${...}` template inside a string is read as code (its own literals are literals too). Enough to find a name in
 * the text a program shows; not a parser.
 */
internal object KotlinLiterals {
    class Literal(val line: Int, val text: String)

    fun of(source: String): List<Literal> {
        val found = mutableListOf<Literal>()
        Lexer(source, found).code(closeBrace = false)
        return found
    }

    private class Lexer(private val s: String, private val out: MutableList<Literal>) {
        private var i = 0
        private var line = 1

        private fun peek(offset: Int = 0): Char = if (i + offset < s.length) s[i + offset] else '\u0000'
        private fun advance(): Char {
            val c = s[i++]
            if (c == '\n') line++
            return c
        }

        /** Reads code up to the end of the file, or up to the `}` that closes a template when [closeBrace] is set. */
        fun code(closeBrace: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = peek()
                when {
                    c == '/' && peek(1) == '/' -> while (i < s.length && peek() != '\n') advance()
                    c == '/' && peek(1) == '*' -> blockComment()
                    c == '"' && peek(1) == '"' && peek(2) == '"' -> rawString()
                    c == '"' -> string()
                    c == '\'' -> charLiteral()
                    c == '{' -> { depth++; advance() }
                    c == '}' -> {
                        if (closeBrace && depth == 0) { advance(); return }
                        depth--; advance()
                    }
                    else -> advance()
                }
            }
        }

        private fun blockComment() {
            var nesting = 0
            while (i < s.length) {
                if (peek() == '/' && peek(1) == '*') { nesting++; advance(); advance() }
                else if (peek() == '*' && peek(1) == '/') { nesting--; advance(); advance(); if (nesting == 0) return }
                else advance()
            }
        }

        private fun charLiteral() {
            advance() // '
            if (peek() == '\\') { advance(); advance() } else advance()
            while (i < s.length && peek() != '\'' && peek() != '\n') advance() // A and the like
            if (peek() == '\'') advance()
        }

        private fun string() {
            val startLine = line
            advance() // opening "
            val text = StringBuilder()
            while (i < s.length) {
                val c = peek()
                when {
                    c == '\\' -> { text.append(advance()); if (i < s.length) text.append(advance()) }
                    c == '"' -> { advance(); out += Literal(startLine, text.toString()); return }
                    c == '$' && peek(1) == '{' -> { advance(); advance(); code(closeBrace = true) }
                    c == '\n' -> { out += Literal(startLine, text.toString()); return } // unterminated: not a string, stop
                    else -> text.append(advance())
                }
            }
        }

        private fun rawString() {
            val startLine = line
            advance(); advance(); advance()
            val text = StringBuilder()
            while (i < s.length) {
                if (peek() == '"' && peek(1) == '"' && peek(2) == '"') {
                    advance(); advance(); advance()
                    while (peek() == '"') advance()
                    out += Literal(startLine, text.toString())
                    return
                }
                if (peek() == '$' && peek(1) == '{') { advance(); advance(); code(closeBrace = true); continue }
                text.append(advance())
            }
        }
    }
}
