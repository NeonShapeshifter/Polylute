package com.lostf1sh.pixelplayeross.utils

import android.net.Uri

object LocalArtworkUri {
    const val SCHEME = "pixelplayer_local_art"
    private val LEGACY_SCHEME = charArrayOf(
        'p', 'i', 'x', 'e', 'l', 'p', 'l', 'a', 'y', '_',
        'l', 'o', 'c', 'a', 'l', '_', 'a', 'r', 't'
    ).concatToString()
    private const val HOST_SONG = "song"
    private const val CACHE_BUST_QUERY = "t"
    private const val SOURCE_SONG_QUERY = "src"
    private const val ALBUM_COVER_KEY_BASE = 1L shl 40

    fun buildSongUri(songId: Long): String = "$SCHEME://$HOST_SONG/$songId"
    fun buildSongUriWithTimestamp(songId: Long): String = buildSongUri(songId) + "?t=${System.currentTimeMillis()}"

    /**
     * B60 (#82 follow-up): **an album's own cover** has its own entry in the per-song artwork cache, under a reserved negative key (no song has one), so it can never hold a disc's picture
     * (a disc cover is installed into the cache of its songs). The URI is a song URI of that key: every consumer of local artwork already reads it. `src` (optional) names the song whose
     * embedded picture is the album's cover when the cache has none yet (the fetcher extracts it then, once; a sync never does).
     */
    fun albumCoverKey(albumId: Long): Long = -ALBUM_COVER_KEY_BASE - Math.floorMod(albumId, ALBUM_COVER_KEY_BASE)

    fun isAlbumCoverKey(songId: Long): Boolean = songId <= -ALBUM_COVER_KEY_BASE

    /**
     * The album id an album-cover key stands for, **only when the album id was below 2^40** (the key keeps its low 40 bits); null when [key] is not an album-cover key. MediaStore album ids on
     * Android 10+ are 19-digit hashes, for which this is NOT the album id (B61): to find the album of a key, work forward from the albums ([AlbumArtUtils.recordedAlbumCoverKeys]).
     */
    fun albumIdOfCoverKey(key: Long): Long? = if (isAlbumCoverKey(key)) -key - ALBUM_COVER_KEY_BASE else null

    /** [stamp] changes when the source changes (the digest of `cover.jpg`, or the song id): the same cover always has the same address, so a sync that changes nothing writes nothing. */
    fun buildAlbumCoverUri(albumId: Long, stamp: String, sourceSongId: Long? = null): String =
        buildSongUri(albumCoverKey(albumId)) + "?$CACHE_BUST_QUERY=$stamp" + (sourceSongId?.let { "&$SOURCE_SONG_QUERY=$it" } ?: "")

    /** The song whose embedded picture an album-cover address stands for, or null (a cover file's address, or not an album cover at all). */
    fun parseAlbumCoverSource(uriString: String?): Long? {
        if (uriString.isNullOrBlank()) return null
        val songId = parseSongId(uriString) ?: return null
        if (!isAlbumCoverKey(songId)) return null
        return uriString.substringAfter('?', "").split('&')
            .firstNotNullOfOrNull { entry -> entry.takeIf { it.startsWith("$SOURCE_SONG_QUERY=") }?.substringAfter('=')?.toLongOrNull() }
    }

    fun isLocalArtworkUri(uriString: String?): Boolean {
        return uriString?.let { it.startsWith("$SCHEME://") || it.startsWith("$LEGACY_SCHEME://") } == true
    }

    fun isLocalArtworkUri(uri: Uri?): Boolean {
        return uri?.toString()?.let(::isLocalArtworkUri) == true
    }

    fun parseSongId(uriString: String): Long? {
        if (!isLocalArtworkUri(uriString)) return null
        val scheme = if (uriString.startsWith("$SCHEME://")) SCHEME else LEGACY_SCHEME
        val prefix = "$scheme://$HOST_SONG/"
        return uriString.removePrefix(prefix)
            .substringBefore('?')
            .toLongOrNull()
    }

    fun looksLikeVolatileArtworkUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val normalized = uriString.lowercase()
        val isLegacyCachedFileUri = normalized.contains("song_art_") &&
            (
                normalized.startsWith("content://") ||
                    normalized.startsWith("file://") ||
                    normalized.startsWith("/") ||
                    normalized.contains(".provider/")
                )
        val isSharedArtworkUri = normalized.startsWith("content://") &&
            normalized.contains(".artwork/song/")
        return isLegacyCachedFileUri || isSharedArtworkUri
    }

    fun parseSongIdFromVolatileArtworkUri(uriString: String?): Long? {
        if (uriString.isNullOrBlank()) return null
        if (!looksLikeVolatileArtworkUri(uriString)) return null

        val normalized = uriString.lowercase()
        if (normalized.startsWith("content://") && normalized.contains(".artwork/song/")) {
            return normalized
                .substringAfter(".artwork/song/")
                .substringBefore('?')
                .substringBefore('/')
                .toLongOrNull()
        }

        val fileName = uriString.substringAfterLast('/').substringBefore('?')
        if (!fileName.startsWith("song_art_")) {
            return null
        }

        return fileName
            .removePrefix("song_art_")
            .substringBefore('_')
            .substringBefore('.')
            .toLongOrNull()
    }

    fun extractCacheBustToken(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val query = uriString.substringAfter('?', "")
        if (query.isBlank()) return null
        return query
            .split('&')
            .asSequence()
            .mapNotNull { entry ->
                val separatorIndex = entry.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                val key = entry.substring(0, separatorIndex)
                if (key != CACHE_BUST_QUERY) return@mapNotNull null
                entry.substring(separatorIndex + 1).takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    fun isLikelyLocalMedia(contentUriString: String): Boolean {
        val normalized = contentUriString.lowercase()
        return !normalized.startsWith("navidrome://") &&
            !normalized.startsWith("jellyfin://")
    }

    fun resolveSongArtworkUri(
        storedUri: String?,
        songId: Long,
        contentUriString: String
    ): String? {
        val normalizedStoredUri = storedUri?.takeIf { it.isNotBlank() } ?: return null
        if (!isLikelyLocalMedia(contentUriString)) {
            return normalizedStoredUri
        }

        return when {
            isLocalArtworkUri(normalizedStoredUri) -> {
                if (normalizedStoredUri.contains("?t=")) {
                    normalizedStoredUri
                } else {
                    buildSongUri(songId)
                }
            }
            looksLikeVolatileArtworkUri(normalizedStoredUri) -> buildSongUri(songId)
            else -> normalizedStoredUri
        }
    }
}
