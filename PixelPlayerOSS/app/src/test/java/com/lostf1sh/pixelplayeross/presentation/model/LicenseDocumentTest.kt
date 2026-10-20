package com.lostf1sh.pixelplayeross.presentation.model

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LicenseDocumentTest {
    @Test
    fun `bundled notices preserve every heading paragraph and table cell`() {
        val source = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md").readText()
        val blocks = licenseBlocks(source, markdown = true)
        val presented = blocks.joinToString("\n") { block ->
            when (block) {
                is LicenseBlock.Heading -> block.text
                is LicenseBlock.Paragraph -> block.text
                is LicenseBlock.Table -> (listOf(block.headers) + block.rows)
                    .joinToString("\n") { it.joinToString(" ") }
            }
        }
        val expected = source.lines().filterNot { it.startsWith("| ---") }
            .joinToString("\n").replace("# ", "").replace("|", " ")
        fun words(s: String) = s.trim().split(Regex("\\s+"))
        assertEquals(words(expected), words(presented))
        assertEquals(6, blocks.filterIsInstance<LicenseBlock.Table>().size)
        assertEquals(41, blocks.filterIsInstance<LicenseBlock.Table>().sumOf { it.rows.size })
    }

    @Test
    fun `native libraries table names every shipped library with a license a version and a source`() {
        val source = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md").readText()
        val native = licenseBlocks(source, markdown = true).filterIsInstance<LicenseBlock.Table>()
            .single { it.headers.first() == "Library" }
        assertEquals(listOf("Library", "What it is", "License", "Version", "Source"), native.headers)
        // The five libraries in the release bundle (app-release.aab, lib/<abi>/), nothing more and nothing less.
        assertEquals(
            setOf(
                "libffmpegJNI.so", "libtaglib.so", "libandroidx.graphics.path.so",
                "libflacJNI.so", "libdecent_usb_audio.so",
            ),
            native.rows.map { licenseInlineText(it[0]) }.toSet(),
        )
        assertTrue(native.rows.all { row -> row.drop(2).all { it.isNotBlank() } })
    }

    @Test
    fun `the FFmpeg row states its license and where its corresponding source is`() {
        val source = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md").readText()
        val ffmpeg = licenseBlocks(source, markdown = true).filterIsInstance<LicenseBlock.Table>()
            .single { it.headers.first() == "Library" }
            .rows.single { licenseInlineText(it[0]) == "libffmpegJNI.so" }
        assertTrue(ffmpeg[2].contains("GPL-3.0"))
        assertTrue(ffmpeg[4].contains("https://github.com/jellyfin/jellyfin-androidx-media"))
        assertTrue(ffmpeg[4].contains("v1.9.0+1"))
        assertTrue(ffmpeg[4].contains("37c90f55d8b6c3948c79449b04755527b4ae1351"))
        assertTrue(ffmpeg[4].contains("7cc1056f840ce226598d3b990d4a6f7cd17e2831"))
    }

    @Test
    fun `notices name the FFmpeg decoder and TagLib versions the build uses`() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val notices = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md").readText()
        val ffmpeg = Regex("media3-ffmpeg-decoder\", version = \"([^\"]+)\"").find(catalog)!!.groupValues[1]
        val taglib = Regex("^taglib = \"([^\"]+)\"", RegexOption.MULTILINE).find(catalog)!!.groupValues[1]
        assertTrue(notices.contains("media3-ffmpeg-decoder:$ffmpeg"))
        assertTrue(notices.contains("io.github.kyant0:taglib:$taglib"))
    }

    @Test
    fun `font license preserves all content and links`() {
        val source = File("src/main/assets/licenses/OFL.txt").readText()
        val blocks = licenseBlocks(source, markdown = false)
        assertTrue(blocks.all { it is LicenseBlock.Paragraph })
        val presented = blocks.joinToString("\n\n") { (it as LicenseBlock.Paragraph).text }
        assertEquals(source.trim().replace(Regex("\\n{2,}"), "\n\n"),
            presented.trim().replace(Regex("\\n{2,}"), "\n\n"))
        assertEquals(licenseUrlPattern.findAll(source).map { it.value }.toList(),
            licenseUrlPattern.findAll(presented).map { it.value }.toList())
    }

    /**
     * B37 (C2): the GPL travels with the app as a checked-in copy of the repository's `LICENSE`, shown by the Licenses screen. Not generated at
     * build time: a copy that drifts fails this test, in `testDebugUnitTest`, which every gate runs.
     */
    @Test
    fun `the bundled GPL text is the repository LICENSE byte for byte`() {
        val bundled = File("src/main/assets/licenses/LICENSE")
        val repository = File("../LICENSE")
        assertTrue("the bundled copy exists", bundled.isFile)
        assertTrue("the repository LICENSE exists", repository.isFile)
        assertArrayEquals(repository.readBytes(), bundled.readBytes())
        assertTrue(bundled.readText().contains("GNU GENERAL PUBLIC LICENSE") && bundled.readText().contains("Version 3, 29 June 2007"))
    }

    @Test
    fun `the GPL text preserves all content and links`() {
        val source = File("src/main/assets/licenses/LICENSE").readText()
        val blocks = licenseBlocks(source, markdown = false)
        assertTrue(blocks.all { it is LicenseBlock.Paragraph })
        val presented = blocks.joinToString("\n\n") { (it as LicenseBlock.Paragraph).text }
        fun words(s: String) = s.trim().split(Regex("\\s+"))
        assertEquals(words(source), words(presented))
        assertEquals(licenseUrlPattern.findAll(source).map { it.value }.toList(),
            licenseUrlPattern.findAll(presented).map { it.value }.toList())
    }

    @Test
    fun `unexpected table cells and syntax are retained`() {
        val blocks = licenseBlocks("| A | B |\n| --- | --- |\n| one | two | three |\n## Notice", true)
        assertTrue((blocks.last() as LicenseBlock.Paragraph).text.contains("three"))
        assertTrue((blocks.last() as LicenseBlock.Paragraph).text.contains("## Notice"))
        assertEquals("GPL-3.0, https://example.org", licenseInlineText("`GPL-3.0`, `https://example.org`"))
    }
}
