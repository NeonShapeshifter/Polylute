package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.lostf1sh.pixelplayeross.data.diagnostics.DetailedLogState
import com.lostf1sh.pixelplayeross.data.diagnostics.DetailedLogging
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** B40-F7: Maintenance's "Detailed logging" switch, and the log it makes. The rules and the file are [DetailedLogging]'s. */
@HiltViewModel
class DetailedLoggingViewModel @Inject constructor(
    private val logging: DetailedLogging,
) : ViewModel() {
    val state: StateFlow<DetailedLogState> = logging.state

    fun setOn(on: Boolean) = if (on) logging.enable() else logging.disable()

    fun setIncludeEverything(value: Boolean) = logging.setIncludeEverything(value)

    fun delete() = logging.delete()

    /** The share sheet for the log, or null when there is nothing to share. */
    fun shareIntent(): Intent? = logging.shareIntent()
}
