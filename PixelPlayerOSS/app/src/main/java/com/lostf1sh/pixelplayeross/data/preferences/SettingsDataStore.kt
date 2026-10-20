package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import timber.log.Timber

/**
 * Recovery policy for the shared `settings` DataStore (AUD-032).
 *
 * Without a handler a corrupt file threw `CorruptionException` into the eager `stateIn(appScope)`
 * collectors — an uncaught crash — and in Dedicated Mode the app that crashes is HOME, which can
 * loop until recovered over adb. The file is replaced with empty preferences instead: settings reset
 * to defaults, the device stays usable.
 *
 * What a reset loses is real — Safety Max calibrations and headphone/DAC profiles live in this same
 * store — so it is logged loudly. Moving that safety-critical state to its own store with a backup
 * is the fuller fix the audit recommends, and is not done here.
 */
object SettingsDataStore {
    const val NAME = "settings"

    val corruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
        ReplaceFileCorruptionHandler { error ->
            Timber.tag("SettingsDataStore").e(error, "Settings DataStore corrupt; resetting to defaults")
            emptyPreferences()
        }
}
