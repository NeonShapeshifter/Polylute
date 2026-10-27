package com.lostf1sh.pixelplayeross.presentation.screens

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.diagnostics.DetailedLogPolicy
import com.lostf1sh.pixelplayeross.data.diagnostics.DetailedLogState
import kotlinx.coroutines.delay

/** What the section needs besides the state: the four things it can ask for. */
internal interface DetailedLoggingActions {
    fun setOn(on: Boolean)
    fun setIncludeEverything(value: Boolean)
    fun delete()

    /** Opens the share sheet for the log; false when there was nothing to share or the sheet could not be opened. */
    fun share(): Boolean
}

/**
 * The whole minutes left, rounded up: "1 min left" until it is really over. B41: never more than a run is long. The clock the section
 * holds is sampled when it is drawn and every half minute after, so the moment the switch is turned on it can be a few seconds older
 * than the switch; counted from that old reading a fresh 30-minute run was 30 minutes and some seconds, which rounds up to "31".
 */
internal fun detailedLoggingMinutesLeft(untilMs: Long, nowMs: Long): Int =
    ((DetailedLogPolicy.remainingMs(untilMs, nowMs) + 59_999L) / 60_000L).toInt()
        .coerceAtMost((DetailedLogPolicy.DURATION_MS / 60_000L).toInt())

/**
 * B40-F7: Maintenance's "Detailed logging" section: the switch (half an hour, then it stops by itself), the second switch that lets titles
 * and paths in, what is and is not written, and Share / Delete for the file. The state comes from [DetailedLogState]; [nowMs] is the clock the
 * minutes are counted against.
 */
@Composable
internal fun MaintenanceDetailedLogging(state: DetailedLogState, nowMs: Long, actions: DetailedLoggingActions) {
    val context = LocalContext.current
    val sharedFailed = stringResource(R.string.detailed_logging_share_failed)
    val deleted = stringResource(R.string.detailed_logging_deleted)
    MaintenanceSection(stringResource(R.string.detailed_logging_title)) {
        SwitchRow(
            title = stringResource(R.string.detailed_logging_switch),
            summary = if (state.on) {
                stringResource(R.string.detailed_logging_on_summary, detailedLoggingMinutesLeft(state.untilMs, nowMs))
            } else {
                stringResource(R.string.detailed_logging_off_summary)
            },
            checked = state.on,
            enabled = true,
            onChange = actions::setOn,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        SwitchRow(
            title = stringResource(R.string.detailed_logging_titles_switch),
            summary = stringResource(R.string.detailed_logging_titles_summary),
            checked = state.on && state.includeEverything,
            // it is an option of a run: there is nothing to include before one starts
            enabled = state.on,
            onChange = actions::setIncludeEverything,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        MaintenanceNote(stringResource(R.string.detailed_logging_contents))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        val hasLog = state.sizeBytes > 0L
        MaintenanceAction(
            Icons.Rounded.Share,
            stringResource(R.string.detailed_logging_share),
            subtitle = if (hasLog) {
                // what the file holds, not what the switch says now: lines written while titles and paths were allowed are still in it
                stringResource(
                    if (state.holdsTitlesAndPaths) R.string.detailed_logging_share_size_with_titles else R.string.detailed_logging_share_size,
                    Formatter.formatShortFileSize(context, state.sizeBytes),
                )
            } else {
                stringResource(R.string.detailed_logging_empty)
            },
            enabled = hasLog,
        ) {
            if (!actions.share()) Toast.makeText(context, sharedFailed, Toast.LENGTH_LONG).show()
        }
        MaintenanceAction(
            Icons.Rounded.Delete,
            stringResource(R.string.detailed_logging_delete),
            enabled = hasLog,
        ) {
            actions.delete()
            Toast.makeText(context, deleted, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * The clock the minutes are counted against: now, again every half minute while the section is on screen, and again at once when the run
 * it counts ([untilMs]) changes (B41: a switch turned on a few seconds after the section was drawn was counted from the old reading).
 */
@Composable
internal fun rememberDetailedLoggingNow(untilMs: Long = 0L): Long {
    val now by produceState(System.currentTimeMillis(), untilMs) {
        value = System.currentTimeMillis()
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    return now
}
