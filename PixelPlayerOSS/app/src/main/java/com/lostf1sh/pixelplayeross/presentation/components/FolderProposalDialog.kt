package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FolderProposalState
import java.io.File

/**
 * B39-A9: the folders found with music, every one already checked. One tap on the button is "add them all"; any can be unchecked first.
 * The first run and "Find folders with music" in Settings, Library show the same dialog.
 */
@Composable
fun FolderProposalDialog(
    state: FolderProposalState,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chosenSongs = state.chosenSongs
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_proposal_title)) },
        text = {
            // this window is its own surface: the activity's volume-key path does not reach it
            ModalInputBridge()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.folder_proposal_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(state.folders, key = { it.path }) { folder ->
                        val chosen = state.isChosen(folder.path)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = chosen, role = Role.Checkbox, onValueChange = { onToggle(folder.path) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // the row is the toggle (one target, announced once as a checkbox); the box only shows it
                            Checkbox(checked = chosen, onCheckedChange = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = File(folder.path).name.ifEmpty { folder.path },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = folder.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                )
                                Text(
                                    text = pluralStringResource(R.plurals.folder_proposal_songs, folder.songCount, folder.songCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = state.chosen.isNotEmpty()) {
                Text(
                    if (chosenSongs > 0) pluralStringResource(R.plurals.folder_proposal_add, chosenSongs, chosenSongs)
                    else stringResource(R.string.folder_proposal_nothing_chosen),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.folder_proposal_not_now)) }
        },
    )
}
