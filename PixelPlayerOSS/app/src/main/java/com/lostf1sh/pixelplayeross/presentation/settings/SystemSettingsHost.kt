package com.lostf1sh.pixelplayeross.presentation.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.mainActivityOrNull
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ProductModeViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel

/**
 * The System page with the platform round trips it needs (B32-Q6): the activity that owns Lock Task. They live here and not in
 * [SystemSettingsScreen], which only says what the listener asked for. B34: the Android shortcuts follow the product mode live.
 */
@Composable
fun SystemSettingsHost(
    settingsViewModel: SettingsViewModel,
    navigate: (String) -> Unit,
    productModeViewModel: ProductModeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val purgeable by settingsViewModel.purgeableDormantCount.collectAsStateWithLifecycle()
    var isRebuilding by remember { mutableStateOf(false) }
    val usbAvailable = remember(context) {
        Intent(SystemSettingsActionsImpl.USB_SETTINGS_ACTION).resolveActivity(context.packageManager) != null
    }
    // Refreshed on demand rather than observed: opening the page should not put a query on a path playback shares.
    LaunchedEffect(Unit) { settingsViewModel.refreshPurgeableDormantCount() }

    ProductModeLiveEffect(productModeViewModel)
    val mode by productModeViewModel.state.collectAsStateWithLifecycle()
    val actions = remember(settingsViewModel, context, navigate) {
        SystemSettingsActionsImpl(
            vm = settingsViewModel,
            host = context.mainActivityOrNull(),
            navigate = navigate,
            notify = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
            notifyRemoved = { removed ->
                Toast.makeText(context, context.getString(R.string.maintenance_dormant_cleanup_done, removed), Toast.LENGTH_SHORT).show()
            },
            onRebuildStarted = { isRebuilding = true },
        )
    }
    SystemSettingsScreen(
        state = SystemSettingsState(
            purgeableDormantCount = purgeable,
            isRebuilding = isRebuilding,
            usbSettingsAvailable = usbAvailable,
            showAndroidShortcuts = mode.product.offersAndroidShortcuts,
        ),
        actions = actions,
    )
}
