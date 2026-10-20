package com.lostf1sh.pixelplayeross.data.startup

import android.content.Context
import timber.log.Timber
import java.io.File
import java.security.KeyStore

/**
 * One-time purge of what the removed server-music architecture left on disk.
 *
 * Deleting the Navidrome / Jellyfin / ListenBrainz repositories also deleted the only code
 * that knew about their credential stores, so nothing would ever read or clear those files
 * again. Leaving them behind would keep a server URL, a username and a password sitting in
 * the app's private storage for the life of the install, and would let them travel through
 * cloud backup and device transfer. The downloaded-copy cache has the same problem from the
 * other side: its tracking table is gone, so those bytes can never be shown, played or
 * cleaned up through the UI again.
 *
 * All of it lives in app-private storage — never in the user's music folders — and the
 * paths below are the literal names the deleted code used, so this is scoped to exactly the
 * data that belonged to the removed feature. It is idempotent and safe to run on every
 * start: every step is a no-op once the files are gone.
 */
object LegacyCloudDataCleanup {

    /** SharedPreferences files holding server credentials. `_plain` variants were the unencrypted fallbacks. */
    private val LEGACY_CREDENTIAL_PREFS = listOf(
        "navidrome_prefs.xml",
        "navidrome_prefs_plain.xml",
        "jellyfin_prefs.xml",
        "jellyfin_prefs_plain.xml",
        "listenbrainz_prefs.xml",
        "listenbrainz_prefs_plain.xml",
    )

    /** App-private cache of downloaded remote tracks, tracked by the dropped `offline_tracks` table. */
    private const val LEGACY_DOWNLOAD_DIR = "cloud_downloads"

    /**
     * The default AndroidKeyStore alias `MasterKey.Builder` uses when no explicit alias is set.
     * Nothing in the app builds an EncryptedSharedPreferences any more, so the key that wrapped
     * the credentials above protects nothing once they are deleted.
     */
    private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    fun purge(context: Context) {
        purgeCredentialPrefs(context)
        purgeDownloadedCopies(context)
        purgeMasterKey()
    }

    private fun purgeCredentialPrefs(context: Context) {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        LEGACY_CREDENTIAL_PREFS.forEach { name ->
            val file = File(sharedPrefsDir, name)
            if (!file.exists()) return@forEach
            runCatching { file.delete() }
                .onSuccess { deleted ->
                    if (deleted) Timber.tag(TAG).i("Removed legacy cloud credential store %s", name)
                }
                .onFailure { Timber.tag(TAG).w(it, "Could not remove legacy credential store %s", name)
                }
        }
    }

    private fun purgeDownloadedCopies(context: Context) {
        val downloads = File(context.filesDir, LEGACY_DOWNLOAD_DIR)
        if (!downloads.exists()) return
        val deleted = runCatching { downloads.deleteRecursively() }
            .onFailure { Timber.tag(TAG).w(it, "Could not remove legacy download cache") }
            .getOrDefault(false)
        if (deleted) Timber.tag(TAG).i("Removed legacy cloud download cache")
    }

    private fun purgeMasterKey() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) return
            keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            Timber.tag(TAG).i("Removed legacy credential master key")
        }.onFailure {
            // A missing or locked keystore is not a reason to fail startup; the credentials
            // it protected are already deleted, so the key is inert either way.
            Timber.tag(TAG).w(it, "Could not remove legacy credential master key")
        }
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TAG = "LegacyCloudCleanup"
}
