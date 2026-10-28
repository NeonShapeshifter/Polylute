package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.offline.OfflineKind
import com.lostf1sh.pixelplayeross.data.offline.OfflineStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OfflineDownloadsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/** Explicit, one-shot network consent. Closing this window leaves WorkManager's task running. */
@Composable
fun OfflineDownloadsDialog(songIds: List<String>?, onDismiss: () -> Unit, viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    val batch by viewModel.state.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf(listOf(OfflineKind.LYRICS.name)) }
    val progress = starting || !selecting
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_download_title)) },
        text = {
            ModalInputBridge()
            Column(Modifier.fillMaxWidth()) {
                if (!progress) {
                    Text(if (songIds == null) stringResource(R.string.offline_download_all) else stringResource(R.string.offline_download_selected, songIds.size))
                    OfflineKind.entries.forEach { kind ->
                        val label = stringResource(when (kind) {
                            OfflineKind.LYRICS -> R.string.offline_download_lyrics
                            OfflineKind.ARTIST_PHOTOS -> R.string.offline_download_photos
                            OfflineKind.COVERS -> R.string.offline_download_covers
                            OfflineKind.DESCRIPTIONS -> R.string.offline_download_descriptions
                        })
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = kind.name in selected, role = Role.Checkbox,
                            onValueChange = { on -> selected = if (on) selected + kind.name else selected - kind.name }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = kind.name in selected, onCheckedChange = null)
                            Text(label)
                        }
                    }
                    Text(stringResource(R.string.offline_download_note), style = DapType.Caption)
                    if (batch != null) TextButton(onClick = { selecting = false }) { Text(stringResource(R.string.offline_download_last)) }
                } else if (starting) {
                    Text(stringResource(R.string.offline_download_preparing))
                } else batch?.let { current ->
                    Text(stringResource(when (current.status) {
                        OfflineStatus.QUEUED -> R.string.offline_download_waiting
                        OfflineStatus.RUNNING -> R.string.offline_download_running
                        OfflineStatus.PAUSED -> R.string.offline_download_paused
                        OfflineStatus.COMPLETE -> R.string.offline_download_complete
                    }))
                    LinearProgressIndicator(progress = { if (current.total == 0) 0f else current.next.toFloat() / current.total },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(stringResource(when (current.kinds.singleOrNull()) {
                        OfflineKind.LYRICS -> R.string.offline_download_progress
                        OfflineKind.ARTIST_PHOTOS -> R.string.offline_download_progress_artists
                        OfflineKind.COVERS -> R.string.offline_download_progress_albums
                        else -> R.string.offline_download_progress_items
                    }, current.next, current.total))
                    Text(stringResource(R.string.offline_download_summary, current.available, current.unavailable, current.failed), style = DapType.Caption)
                    current.estimatedRemainingMs?.let { remaining ->
                        Text(stringResource(R.string.offline_download_estimate, (remaining / 60_000L).coerceAtLeast(1L)), style = DapType.Caption)
                    }
                    if (current.failed > 0) Text(stringResource(R.string.offline_download_retry, current.failed), style = DapType.Caption)
                    if (current.active) Text(current.lastTitle, maxLines = 2)
                    else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(results) { result ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(result.title, style = DapType.Body)
                                    Text(stringResource(R.string.offline_download_summary, result.available, result.unavailable, result.failed), style = DapType.Caption)
                                }
                            }
                        }
                        if (current.status == OfflineStatus.PAUSED && current.next < current.total) {
                            TextButton(onClick = viewModel::resume) { Text(stringResource(R.string.offline_download_resume)) }
                        }
                        TextButton(onClick = { viewModel.deleteJob(current.planId) }) { Text(stringResource(R.string.offline_download_delete_job)) }
                        TextButton(onClick = { selecting = true }) { Text(stringResource(R.string.offline_download_new)) }
                    }
                }
                jobs.filter { it.planId != batch?.planId }.forEach { job ->
                    Text(stringResource(R.string.offline_download_job, job.songIds.size, job.next, job.total), style = DapType.Caption)
                    Text(stringResource(when (job.status) {
                        OfflineStatus.QUEUED, OfflineStatus.RUNNING -> R.string.offline_download_queued_job
                        OfflineStatus.PAUSED -> R.string.offline_download_paused
                        OfflineStatus.COMPLETE -> R.string.offline_download_complete
                    }), style = DapType.Caption)
                    if (!job.active) TextButton(onClick = { viewModel.deleteJob(job.planId) }) { Text(stringResource(R.string.offline_download_delete_job)) }
                }
                if (error) {
                    Text(stringResource(R.string.offline_download_failed))
                    TextButton(onClick = { selecting = true }) { Text(stringResource(R.string.offline_download_new)) }
                }
            }
        },
        confirmButton = {
            if (!progress) TextButton(enabled = selected.isNotEmpty(), onClick = {
                viewModel.start(songIds, selected.mapNotNull { name -> OfflineKind.entries.find { it.name == name } }.toSet())
                selecting = false
            }) { Text(stringResource(R.string.offline_download_start)) }
            else if (batch?.active == true) TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.offline_download_pause)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.offline_download_close)) } },
    )
}
