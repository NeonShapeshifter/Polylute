package com.lostf1sh.pixelplayeross.data.network

import com.lostf1sh.pixelplayeross.BuildConfig

/**
 * B37 (C3): what every request the app makes calls itself.
 *
 * The clients used to say `PixelPlayerOSS/1.0` (twice, with a version that was never the app's) and, for MusicBrainz, the upstream project's
 * address. One place now; `HttpUserAgentTest` reads what each client sends.
 */
object HttpUserAgent {
    /** This project's address: MusicBrainz asks the client's author to be reachable, so it is the one request that carries it. */
    const val PROJECT_URL = "https://github.com/NeonShapeshifter/Polylute"

    /** `Polylute/<versionName> (Android; Music Player)`: LRCLIB, Deezer and the image loader. */
    const val DEFAULT = "Polylute/${BuildConfig.VERSION_NAME} (Android; Music Player)"

    /** `Polylute/<versionName> ( <project address> )`: MusicBrainz. */
    const val MUSICBRAINZ = "Polylute/${BuildConfig.VERSION_NAME} ( $PROJECT_URL )"
}
