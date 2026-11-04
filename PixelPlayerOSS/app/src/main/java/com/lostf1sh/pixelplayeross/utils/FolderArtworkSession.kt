package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/** A sync owns one session. Candidate discovery, stat, bytes and hashing are shared by its songs. */
internal class FolderArtworkSession(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val preferences = context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE)
    private val folders = mutableMapOf<String, Source?>()

    /** B57-fix1 4: the animated covers are read as the platform lets this app read them: the disk before Android 13, the folder the listener chose after it. */
    private val animatedCovers by lazy { com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverFolder.directoryFor(appContext) }

    fun source(songPath: String): Source? {
        val directory = File(songPath).parent.orEmpty()
        if (!folders.containsKey(directory)) {
            // B56-7.2: a folder with only an animated cover (cover.mp4 / .webp / .gif) gives its first frame, once, as the still one
            val file = AlbumArtUtils.findExternalAlbumArtFile(songPath)
                ?: com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverFrames.frameFor(appContext, songPath, animatedCovers)
            folders[directory] = file?.let { Source(it, preferences) }
        }
        return folders[directory]
    }

    internal class Source(val file: File, private val preferences: SharedPreferences) {
        private val cheapSignature = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        private val key = "folder_signature_${file.parent}"
        private val digestKey = "folder_digest_${file.parent}"
        private var loadedBytes: ByteArray? = null
        private var loadedDigest: String? = null

        // A stable source needs no bytes until a song has lost its cached copy.
        val digest: String get() {
            loadedDigest?.let { return it }
            if (preferences.getString(key, null) == cheapSignature) {
                preferences.getString(digestKey, null)?.let { loadedDigest = it; return it }
            }
            return load().second
        }

        val bytes: ByteArray get() = loadedBytes ?: load().first

        @android.annotation.SuppressLint("UseKtx") // A failed signature write must be retried on the next sync.
        private fun load(): Pair<ByteArray, String> {
            val bytes = file.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            check(preferences.edit().putString(key, cheapSignature).putString(digestKey, digest).commit()) {
                "Could not record folder artwork signature for ${file.parent}"
            }
            loadedBytes = bytes
            loadedDigest = digest
            return bytes to digest
        }
    }
}
