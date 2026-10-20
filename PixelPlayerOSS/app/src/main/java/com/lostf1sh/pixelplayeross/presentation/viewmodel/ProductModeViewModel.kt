package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.dap.admin.AppPinningPolicy
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver
import com.lostf1sh.pixelplayeross.dap.mode.HomeAppReader
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeController
import com.lostf1sh.pixelplayeross.presentation.settings.DedicatedGuide
import com.lostf1sh.pixelplayeross.presentation.settings.HomeModeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the product mode is, read from Android and kept current while a page that shows it is on screen (B34).
 *
 * It was read once, at construction, and never refreshed: fine for a card that said "Dedicated Mode", wrong for a page that must follow
 * the listener choosing another launcher, confirming a pin in Android's dialog, or unpinning with the system gesture. [refresh] reads again
 * off the main thread (`ProductModeLiveEffect` calls it on resume and once a second while a page is visible); an unchanged answer
 * publishes nothing.
 */
@HiltViewModel
class ProductModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val controller = ProductModeController(context)
    private val homeReader = HomeAppReader(context)
    private val dpmCommand = DedicatedGuide.command(context.packageName, ComponentName(context, DapDeviceAdminReceiver::class.java))

    private val _state = MutableStateFlow(read())
    val state: StateFlow<HomeModeState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { read() }
            _state.value = next
        }
    }

    /** Records the requested level (a request, not a claim) and reads again at once. */
    fun requestLevel(level: ProductLevel) {
        controller.requestLevel(level)
        _state.value = read()
    }

    private fun read(): HomeModeState = HomeModeState(
        product = controller.currentState(),
        home = homeReader.current(),
        pinning = AppPinningPolicy.readiness(
            runCatching { Settings.System.getInt(context.contentResolver, AppPinningPolicy.SETTING_KEY) }.getOrNull(),
        ),
        dpmCommand = dpmCommand,
    )
}
