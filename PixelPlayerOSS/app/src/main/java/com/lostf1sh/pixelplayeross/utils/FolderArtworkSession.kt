package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/** A sync owns one session. Candidate discovery, stat, bytes and hashing are shared by its songs. */
internal class FolderArtworkSession(
    context: Context,
    /** B59 review: where the animated covers are read from; null = as the platform allows (the tests pass one that counts its listings). */
    animatedCoverDirectory: com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverDirectory? = null,
) {
    private val appContext = context.applicationContext ?: context
    private val preferences = context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE)
    private val folders = mutableMapOf<String, Source?>()

    /**
     * B61: true when a question to the animated-cover folder **failed** (a provider that errored; not "outside the chosen folder", which is a real answer). The folder step does not remember
     * "nothing to install" for a folder whose answer could not be had ([FolderArtworkDecisions]): the next sync asks again.
     */
    @Volatile
    var listingFailed = false
        private set

    /** B61: how many times the animated-cover folder was really asked for a listing in this session (the sync's log line adds them up). */
    @Volatile
    var listingsAsked = 0
        private set

    /** B57-fix1 4: the animated covers are read as the platform lets this app read them: the disk before Android 13, the folder the listener chose after it. */
    private val animatedCovers by lazy {
        OncePerFolder(animatedCoverDirectory ?: com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverFolder.directoryFor(appContext), { listingsAsked++ }) { listingFailed = true }
    }

    /** B61: where the animated covers are read from, as text (the disk, none, or the chosen folder): part of what a remembered "nothing to install" depends on. */
    val animatedIdentity: String get() = animatedCovers.identity()

    /**
     * B60 (item 3): the names in a folder are asked of the chosen animated-cover folder **once per folder for the whole session**, whatever asks (the folder's still cover, disc 1, disc 2…): on Android 13+
     * each question is a `DocumentsContract` query of ~100 ms on the HONOR, and B59's review fix still asked it once per (folder, disc). A failed listing (null) is kept too: a session is one folder of
     * one sync, and the next sync asks again.
     */
    private class OncePerFolder(
        private val inner: com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverDirectory,
        private val onListing: () -> Unit,
        private val onFailedListing: () -> Unit,
    ) : com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverDirectory by inner {
        private val asked = HashMap<String, List<String>?>()
        override fun names(dir: File): List<String>? {
            val key = dir.path
            if (!asked.containsKey(key)) {
                onListing()
                val answer = inner.names(dir)
                if (answer == null && !inner.isUnreachable(dir)) onFailedListing()
                asked[key] = answer
            }
            return asked[key]
        }
    }

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

    private val listings = mutableMapOf<String, List<String>>()
    private val discFolders = mutableMapOf<Pair<String, Int>, Source?>()

    /**
     * B59 4 (#82): the picture of **disc [disc]** in this song's folder (`disc2.jpg` / `.jpeg` / `.png`, see [DiscCoverNames]), or, when the folder has none but has that disc's own
     * animation (`disc2.mp4` / `.webp` / `.gif`), its first frame, once, as the still one (as a folder with only `cover.mp4` gives its frame). Null when the disc has no file of its own:
     * the album's folder cover rules apply to its songs then. The folder is listed once per session ([OncePerFolder]), the answer is kept per (folder, disc).
     */
    fun discSource(songPath: String, disc: Int): Source? {
        if (disc <= 0) return null
        val directory = File(songPath).parent.orEmpty()
        // B59 review: not getOrPut — it runs its block again for a stored null, and "this disc has no file" is the usual answer: every song of a folder without discN.* listed the chosen
        // animated-cover folder again (a SAF query on Android 13+), 4,966 listings and ~122 s in every "Update library" on the HONOR instead of one per folder
        val key = directory to disc
        if (discFolders.containsKey(key)) return discFolders[key]
        val dir = File(directory)
        val source = if (!AlbumArtUtils.shouldTrustDirectoryArtwork(dir.name)) {
            null
        } else {
            val names = listings.getOrPut(directory) { dir.list()?.toList().orEmpty() }
            val still = DiscCoverNames.pickStill(names, disc)?.let { File(dir, it) }?.takeIf { it.isFile && it.length() > 1024 }
            val file = still ?: com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverFrames.frameFor(appContext, songPath, animatedCovers, disc, discOnly = true)
            file?.let { Source(it, preferences) }
        }
        discFolders[key] = source
        return source
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
