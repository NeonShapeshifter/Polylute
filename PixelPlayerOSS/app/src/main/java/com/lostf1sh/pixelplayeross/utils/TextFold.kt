package com.lostf1sh.pixelplayeross.utils

import java.text.Normalizer
import java.util.Locale

/**
 * Shared accent- and case-insensitive text folding utility (NFD, remove combining marks `\p{Mn}`,
 * `lowercase(Locale.ROOT)`).
 */
object TextFold {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Folds [text] for search and comparison: NFD decomposition, removal of combining marks
     * (`\p{Mn}`), `lowercase(Locale.ROOT)`, and collapsed whitespace.
     */
    fun fold(text: String): String {
        if (text.isEmpty()) return ""
        if (isPlainAscii(text)) {
            return foldAscii(text)
        }
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        val stripped = StringBuilder(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val cp = Character.codePointAt(nfd, i)
            val step = Character.charCount(cp)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                stripped.appendCodePoint(cp)
            }
            i += step
        }
        return collapseWhitespace(stripped.toString().lowercase(Locale.ROOT))
    }

    /**
     * Folds [text] via [fold] and replaces non-alphanumeric runs with single spaces
     * (used by history import track matching and deduplication keys).
     */
    fun foldWords(text: String): String {
        val folded = fold(text)
        if (folded.isEmpty()) return ""
        return NON_WORD.replace(folded, " ").trim()
    }

    private fun isPlainAscii(text: String): Boolean {
        for (i in 0 until text.length) {
            if (text[i].code >= 128) return false
        }
        return true
    }

    private fun isAsciiWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000B' || ch == '\u000C'

    private fun foldAscii(text: String): String {
        val len = text.length
        val sb = StringBuilder(len)
        var inSpace = true
        for (i in 0 until len) {
            val ch = text[i]
            if (isAsciiWhitespace(ch)) {
                if (!inSpace) {
                    sb.append(' ')
                    inSpace = true
                }
            } else {
                sb.append(if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch)
                inSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private fun collapseWhitespace(text: String): String {
        val len = text.length
        val sb = StringBuilder(len)
        var inSpace = true
        var i = 0
        while (i < len) {
            val cp = Character.codePointAt(text, i)
            val step = Character.charCount(cp)
            if (Character.isWhitespace(cp)) {
                if (!inSpace) {
                    sb.append(' ')
                    inSpace = true
                }
            } else {
                sb.appendCodePoint(cp)
                inSpace = false
            }
            i += step
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }
}
