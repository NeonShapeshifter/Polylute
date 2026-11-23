package com.lostf1sh.pixelplayeross.data.service

import android.content.pm.PackageManager
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

/**
 * B69: who may browse the library and resolve library ids through the media session (owner, 2026-11-22):
 * Android Auto, the car, the system and Polylute. Nothing else. Owner 11-23: plus the Google app (the Assistant's
 * "play X on Polylute" on the phone), verified by its certificate like Android Auto.
 *
 * Every other app may still connect and control playback (play, pause, skip, seek, the current song and its
 * picture): watches, Tasker, headsets and KDE Connect keep working. It gets no library content.
 *
 * Pure: the caller's facts and the signature check are given; [MusicService] supplies them.
 */
internal object MediaBrowseCallerPolicy {

    enum class Caller(val mayBrowse: Boolean, val isAutoHost: Boolean) {
        /** Polylute itself: its own package **and** its own uid (the app's controllers, the media notification). */
        SELF(mayBrowse = true, isAutoHost = false),

        /**
         * A controller Android marks as trusted (`ControllerInfo.isTrusted`): it holds MEDIA_CONTENT_CONTROL (System UI's media controls
         * and playback resumption, Bluetooth, the platform) or has notification access, which the listener granted it by hand.
         */
        TRUSTED_SYSTEM(mayBrowse = true, isAutoHost = false),

        /** Android Auto on the phone (it also serves the DHU), verified by its signing certificate, never by its package name alone. */
        ANDROID_AUTO(mayBrowse = true, isAutoHost = true),

        /** The media center of a car that runs Android itself (AAOS), as a trusted system controller. */
        CAR_MEDIA_CENTER(mayBrowse = true, isAutoHost = true),

        /** The Google app (Google Assistant's voice requests on the phone), verified by its signing certificate. Not a car: no car commands. */
        GOOGLE_ASSISTANT(mayBrowse = true, isAutoHost = false),

        /** Anyone else: playback control only. */
        OTHER(mayBrowse = false, isAutoHost = false),
    }

    const val GEARHEAD = "com.google.android.projection.gearhead"
    const val CAR_MEDIA = "com.android.car.media"
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /**
     * SHA-256 digests of Android Auto's release signing certificates: the "Android Auto prod release key" and the "Android Auto rotated
     * prod release key". Source: Google's Universal Android Music Player sample, `common/src/main/res/xml/allowed_media_browser_callers.xml`
     * (https://github.com/android/uamp, last change to that file 2022-12-12, commit f5bfb5ca). UAMP's two `release="false"` keys (Google's
     * development keys) are left out on purpose.
     */
    val GEARHEAD_RELEASE_SHA256: List<String> = listOf(
        "fd:b0:0c:43:db:de:8b:51:cb:31:2a:a8:1d:3b:5f:a1:77:13:ad:b9:4b:28:f5:98:d7:7f:8e:b8:9d:ac:ee:df",
        "1c:a8:dc:c0:be:d3:cb:d8:72:d2:cb:79:12:00:c0:29:2c:a9:97:57:68:a8:2d:67:6b:8b:42:4f:b6:5b:52:95",
    )

    val GEARHEAD_RELEASE_DIGESTS: List<ByteArray> = GEARHEAD_RELEASE_SHA256.map(::digestBytes)

    /** SHA-256 digest of the Google app's release signing certificate: the same UAMP file, entry "Google"; its `release="false"` key is left out. */
    val GOOGLE_APP_RELEASE_SHA256: List<String> = listOf(
        "f0:fd:6c:5b:41:0f:25:cb:25:c3:b5:33:46:c8:97:2f:ae:30:f8:ee:74:11:df:91:04:80:ad:6b:2d:60:db:83",
    )

    val GOOGLE_APP_RELEASE_DIGESTS: List<ByteArray> = GOOGLE_APP_RELEASE_SHA256.map(::digestBytes)

    /** The packages checked by certificate, and their release digests. Any other package is never genuine. */
    private val RELEASE_DIGESTS: Map<String, List<ByteArray>> = mapOf(GEARHEAD to GEARHEAD_RELEASE_DIGESTS, GOOGLE_APP to GOOGLE_APP_RELEASE_DIGESTS)

    fun digestBytes(hex: String): ByteArray = hex.split(':').map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Whether the app running as [uid] is [packageName] and is signed with one of that package's release digests
     * ([GEARHEAD_RELEASE_DIGESTS], [GOOGLE_APP_RELEASE_DIGESTS]).
     * [signedBy] answers "is the app of this uid signed with this SHA-256 certificate digest"; the platform's
     * `PackageManager.hasSigningCertificate(uid, digest, CERT_INPUT_SHA256)` follows key rotation (the signing lineage).
     */
    fun interface SignatureCheck {
        fun isGenuine(packageName: String, uid: Int): Boolean
    }

    /** The production check. Both questions are about the caller's uid, so a sideloaded app that takes the package name fails both. */
    fun packageManagerCheck(packageManager: PackageManager): SignatureCheck = SignatureCheck { packageName, uid ->
        val digests = RELEASE_DIGESTS[packageName] ?: return@SignatureCheck false
        runCatching {
            packageManager.getPackagesForUid(uid)?.contains(packageName) == true &&
                digests.any { packageManager.hasSigningCertificate(uid, it, PackageManager.CERT_INPUT_SHA256) }
        }.getOrDefault(false)
    }

    fun classify(
        packageName: String,
        uid: Int,
        trusted: Boolean,
        appPackage: String,
        appUid: Int,
        signatures: SignatureCheck,
    ): Caller = when {
        packageName == appPackage && uid == appUid -> Caller.SELF
        packageName == GEARHEAD && signatures.isGenuine(packageName, uid) -> Caller.ANDROID_AUTO
        packageName == CAR_MEDIA && trusted -> Caller.CAR_MEDIA_CENTER
        packageName == GOOGLE_APP && signatures.isGenuine(packageName, uid) -> Caller.GOOGLE_ASSISTANT
        trusted -> Caller.TRUSTED_SYSTEM
        else -> Caller.OTHER
    }

    /** The library's session commands. */
    val LIBRARY_COMMAND_CODES: Set<Int> = setOf(
        SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
        SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE,
        SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE,
        SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN,
        SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM,
        SessionCommand.COMMAND_CODE_LIBRARY_SEARCH,
        SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT,
    )

    /**
     * The session commands a caller is given, from what the session would give anyone ([base]).
     *
     * A caller that may not browse loses every library command, so a refusal is the session's own answer, with one exception: a legacy
     * `MediaBrowserCompat` keeps `GET_LIBRARY_ROOT`. Media3 answers its `onGetRoot` with null when that command is missing **or** when the
     * root is an error, and a null root fails the whole connection: the app would lose the session token and could not even pause. It gets
     * [EMPTY_ROOT_ID] instead, a root with nothing under it (`GET_CHILDREN` and the rest stay withheld).
     */
    fun sessionCommands(base: SessionCommands, caller: Caller, legacyBrowser: Boolean): SessionCommands {
        if (caller.mayBrowse) return base
        val builder = base.buildUpon()
        LIBRARY_COMMAND_CODES
            .filterNot { legacyBrowser && it == SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT }
            .forEach { builder.remove(it) }
        return builder.build()
    }

    /** The root a legacy browser that may not browse is given so that it can connect: not browsable, not playable, no children. */
    const val EMPTY_ROOT_ID = "polylute_no_library"
}
