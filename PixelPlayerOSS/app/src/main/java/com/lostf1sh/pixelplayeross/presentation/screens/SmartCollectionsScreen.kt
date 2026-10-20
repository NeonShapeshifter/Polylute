package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.model.labelRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.data.collections.SmartCollection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionField
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionMatchMode
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionOperator
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRule
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortDirection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortField
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SmartCollectionsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SmartCollectionsViewModel.UiState

/**
 * The Smart Collections list, and — in the same screen — one open collection.
 *
 * Kept as one screen rather than two routes deliberately: a collection is a saved query, so
 * "the list" and "the result" are the same object at two levels of detail, and a back press between
 * them is the whole navigation a feature like this needs.
 *
 * Every row is a normal list item in the existing DAP visual system, not a card. The brief is
 * explicit that Phase 12.5 owns the library redesign and that this surface should not pre-empt it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCollectionsScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    viewModel: SmartCollectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.openCollection?.name ?: stringResource(R.string.smart_collections_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.openCollection != null) viewModel.closeCollection()
                            else navController.popBackStack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.auth_cd_back))
                    }
                },
            )
        },
    ) { padding ->
        SmartCollectionsContent(
            playerViewModel = playerViewModel,
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The collections list, and one open collection, as a body that can be hosted anywhere.
 *
 * This used to be the screen itself. It was split out because collections have two doorways — the
 * route the Maintenance and mix surfaces link to, and the Collections tab in the Library — and two
 * doorways onto one concept must not become two implementations that drift. Both hosts render this.
 *
 * ## Why there are no host flags
 *
 * The Library already owns the title and the navigation chrome, the standalone route has its own top
 * bar, and the temptation is to parameterise the differences. Instead the two differences were
 * removed: "New collection" is the first row of the list in both hosts rather than a top-bar action
 * that only one host has, and an open collection always offers a way back to the list rather than
 * relying on a top bar that only one host has. So there is one body, no `isEmbedded`, and nothing to
 * keep in sync.
 */
@Composable
fun SmartCollectionsContent(
    playerViewModel: PlayerViewModel,
    viewModel: SmartCollectionsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    bottomContentPadding: androidx.compose.ui.unit.Dp = LocalDapPlayerShelfHeight.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DapSpacing.Lg,
    /**
     * The app's track-actions sheet, where the host has one to offer.
     *
     * Null rather than a no-op default: the row's overflow button is then not drawn at all, because
     * a control that opens nothing is worse than no control. The Library tab passes the same handler
     * every other track list in the Library uses, so a row here behaves like a row anywhere else.
     */
    onSongMoreOptions: ((com.lostf1sh.pixelplayeross.data.model.Song) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SmartCollection?>(null) }
    var creating by remember { mutableStateOf(false) }

    // Read through the same stable state the other screens use, so this list highlights the
    // playing row identically to every other track list in the app.
    val currentSongId by remember {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember {
        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val open = state.openCollection
    if (open == null) {
        CollectionList(
            state = state,
            modifier = modifier,
            bottomContentPadding = bottomContentPadding,
            onCreate = { creating = true },
            onOpen = viewModel::open,
            onEdit = { editing = it },
            onDuplicate = { viewModel.duplicate(it.id) },
            onDelete = { viewModel.delete(it) },
        )
    } else {
        CollectionResult(
            state = state,
            modifier = modifier,
            bottomContentPadding = bottomContentPadding,
            onBackToList = viewModel::closeCollection,
            onPlayAll = {
                val songs = state.resultSongs
                if (songs.isNotEmpty()) playerViewModel.playSongs(songs, songs.first(), open.name, source = ListenSource(ListenSourceKind.COLLECTION, "rule:${open.id}"))
            },
            onPlaySong = { song ->
                playerViewModel.showAndPlaySong(
                    song = song,
                    contextSongs = state.resultSongs,
                    queueName = open.name,
                    updatesLastPlaylistShortcut = false,
                    source = ListenSource(ListenSourceKind.COLLECTION, "rule:${open.id}"),
                )
            },
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            onEdit = { editing = open },
            onSongMoreOptions = onSongMoreOptions,
        )
    }

    if (creating || editing != null) {
        CollectionEditor(
            source = editing,
            operatorsFor = viewModel::operatorsFor,
            needsNumber = viewModel::needsNumber,
            needsText = viewModel::needsText,
            onDismiss = { creating = false; editing = null },
            onSave = { name, rules, match, sort, direction, limit ->
                val existing = editing
                if (existing == null) {
                    viewModel.create(name, rules, match, sort, direction, limit)
                } else {
                    viewModel.save(existing, name, rules, match, sort, direction, limit)
                }
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun CollectionList(
    state: UiState,
    modifier: Modifier,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    onEdit: (SmartCollection) -> Unit,
    onDuplicate: (SmartCollection) -> Unit,
    onDelete: (SmartCollection) -> Unit,
) {
    if (state.isLoading) {
        Spacer(modifier = modifier.fillMaxSize())
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
    ) {
        item {
            // Creating a collection lives in the list itself rather than in a top bar, because only
            // one of the two hosts has a top bar. Same reason the affordance is identical in both.
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_collections_new_title), style = DapType.SettingLabel, color = Dap.primaryText) },
                supportingContent = { Text(stringResource(R.string.smart_collections_new_subtitle), style = DapType.Caption, color = Dap.secondaryText) },
                leadingContent = {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreate() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                tonalElevation = 0.dp,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
        if (state.collections.isEmpty()) {
            item {
                Text(
                    // Built-ins are seeded on first open, so this is only reachable if seeding
                    // failed. Saying so beats an empty screen that looks like a slow load.
                    text = stringResource(R.string.smart_collections_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(state.collections, key = { it.id }) { collection ->
            ListItem(
                headlineContent = { Text(collection.name, style = DapType.SettingLabel, color = Dap.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        // What the collection *is*, in the vocabulary of the rule model — so what
                        // the row says and what the editor would show cannot disagree.
                        buildString {
                            append(
                                if (collection.matchMode == SmartCollectionMatchMode.ALL) stringResource(R.string.smart_collections_match_all)
                                else stringResource(R.string.smart_collections_match_any)
                            )
                            append(" · ")
                            append(
                                pluralStringResource(
                                    R.plurals.smart_collections_rule_count,
                                    collection.rules.size,
                                    collection.rules.size
                                )
                            )
                            if (collection.limit != null) append(stringResource(R.string.smart_collections_max_format, collection.limit))
                            if (!collection.isValid) append(stringResource(R.string.smart_collections_needs_attention))
                        },
                        style = DapType.Caption,
                        color = Dap.secondaryText,
                    )
                },
                leadingContent = {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(collection.id) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                tonalElevation = 0.dp,
            )
            // Keep the name and rule summary readable at large type. Three trailing
            // actions previously squeezed them into a column only a few letters wide.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = DapSpacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
            ) {
                com.lostf1sh.pixelplayeross.ui.ds.DapButton(
                    label = stringResource(R.string.smart_collections_edit),
                    onClick = { onEdit(collection) },
                    variant = com.lostf1sh.pixelplayeross.ui.ds.DapButtonVariant.Text,
                )
                if (!collection.isBuiltIn) {
                    IconButton(onClick = { onDuplicate(collection) }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.smart_collections_cd_duplicate), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onDelete(collection) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_action), modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun CollectionResult(
    state: UiState,
    modifier: Modifier,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onBackToList: () -> Unit,
    onPlayAll: () -> Unit,
    onPlaySong: (com.lostf1sh.pixelplayeross.data.model.Song) -> Unit,
    currentSongId: String?,
    isPlaying: Boolean,
    onEdit: () -> Unit,
    onSongMoreOptions: ((com.lostf1sh.pixelplayeross.data.model.Song) -> Unit)?,
) {
    val result = state.result
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Present in both hosts. Hosted in the Library tab there is no top bar, so without
                // this the only way back to the list would be a Back press that leaves the Library —
                // which would read as losing your place.
                TextButton(onClick = onBackToList) { Text(stringResource(R.string.smart_collections_all_collections)) }
                if (result?.needsAttention == true) {
                    // A collection whose rules cannot all be evaluated says so, rather than showing
                    // a partial result that would answer a different question than it claims to.
                    Text(
                        text = stringResource(R.string.smart_collections_unreadable_rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    result.unparsedRuleDescriptions.forEach {
                        Text(text = "· $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                R.string.smart_collections_result_count_format,
                                result?.entries?.size ?: 0,
                                result?.candidateCount ?: 0
                            )
                        )
                        result?.limitApplied?.let {
                            append(stringResource(R.string.smart_collections_limit_applied_format, it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPlayAll, enabled = state.resultSongs.isNotEmpty()) {
                        Text(stringResource(R.string.smart_collections_play_all))
                    }
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.smart_collections_edit)) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }

        if (state.resultSongs.isEmpty() && result?.needsAttention != true) {
            item {
                Text(
                    // An empty collection is a true answer, not an error, and saying so is better
                    // than an empty screen that looks broken.
                    text = stringResource(R.string.smart_collections_nothing_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        items(state.resultSongs, key = { it.id }) { song ->
            EnhancedSongListItem(
                modifier = Modifier.padding(horizontal = 16.dp),
                song = song,
                isCurrentSong = currentSongId == song.id,
                isPlaying = currentSongId == song.id && isPlaying,
                onClick = { onPlaySong(song) },
                showMoreOptionsButton = onSongMoreOptions != null,
                onMoreOptionsClick = { onSongMoreOptions?.invoke(it) },
            )
        }
    }
}

/**
 * The rule editor.
 *
 * ## Type safety is enforced by what is offered, not by an error afterwards
 *
 * The operator list comes from the model, so a user cannot construct "release year contains" — the
 * choice is not on screen. That is the brief's requirement met at the point of authorship rather
 * than at the point of saving, which is the difference between a rule system that cannot be made
 * invalid and one that merely complains.
 *
 * ## The value field follows the operator
 *
 * A number operand, a text operand and a day count are different questions, so the field changes
 * with the operator rather than accepting free text for everything.
 */
@Composable
private fun CollectionEditor(
    source: SmartCollection?,
    operatorsFor: (SmartCollectionField) -> List<SmartCollectionOperator>,
    needsNumber: (SmartCollectionField, SmartCollectionOperator) -> Boolean,
    needsText: (SmartCollectionField, SmartCollectionOperator) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (
        String,
        List<SmartCollectionRule>,
        SmartCollectionMatchMode,
        SmartCollectionSortField,
        SmartCollectionSortDirection,
        Int?,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(source?.name.orEmpty()) }
    var matchMode by remember { mutableStateOf(source?.matchMode ?: SmartCollectionMatchMode.ALL) }
    var sortField by remember { mutableStateOf(source?.sortField ?: SmartCollectionSortField.TITLE) }
    var sortDirection by remember { mutableStateOf(source?.sortDirection ?: SmartCollectionSortDirection.ASCENDING) }
    var limitText by remember { mutableStateOf(source?.limit?.toString().orEmpty()) }

    val drafts = remember {
        mutableStateListOf<RuleDraft>().apply {
            source?.rules?.forEach { rule ->
                add(
                    RuleDraft(
                        field = rule.target,
                        operation = rule.operation,
                        text = rule.textValue.orEmpty(),
                        number = rule.numberValue?.let { formatNumber(it) }.orEmpty(),
                    )
                )
            }
            if (isEmpty()) add(RuleDraft())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // An alert is a window of its own, so the activity's volume-key path does not reach it.
            // The bridge routes Volume Up/Down to the same authority the activity uses — the rule
            // every app-owned window in this project follows.
            ModalInputBridge()
            Text(
                stringResource(
                    if (source == null) R.string.smart_collections_new_title
                    else R.string.smart_collections_edit_title
                )
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        keyboardActions = dapDismissKeyboardActions(),
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.smart_collections_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().dapTextField(),
                    )
                }
                item {
                    ChipRow(
                        label = stringResource(R.string.smart_collections_match_label),
                        options = SmartCollectionMatchMode.entries.map { it to stringResource(it.labelRes()) },
                        selected = matchMode,
                        onSelect = { matchMode = it },
                    )
                }
                items(drafts.size) { index ->
                    val draft = drafts[index]
                    RuleEditorRow(
                        draft = draft,
                        operatorsFor = operatorsFor,
                        needsNumber = needsNumber,
                        needsText = needsText,
                        onChange = { drafts[index] = it },
                        onRemove = { if (drafts.size > 1) drafts.removeAt(index) },
                    )
                }
                item {
                    TextButton(onClick = { drafts.add(RuleDraft()) }) { Text(stringResource(R.string.smart_collections_add_rule)) }
                }
                item {
                    ChipRow(
                        label = stringResource(R.string.smart_collections_sort_by_label),
                        options = SmartCollectionSortField.entries.map { it to stringResource(it.labelRes()) },
                        selected = sortField,
                        onSelect = { sortField = it },
                    )
                }
                item {
                    ChipRow(
                        label = stringResource(R.string.smart_collections_direction_label),
                        options = listOf(
                            SmartCollectionSortDirection.ASCENDING to stringResource(R.string.smart_collections_ascending),
                            SmartCollectionSortDirection.DESCENDING to stringResource(R.string.smart_collections_descending),
                        ),
                        selected = sortDirection,
                        onSelect = { sortDirection = it },
                    )
                }
                item {
                    OutlinedTextField(
                        value = limitText,
                        keyboardActions = dapDismissKeyboardActions(),
                        onValueChange = { limitText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.smart_collections_limit_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().dapTextField(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && drafts.any { it.isComplete() },
                onClick = {
                    onSave(
                        name,
                        drafts.filter { it.isComplete() }.map { it.toRule() },
                        matchMode,
                        sortField,
                        sortDirection,
                        limitText.toIntOrNull()?.takeIf { it > 0 },
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RuleEditorRow(
    draft: RuleDraft,
    operatorsFor: (SmartCollectionField) -> List<SmartCollectionOperator>,
    needsNumber: (SmartCollectionField, SmartCollectionOperator) -> Boolean,
    needsText: (SmartCollectionField, SmartCollectionOperator) -> Boolean,
    onChange: (RuleDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val operations = operatorsFor(draft.field)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipRow(
                label = null,
                options = SmartCollectionField.entries.map { it to stringResource(it.labelRes()) },
                selected = draft.field,
                onSelect = { field ->
                    // Changing the field resets the operator to one that field actually supports:
                    // the alternative is a rule that is transiently invalid on screen.
                    onChange(draft.copy(field = field, operation = operatorsFor(field).first()))
                },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.smart_collections_cd_remove_rule), modifier = Modifier.size(18.dp))
            }
        }
        ChipRow(
            label = null,
            options = operations.map { it to stringResource(it.labelRes()) },
            selected = draft.operation,
            onSelect = { onChange(draft.copy(operation = it)) },
        )
        when {
            needsText(draft.field, draft.operation) -> OutlinedTextField(
                value = draft.text,
                keyboardActions = dapDismissKeyboardActions(),
                onValueChange = { onChange(draft.copy(text = it)) },
                label = { Text(stringResource(R.string.smart_collections_value_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().dapTextField(),
            )
            needsNumber(draft.field, draft.operation) -> OutlinedTextField(
                value = draft.number,
                keyboardActions = dapDismissKeyboardActions(),
                onValueChange = { onChange(draft.copy(number = it.filter { c -> c.isDigit() || c == '.' })) },
                label = { Text(stringResource(R.string.smart_collections_value_label)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().dapTextField(),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

@Composable
private fun <T> ChipRow(
    label: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    // Opened at the selection rather than at the start of the list.
    //
    // The field row has nineteen options and the editor's default field is the seventeenth of them,
    // so the row used to open showing Title, Track artist, Album artist — none of them selected —
    // while the operator row underneath offered that off-screen field's operators. It read as a
    // form with nothing chosen. Scrolling to the selection means the chip that is actually applied
    // is the one on screen.
    //
    // `initialFirstVisibleItemIndex` is a first-composition value, so this places the row once and
    // then gets out of the way: it does not fight a user scrolling the row themselves, and it does
    // not jump when they pick a different chip.
    val initialIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    Column {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
        LazyRow2(items = options, state = rowState) { option ->
            FilterChip(
                selected = option.first == selected,
                onClick = { onSelect(option.first) },
                label = { Text(option.second) },
            )
        }
    }
}

/** A horizontally scrolling row of chips. Named to avoid colliding with the lazy list above. */
@Composable
private fun <T> LazyRow2(
    items: List<T>,
    state: androidx.compose.foundation.lazy.LazyListState,
    content: @Composable (T) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items.size) { index -> content(items[index]) }
    }
}

/** What the editor holds while a rule is being written. */
private data class RuleDraft(
    val field: SmartCollectionField = SmartCollectionField.FAVOURITE,
    val operation: SmartCollectionOperator = SmartCollectionOperator.IS_TRUE,
    val text: String = "",
    val number: String = "",
) {
    fun isComplete(): Boolean = when {
        operation == SmartCollectionOperator.IS_KNOWN || operation == SmartCollectionOperator.IS_UNKNOWN -> true
        operation == SmartCollectionOperator.IS_TRUE || operation == SmartCollectionOperator.IS_FALSE -> true
        field.type == com.lostf1sh.pixelplayeross.data.collections.FieldType.NUMBER -> number.isNotBlank()
        else -> text.isNotBlank()
    }

    fun toRule(): SmartCollectionRule = SmartCollectionRule(
        target = field,
        operation = operation,
        textValue = text.takeIf { it.isNotBlank() },
        numberValue = number.toDoubleOrNull(),
    )
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
