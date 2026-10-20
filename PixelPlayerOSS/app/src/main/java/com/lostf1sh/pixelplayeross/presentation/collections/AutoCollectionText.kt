package com.lostf1sh.pixelplayeross.presentation.collections

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollection
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionEngine
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.collections.auto.EmptyReason
import com.lostf1sh.pixelplayeross.presentation.stats.StatsFormat
import com.lostf1sh.pixelplayeross.utils.currentLocale
import java.time.ZoneId

/** What each automatic collection is called. */
@StringRes
internal fun AutoCollectionId.nameRes(): Int = when (this) {
    AutoCollectionId.DAILY_MIX -> R.string.collection_name_daily_mix
    AutoCollectionId.ON_REPEAT -> R.string.collection_name_on_repeat
    AutoCollectionId.REDISCOVER -> R.string.collection_name_rediscover
    AutoCollectionId.FORGOTTEN_FAVORITES -> R.string.collection_name_forgotten_favorites
    AutoCollectionId.DEEP_CUTS -> R.string.collection_name_deep_cuts
    AutoCollectionId.NEW_UNEXPLORED -> R.string.collection_name_new_unexplored
    AutoCollectionId.NIGHT -> R.string.collection_name_night
    AutoCollectionId.HIGH_RESOLUTION -> R.string.collection_name_high_resolution
    AutoCollectionId.NEVER_PLAYED -> R.string.collection_name_never_played
    AutoCollectionId.A_YEAR_AGO -> R.string.collection_name_a_year_ago
}

@Composable
internal fun AutoCollectionId.nameText(): String = stringResource(nameRes())

/** The one line that says why these songs: the rule, with its numbers. */
@Composable
internal fun AutoCollection.reasonText(): String = when (id) {
    AutoCollectionId.DAILY_MIX -> stringResource(R.string.collection_reason_daily_mix)
    AutoCollectionId.ON_REPEAT -> stringResource(R.string.collection_reason_on_repeat, days)
    AutoCollectionId.REDISCOVER -> stringResource(R.string.collection_reason_rediscover, days)
    AutoCollectionId.FORGOTTEN_FAVORITES -> stringResource(R.string.collection_reason_forgotten_favorites, days)
    AutoCollectionId.DEEP_CUTS -> stringResource(R.string.collection_reason_deep_cuts)
    AutoCollectionId.NEW_UNEXPLORED -> stringResource(R.string.collection_reason_new_unexplored, days)
    AutoCollectionId.NIGHT -> stringResource(R.string.collection_reason_night)
    AutoCollectionId.HIGH_RESOLUTION -> stringResource(R.string.collection_reason_high_resolution)
    AutoCollectionId.NEVER_PLAYED -> stringResource(R.string.collection_reason_never_played)
    AutoCollectionId.A_YEAR_AGO -> stringResource(R.string.collection_reason_a_year_ago)
}

/** What an empty collection says: the truth about why, never a bare "nothing here". */
@Composable
internal fun AutoCollection.emptyText(): String {
    val zone = ZoneId.systemDefault()
    return when (emptyReason ?: return "") {
        EmptyReason.NO_HISTORY -> stringResource(R.string.collection_empty_no_history)
        EmptyReason.TOO_LITTLE_HISTORY -> stringResource(R.string.collection_empty_too_little_history, needDays, haveDays)
        EmptyReason.NO_FAVORITES -> stringResource(R.string.collection_empty_no_favorites)
        EmptyReason.EVERYTHING_PLAYED -> stringResource(R.string.collection_empty_everything_played)
        EmptyReason.EMPTY_LIBRARY -> stringResource(R.string.collection_empty_library)
        EmptyReason.NO_RESOLUTION_DATA -> sinceMs
            ?.let { stringResource(R.string.collection_empty_no_resolution_since, StatsFormat.date(it, zone, currentLocale())) }
            ?: stringResource(R.string.collection_empty_no_resolution)
        EmptyReason.NOTHING_MATCHES -> when (id) {
            AutoCollectionId.DAILY_MIX -> stringResource(R.string.collection_empty_nothing_daily_mix)
            AutoCollectionId.ON_REPEAT -> stringResource(R.string.collection_empty_nothing_on_repeat, days, AutoCollectionEngine.ON_REPEAT_MIN_PLAYS)
            AutoCollectionId.REDISCOVER -> stringResource(R.string.collection_empty_nothing_rediscover, days)
            AutoCollectionId.FORGOTTEN_FAVORITES -> stringResource(R.string.collection_empty_nothing_forgotten_favorites, days)
            AutoCollectionId.DEEP_CUTS -> stringResource(R.string.collection_empty_nothing_deep_cuts, days)
            AutoCollectionId.NEW_UNEXPLORED -> stringResource(R.string.collection_empty_nothing_new_unexplored, days)
            AutoCollectionId.NIGHT -> stringResource(R.string.collection_empty_nothing_night)
            AutoCollectionId.HIGH_RESOLUTION -> stringResource(R.string.collection_empty_nothing_high_resolution)
            AutoCollectionId.NEVER_PLAYED -> stringResource(R.string.collection_empty_everything_played)
            AutoCollectionId.A_YEAR_AGO -> stringResource(R.string.collection_empty_nothing_a_year_ago)
        }
    }
}

@PluralsRes
internal fun songCountRes(): Int = R.plurals.song_count

@Composable
internal fun songCountText(count: Int): String = pluralStringResource(songCountRes(), count, count)
