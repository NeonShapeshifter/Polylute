package com.lostf1sh.pixelplayeross.data.media

import java.io.File

/**
 * B57-fix1 5.1. What the edit sheet asks **before** the listener types: can the editor write this file's tags safely?
 *
 * The answer is "no" only for a file the editor cannot read with certainty (today: a WAV that is streamed, truncated or not a classic RIFF file). For every
 * other file the editor writes through a writer it can verify, so the answer is "yes" and a failure after Save stays a real failure, never an expected one.
 * Cheap (a few reads of the chunk headers): call it off the main thread.
 */
object MetadataEditSupport {
    fun canEdit(path: String?): Boolean {
        if (path.isNullOrBlank()) return true
        val file = File(path)
        if (!file.isFile) return true // the editor reports a missing file itself
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(12)
                val read = input.read(head)
                val isWave = read == 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE"
                !isWave || WavTagWriter.canEdit(file)
            }
        } catch (e: java.io.IOException) {
            true
        }
    }
}
