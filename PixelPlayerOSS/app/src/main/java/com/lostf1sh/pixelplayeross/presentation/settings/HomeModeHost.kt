package com.lostf1sh.pixelplayeross.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.dap.admin.AppPinningPolicy
import com.lostf1sh.pixelplayeross.dap.mode.HomePicker
import com.lostf1sh.pixelplayeross.presentation.mainActivityOrNull
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ProductModeViewModel

/**
 * "Inicio y modo DAP" with the platform round trips it needs (B34): the activity that owns Lock Task and pinning, Android's home picker
 * and the Security settings, the clipboard. They live here and not in [HomeModeScreen], which only says what the listener asked for.
 */
@Composable
fun HomeModeHost(productModeViewModel: ProductModeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    ProductModeLiveEffect(productModeViewModel)
    val state by productModeViewModel.state.collectAsStateWithLifecycle()
    val actions = remember(productModeViewModel, context) {
        val activity = context.mainActivityOrNull()
        HomeModeActionsImpl(
            state = { productModeViewModel.state.value.product },
            requestLevel = productModeViewModel::requestLevel,
            refresh = productModeViewModel::refresh,
            host = activity,
            modeHost = activity,
            openHomePicker = { HomePicker.resolveIntent(context)?.let { context.startSafely(it) } == true },
            openSecuritySettings = { context.startSafely(AppPinningPolicy.securitySettingsIntent()) },
            dpmCommand = productModeViewModel.state.value.dpmCommand,
            copy = { text ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("dpm", text))
            },
            notify = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
        )
    }
    HomeModeScreen(state = state, actions = actions)
}

private fun Context.startSafely(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
