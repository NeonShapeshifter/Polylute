package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.offline.OfflineDownloads
import com.lostf1sh.pixelplayeross.data.offline.OfflineKind
import com.lostf1sh.pixelplayeross.data.offline.OfflineResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(private val downloads: OfflineDownloads) : ViewModel() {
    val state = downloads.state
    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error = _error.asStateFlow()
    private val _results = MutableStateFlow<List<OfflineResult>>(emptyList())
    val results = _results.asStateFlow()
    init {
        viewModelScope.launch { runCatching { downloads.refresh() } }
        viewModelScope.launch { state.collectLatest { batch ->
            _results.value = if (batch != null && !batch.active) downloads.results() else emptyList()
        } }
    }
    fun start(ids: List<String>?, kinds: Set<OfflineKind>) {
        if (_starting.value || state.value?.active == true) return
        _starting.value = true
        _error.value = false
        viewModelScope.launch {
            try { downloads.start(ids, kinds) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { _error.value = true }
            finally { _starting.value = false }
        }
    }
    fun cancel() { viewModelScope.launch { downloads.cancel() } }
    fun resume() { viewModelScope.launch { downloads.resume() } }
}
