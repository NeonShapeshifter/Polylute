package com.lostf1sh.pixelplayeross.presentation.model

/**
 * The documents the Licenses screen reads from the APK's assets: the only assets the app opens, and the only ones in `assets/`
 * (`AppAssetsBoundaryTest`). `GPL` is a checked-in copy of the repository's `LICENSE`, pinned equal to it, byte for byte, by
 * `LicenseDocumentTest`.
 */
internal object LicenseAssets {
    const val GPL = "licenses/LICENSE"
    const val THIRD_PARTY = "licenses/THIRD_PARTY_NOTICES.md"
    const val FONTS = "licenses/OFL.txt"
}

/** Presentation only: the bundled notices remain the source of truth. */
internal sealed interface LicenseBlock {
    data class Heading(val text: String) : LicenseBlock
    data class Paragraph(val text: String) : LicenseBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : LicenseBlock
}

/** Parses the small Markdown subset used by our notices; unknown syntax stays as text. */
internal fun licenseBlocks(source: String, markdown: Boolean): List<LicenseBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<LicenseBlock>()
    var index = 0
    fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|")
        .split('|').map { it.trim() }
    fun tableStart(at: Int): Boolean = markdown && at + 1 < lines.size &&
        lines[at].trim().startsWith('|') &&
        cells(lines[at + 1]).all { it.matches(Regex(":?-{3,}:?")) }
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) { index++; continue }
        if (markdown && line.startsWith("# ")) {
            blocks += LicenseBlock.Heading(line.removePrefix("# "))
            index++
        } else if (tableStart(index)) {
            val headers = cells(line)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && lines[index].trim().startsWith('|')) {
                val row = cells(lines[index])
                // Never discard a malformed row or cell when a document changes.
                if (row.size != headers.size) break
                rows += row
                index++
            }
            blocks += LicenseBlock.Table(headers, rows)
        } else {
            val paragraph = mutableListOf(line)
            index++
            while (index < lines.size && lines[index].isNotBlank() &&
                !(markdown && lines[index].startsWith("# ")) && !tableStart(index)) {
                paragraph += lines[index++]
            }
            blocks += LicenseBlock.Paragraph(paragraph.joinToString("\n"))
        }
    }
    return blocks
}

internal fun licenseInlineText(text: String): String = text.replace(Regex("`([^`]+)`"), "$1")

/** Bare URLs in both Markdown code spans and the verbatim font license become links. */
internal val licenseUrlPattern = Regex("https?://[^\\s<>`]+")
