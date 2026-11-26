package com.lostf1sh.pixelplayeross.data.historyimport

import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import java.io.ByteArrayOutputStream

/**
 * Shared path normalization and catalog path lookup for all importers (history, likes CSV, playlists CSV, M3U/M3U8).
 *
 * Strips `file://` and `file:` prefixes and percent-decodes the remainder as UTF-8 (keeping `+` literal),
 * and converts `\` to `/`. `content://` URIs and relative paths are left untouched without guessing.
 */
internal object ImportPathNormalizer {

    fun normalize(raw: String): String {
        val decoded = when {
            raw.startsWith("file://", ignoreCase = true) -> percentDecodeUtf8(raw.substring(7))
            raw.startsWith("file:", ignoreCase = true) -> percentDecodeUtf8(raw.substring(5))
            else -> raw
        }
        return decoded.replace('\\', '/')
    }

    private fun percentDecodeUtf8(input: String): String {
        if ('%' !in input) return input
        val out = StringBuilder(input.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            if (input[i] == '%' && i + 2 < input.length) {
                val hi = hexDigit(input[i + 1])
                val lo = hexDigit(input[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            if (bytes.size() > 0) {
                out.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.reset()
            }
            out.append(input[i])
            i++
        }
        if (bytes.size() > 0) {
            out.append(bytes.toByteArray().toString(Charsets.UTF_8))
        }
        return out.toString()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/** Exact-first, normalized-second catalog path index shared across importers. */
internal class CatalogPathIndex private constructor(
    private val byExactPath: Map<String, CatalogItem>,
    private val byNormalizedPath: Map<String, CatalogItem>,
) {
    fun find(rawPath: String?): CatalogItem? {
        if (rawPath.isNullOrBlank()) return null
        return byExactPath[rawPath] ?: byNormalizedPath[ImportPathNormalizer.normalize(rawPath)]
    }

    companion object {
        fun from(catalog: LibraryCatalog): CatalogPathIndex {
            val byExact = HashMap<String, CatalogItem>(catalog.items.size * 2)
            val byNormalized = HashMap<String, CatalogItem>(catalog.items.size * 2)
            for (item in catalog.items) {
                val p = item.song.path
                if (p.isNotBlank()) {
                    byExact.putIfAbsent(p, item)
                    byNormalized.putIfAbsent(ImportPathNormalizer.normalize(p), item)
                }
            }
            return CatalogPathIndex(byExact, byNormalized)
        }
    }
}
