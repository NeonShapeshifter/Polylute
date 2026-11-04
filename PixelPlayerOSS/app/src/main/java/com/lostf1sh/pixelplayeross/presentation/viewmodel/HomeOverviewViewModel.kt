package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only the overview report, plus the ledger projection used by Home and the history page. Never a Stats summary. */
data class HomeListeningState(
    val loaded: Boolean = false,
    val overview: StatsReport? = null,
    val recent: List<RecentlyPlayedSongUiModel> = emptyList(),
    val artistCredits: List<ArtistRef> = emptyList(),
    val nowMs: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
    val catalog: LibraryCatalog = LibraryCatalog.Empty,
)

@HiltViewModel
@OptIn(FlowPreview::class,ExperimentalCoroutinesApi::class)
class HomeOverviewViewModel @Inject constructor(
    private val ledgers: LedgerProvider,
    private val dispatchers: DispatcherProvider,
    private val clockChanges: ClockChanges,
    private val preferences: UserPreferencesRepository,
    private val music: MusicRepository,
    private val opens: SuggestionsOpenSession,
) : ViewModel() {
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault
    private val mutableState=MutableStateFlow(HomeListeningState())
    val state: StateFlow<HomeListeningState> = mutableState.asStateFlow()
    val openToken=opens.token
    internal val suggestionPicker=com.lostf1sh.pixelplayeross.presentation.model.SongSuggestionPicker()
    private var load: Job? = null
    val recentArtists: StateFlow<List<Artist>> = state.map { it.artistCredits }.distinctUntilChanged().flatMapLatest { refs ->
        if(refs.isEmpty()) flowOf(emptyList()) else music.observeArtistsForHome(refs.map { it.id }).map { rows ->
            val byId=rows.associateBy { it.id }
            refs.map { byId[it.id] ?: Artist(it.id,it.name,0) }
        }
    }.flowOn(dispatchers.default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    init {
        refresh()
        viewModelScope.launch { ledgers.changes.drop(1).debounce(2000).collect { refresh() } }
        viewModelScope.launch { preferences.artistDelimitersFlow.distinctUntilChanged().drop(1).collect { refresh() } }
        viewModelScope.launch { clockChanges.changes.collect { refresh() } }
    }
    /** Cold and off the UI thread; collected only if both saved mixes are empty. Reuses the ledger's catalog. */
    fun fallbackPreview(): Flow<List<Song>?> = state.map { it.loaded to it.catalog }.distinctUntilChanged().map { (loaded,catalog) ->
        if(!loaded) null else catalog.items.sortedWith(compareByDescending<CatalogItem> { it.song.dateAdded }.thenBy { it.identity })
            .take(48).map { it.song }
    }.flowOn(dispatchers.default)

    fun refresh() {
        load?.cancel()
        load=viewModelScope.launch {
            try {
                mutableState.value=withContext(dispatchers.default) {
                    val ledger=ledgers.ledger()
                    val now=clock();val zone=zone()
                    val delimiters=preferences.artistDelimitersFlow.first()
                    val recent=recentListening(ledger,now)
                    val credits=recentArtistCredits(recent,delimiters).mapNotNull { ref ->
                        if(ref.id != -1L) ref else withContext(dispatchers.io) { music.getArtistIdByName(ref.name) }?.let { ref.copy(id=it) }
                    }
                    val overview=listOf(StatsTimeRange.WEEK,StatsTimeRange.MONTH,StatsTimeRange.YEAR,StatsTimeRange.ALL).asSequence()
                        .map { StatsEngine.report(ledger,StatsPeriod.Preset(it),now,zone,StatsEngine.Detail.OVERVIEW) }.firstOrNull { !it.isEmpty }
                    HomeListeningState(true,overview,recent,credits,now,zone,ledger.catalog)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { timber.log.Timber.w(failure,"Home overview could not be loaded") }
        }
    }
}
