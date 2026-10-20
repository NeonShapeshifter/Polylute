package com.lostf1sh.pixelplayeross.data.media

/**
 * Every cache key a piece of artwork can be stored under, in one place.
 *
 * Coil caches a decoded bitmap per *requested size*, so one cover occupies as many memory-cache
 * entries as there are sizes any surface has ever asked for it in. Invalidating artwork therefore
 * means knowing every size the app requests — and that set was previously written out by hand at
 * the invalidation site, where it had fallen behind the call sites.
 *
 * It was missing `2048x2048`, which is the default `OptimizedAlbumArt` target and the size Home's
 * mix cells were asking for, so exactly the largest and most expensive entry was the one every
 * invalidation left behind. It was also missing `96x96`, which is the compact-list target. An
 * edited cover could therefore keep showing its old picture in the places that mattered most.
 *
 * The tokens are derived from the sizes the app actually requests; `ArtworkCacheKeysTest` reads the
 * call sites and fails if a size is used that is not listed here, so this cannot silently drift
 * again.
 */
internal object ArtworkCacheKeys {

    /**
     * The `<w>x<h>` suffixes of every size the app requests artwork at.
     *
     * Sources: `SafeOriginalAlbumArtSize` (2048), the `AlbumArtQuality` options (256/512/800),
     * `MAX_CACHED_ART_DIMENSION_PX` (1536, the largest an extracted cover can actually be), the
     * Library grid (256), the compact list (96), and the row/header sizes used by the sheets,
     * pickers and the external-player overlay.
     */
    val SIZE_TOKENS: List<String> = listOf(
        "96x96",
        "120x120",
        "128x128",
        "144x144",
        "150x150",
        "160x160",
        "168x168",
        "180x180",
        "192x192",
        "256x256",
        "300x300",
        "512x512",
        "600x600",
        "800x800",
        "1536x1536",
        "2048x2048",
    )

    /** Keys may include a URI timestamp, a size suffix and a persistent generation. */
    fun belongsToSong(key: String, songId: Long): Boolean {
        if (!com.lostf1sh.pixelplayeross.utils.LocalArtworkUri.isLocalArtworkUri(key)) return false
        return key.substringAfter("://song/", "")
            .substringBefore('?').substringBefore('_').toLongOrNull() == songId
    }

    /**
     * Every key [baseUri]'s artwork may be cached under.
     *
     * The bare URI is included because that is the key for an unbounded request; the rest are the
     * size-suffixed variants, and [screenSidesPx] adds the device-dependent screen-size ones.
     */
    fun keysFor(baseUri: String, screenSidesPx: Collection<Int> = emptyList()): List<String> = buildList {
        add(baseUri)
        SIZE_TOKENS.forEach { add("${baseUri}_$it") }
        // Covers decoded "at the screen" (AUD-113) are cached at a size that depends on the device, so
        // it cannot be in the fixed list: the caller passes this device's sides.
        screenSidesPx.filter { it > 0 }.distinct().map { "${it}x$it" }
            .filter { it !in SIZE_TOKENS }
            .forEach { add("${baseUri}_$it") }
    }
}
