package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.collections.SmartCollection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionEngine
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionMatchMode
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionOperator
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRepository
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionResult
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRule
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortDirection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortField
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionField
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * State for the Smart Collections surfaces.
 *
 * Thin on purpose. Every decision about *what matches* lives in the engine, and every decision about
 * *what is stored* lives in the repository; this holds the list, the open collection and its
 * generated result, and nothing else. A view model that starts interpreting rules is how a rule
 * system acquires a second, subtly different implementation of itself.
 */
@HiltViewModel
class SmartCollectionsViewModel @Inject constructor(
    private val repository: SmartCollectionRepository,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    data class UiState(
        val collections: List<SmartCollection> = emptyList(),
        val openCollection: SmartCollection? = null,
        val result: SmartCollectionResult? = null,
        val resultSongs: List<Song> = emptyList(),
        val isLoading: Boolean = true,
        /** Set when the last action failed, so the UI can say so rather than appearing to do nothing. */
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedBuiltInsIfNeeded()
            repository.observeAll().collect { collections ->
                _uiState.value = _uiState.value.copy(collections = collections, isLoading = false)
            }
        }
    }

    /** Loads a collection and generates its result. */
    fun open(collectionId: Long) {
        viewModelScope.launch {
            val collection = repository.getById(collectionId) ?: return@launch
            _uiState.value = _uiState.value.copy(openCollection = collection, isLoading = true)
            refresh(collection)
        }
    }

    /**
     * Re-runs the open collection.
     *
     * Called whenever the screen becomes visible as well as after an edit, because a collection is a
     * *view*: a play count that changed while the user was elsewhere must be reflected on return, and
     * there is no cache to invalidate.
     */
    fun refreshOpenCollection() {
        val collection = _uiState.value.openCollection ?: return
        viewModelScope.launch { refresh(collection) }
    }

    private suspend fun refresh(collection: SmartCollection) {
        val result = repository.execute(collection)
        val songs = hydrate(result)
        _uiState.value = _uiState.value.copy(result = result, resultSongs = songs, isLoading = false)
    }

    /**
     * Resolves the result to playable songs, **in the engine's order**.
     *
     * The order is the whole point of a saved sort, and a database returning rows by primary key
     * would silently undo it. A locator that no longer resolves is dropped rather than substituted.
     */
    private suspend fun hydrate(result: SmartCollectionResult): List<Song> {
        if (result.isEmpty) return emptyList()
        val songs = musicRepository.getSongsByIds(result.entries.map { it.locator.toString() }).first()
        val byLocator = songs.associateBy { it.id }
        return result.entries.mapNotNull { entry -> byLocator[entry.locator.toString()] }
    }

    fun create(
        name: String,
        rules: List<SmartCollectionRule>,
        matchMode: SmartCollectionMatchMode,
        sortField: SmartCollectionSortField,
        sortDirection: SmartCollectionSortDirection,
        limit: Int?,
    ) {
        if (name.isBlank() || rules.isEmpty()) return
        viewModelScope.launch {
            val id = repository.create(name, rules, matchMode, sortField, sortDirection, limit)
            open(id)
        }
    }

    fun save(
        collection: SmartCollection,
        name: String,
        rules: List<SmartCollectionRule>,
        matchMode: SmartCollectionMatchMode,
        sortField: SmartCollectionSortField,
        sortDirection: SmartCollectionSortDirection,
        limit: Int?,
    ) {
        if (name.isBlank() || rules.isEmpty()) return
        viewModelScope.launch {
            repository.update(
                collection.copy(
                    name = name,
                    rules = rules,
                    matchMode = matchMode,
                    sortField = sortField,
                    sortDirection = sortDirection,
                    limit = limit,
                )
            )
            open(collection.id)
        }
    }

    fun duplicate(collectionId: Long) {
        viewModelScope.launch { repository.duplicate(collectionId)?.let { open(it) } }
    }

    /**
     * Deletes a collection.
     *
     * A built-in is left alone by the repository, so this cannot remove one — and the UI does not
     * offer it. Reported rather than silent if it somehow does nothing.
     */
    fun delete(collection: SmartCollection) {
        viewModelScope.launch {
            repository.delete(collection.id)
            _uiState.value = _uiState.value.copy(
                openCollection = null,
                result = null,
                resultSongs = emptyList(),
                message = if (collection.isBuiltIn) "Built-in collections cannot be deleted" else null,
            )
        }
    }

    fun closeCollection() {
        _uiState.value = _uiState.value.copy(openCollection = null, result = null, resultSongs = emptyList())
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * What the editor should offer for a field.
     *
     * Delegates to the model, so the UI cannot present an operator the engine would reject — the
     * brief's requirement that "release year contains" be impossible through normal use is enforced
     * by the editor showing only valid choices, not by a validation error afterwards.
     */
    fun operatorsFor(field: SmartCollectionField): List<SmartCollectionOperator> =
        SmartCollectionOperator.forField(field)

    /** True when the operator takes a text operand. */
    fun needsText(field: SmartCollectionField, operation: SmartCollectionOperator): Boolean =
        (field.type == SmartCollectionField.TITLE.type ||
            field.type == SmartCollectionField.GENRE.type) &&
            operation != SmartCollectionOperator.IS_KNOWN &&
            operation != SmartCollectionOperator.IS_UNKNOWN

    /** True when the operator takes a numeric operand. */
    fun needsNumber(field: SmartCollectionField, operation: SmartCollectionOperator): Boolean =
        operation != SmartCollectionOperator.IS_KNOWN &&
            operation != SmartCollectionOperator.IS_UNKNOWN &&
            (field.type == SmartCollectionField.PLAY_COUNT.type ||
                field.type == SmartCollectionField.RELEASE_YEAR.type ||
                field.type == SmartCollectionField.DURATION_MS.type ||
                field.type == SmartCollectionField.SAMPLE_RATE.type ||
                field.type == SmartCollectionField.BITRATE.type ||
                field.type == SmartCollectionField.TRACK_NUMBER.type ||
                field.type == SmartCollectionField.DISC_NUMBER.type ||
                field.type == SmartCollectionField.COMPLETION_COUNT.type ||
                field.type == SmartCollectionField.SKIP_COUNT.type)

    /** True when the operator takes a day count rather than a date. */
    fun needsDays(field: SmartCollectionField, operation: SmartCollectionOperator): Boolean =
        operation == SmartCollectionOperator.IN_LAST_DAYS || operation == SmartCollectionOperator.NOT_IN_LAST_DAYS

    companion object {
        /** Exposed so the editor can label the empty state without knowing the engine. */
        val engineForDiagnostics = SmartCollectionEngine()
    }
}
