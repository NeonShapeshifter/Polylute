package com.lostf1sh.pixelplayeross.presentation.settings

import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.admin.AppPinningPolicy
import com.lostf1sh.pixelplayeross.dap.admin.PinningReadiness
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.dap.mode.HomeApp
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveRow
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedRow
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/** What the "Inicio y modo DAP" page draws (B34): what Android says, never what was asked for. */
@Immutable
data class HomeModeState(
    val product: ProductModeState = ProductModeState(
        requestedMode = ProductModeRequest.APP,
        effectiveMode = EffectiveProductMode.APP,
        isDeviceOwner = false,
        hasDedicatedProvisioningEvidence = false,
    ),
    /** The app that answers the HOME button now, on any brand. */
    val home: HomeApp = HomeApp.Unknown,
    /** Whether Android's "App pinning" switch is on. */
    val pinning: PinningReadiness = PinningReadiness.UNKNOWN,
    /** The exact command for this build's package and receiver ([DedicatedGuide.command]). */
    val dpmCommand: String = "",
)

/** The exact command that makes this build the device owner: shown, never run (B34). */
object DedicatedGuide {
    fun command(packageName: String, receiver: ComponentName): String =
        "adb shell dpm set-device-owner ${ComponentName(packageName, receiver.className).flattenToString()}"
}

/**
 * Everything the page can do (B34). The leave / return / release actions are asked "are you sure?" by the page first, in a dialog that
 * says what they do; the others are a tap.
 */
interface HomeModeActions {
    fun onChooseHomeApp()
    fun onSelectLevel(level: ProductLevel)
    fun onPinNow()
    fun onUnpin()
    fun onOpenSecuritySettings()
    fun onCopyDpmCommand()
    fun onReturnToDap()
    fun onReleaseDeviceOwner()
}

private enum class HomeConfirmation { LEAVE_HOME, RETURN_TO_DAP, RELEASE_OWNER }

/**
 * "Inicio y modo DAP" (B34): the home app, and, when Polylute is the home app, one choice of three levels.
 *
 *  - **Launcher**: Polylute is home, nothing locked.
 *  - **Fijado**: Android's app pinning. No Device Owner.
 *  - **Dedicado** (advanced): Device Owner + Lock Task. Without Device Owner it is a guide, never a switch.
 *
 * The page shows the requested level and the level Android confirms side by side and never claims one Android does not confirm.
 * [mountInputBridge] is true in the app (a dialog is a window of its own and the volume keys must still reach the Safety authority).
 */
@Composable
fun HomeModeScreen(
    state: HomeModeState,
    actions: HomeModeActions,
    mountInputBridge: Boolean = true,
) {
    var confirming by remember { mutableStateOf<HomeConfirmation?>(null) }
    val product = state.product

    SettingsSubsection(title = stringResource(R.string.home_mode_section_home), addBottomSpace = !product.isPolyluteHome) {
        SystemRow(
            Icons.Rounded.Home,
            R.string.home_mode_choose_home,
            subtitle = homeNowText(state.home),
        ) {
            // lifting Lock Task and clearing the HOME policy is what the listener is asked about; the plain picker needs no question
            if (product.isDedicatedActive) confirming = HomeConfirmation.LEAVE_HOME else actions.onChooseHomeApp()
        }
        if (!product.isPolyluteHome) {
            Caption(stringResource(R.string.home_mode_levels_after_home))
        }
    }

    if (product.isPolyluteHome) {
        val levels = ProductLevel.entries
        val labels = levels.associateWith { stringResource(levelLabel(it)) }
        SettingsSubsection(title = stringResource(R.string.home_mode_section_level)) {
            DapSegmentedRow(
                label = stringResource(R.string.home_mode_requested),
                options = levels,
                selected = product.requestedLevel,
                onSelect = actions::onSelectLevel,
                optionLabel = { labels.getValue(it) },
                description = if (product.isDeviceOwner) stringResource(R.string.home_mode_locked_by_owner) else null,
                // a Device Owner stays Dedicated whatever is asked: the way down is giving up the role, below
                enabled = !product.isDeviceOwner,
            )
            DapNavRow(
                label = stringResource(R.string.home_mode_actual),
                value = product.level?.let { labels.getValue(it) },
                valueStyle = DapType.CaptionMedium,
                description = if (product.level != product.requestedLevel) stringResource(R.string.home_mode_not_confirmed) else null,
            )
        }
        when (product.requestedLevel) {
            ProductLevel.LAUNCHER -> SettingsSubsection(title = labels.getValue(ProductLevel.LAUNCHER)) {
                Caption(stringResource(R.string.home_mode_launcher_body))
            }
            ProductLevel.PINNED -> PinnedLevel(state, actions)
            ProductLevel.DEDICATED -> DedicatedLevel(state, actions) { confirming = it }
        }
    }

    confirming?.let { which ->
        val dismiss = { confirming = null }
        val run: (() -> Unit) -> Unit = { action ->
            confirming = null
            action()
        }
        when (which) {
            HomeConfirmation.LEAVE_HOME -> ConfirmDialog(
                title = R.string.home_mode_leave_confirm_title,
                body = stringResource(R.string.home_mode_leave_confirm_body),
                confirm = R.string.maintenance_continue,
                destructive = false,
                mountInputBridge = mountInputBridge,
                onDismiss = dismiss,
                onConfirm = { run(actions::onChooseHomeApp) },
            )
            HomeConfirmation.RETURN_TO_DAP -> ConfirmDialog(
                title = R.string.system_confirm_return_title,
                body = stringResource(R.string.system_confirm_return_body),
                confirm = R.string.system_confirm_return_action,
                destructive = false,
                mountInputBridge = mountInputBridge,
                onDismiss = dismiss,
                onConfirm = { run(actions::onReturnToDap) },
            )
            HomeConfirmation.RELEASE_OWNER -> ConfirmDialog(
                title = R.string.home_mode_release_confirm_title,
                body = stringResource(R.string.home_mode_release_confirm_body),
                confirm = R.string.home_mode_release_confirm_action,
                destructive = true,
                mountInputBridge = mountInputBridge,
                onDismiss = dismiss,
                onConfirm = { run(actions::onReleaseDeviceOwner) },
            )
        }
    }
}

@Composable
private fun PinnedLevel(state: HomeModeState, actions: HomeModeActions) {
    val product = state.product
    val pinned = product.level == ProductLevel.PINNED
    SettingsSubsection(title = stringResource(R.string.home_mode_level_pinned)) {
        Caption(stringResource(R.string.home_mode_pinned_body))
        if (state.pinning == PinningReadiness.DISABLED_IN_SETTINGS && !pinned) {
            Caption(stringResource(R.string.home_mode_pin_disabled))
            SystemRow(Icons.Rounded.Security, R.string.home_mode_open_security, onClick = actions::onOpenSecuritySettings)
        }
        if (pinned) {
            DapNavRow(
                label = stringResource(R.string.home_mode_pinned_active),
                leading = { androidx.compose.material3.Icon(Icons.Outlined.PushPin, null, tint = Dap.secondaryText) },
            )
            SystemRow(Icons.Outlined.PushPin, R.string.home_mode_unpin_now, onClick = actions::onUnpin)
        } else {
            SystemRow(
                Icons.Outlined.PushPin,
                R.string.home_mode_pin_now,
                subtitle = stringResource(R.string.home_mode_pin_now_subtitle),
                enabled = AppPinningPolicy.canRequest(state.pinning, product),
                onClick = actions::onPinNow,
            )
        }
        Caption(stringResource(R.string.home_mode_unpin_how))
    }
}

@Composable
private fun DedicatedLevel(state: HomeModeState, actions: HomeModeActions, confirm: (HomeConfirmation) -> Unit) {
    val product = state.product
    SettingsSubsection(title = stringResource(R.string.home_mode_level_dedicated), addBottomSpace = false) {
        Caption(stringResource(R.string.home_mode_dedicated_body))
        if (!product.isDeviceOwner) {
            // a guide, not a switch: nothing is claimed active, and the command is for the computer
            Caption(stringResource(R.string.home_mode_guide_title), strong = true)
            Caption(stringResource(R.string.home_mode_guide_requirements))
            Caption(stringResource(R.string.home_mode_guide_command_label))
            SelectionContainer {
                Text(
                    text = state.dpmCommand,
                    style = DapType.Value,
                    color = Dap.primaryText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            SystemRow(Icons.Rounded.ContentCopy, R.string.home_mode_guide_copy, onClick = actions::onCopyDpmCommand)
            Caption(stringResource(R.string.home_mode_guide_may_refuse))
        } else {
            DapNavRow(
                label = stringResource(R.string.home_mode_lock_state),
                value = stringResource(if (product.isLockHeld) R.string.home_mode_lock_held else R.string.home_mode_lock_not_held),
                valueStyle = DapType.CaptionMedium,
                leading = { androidx.compose.material3.Icon(Icons.Rounded.Lock, null, tint = Dap.secondaryText) },
            )
            // B34-fix2 R2: the level above is what Android confirms (Launcher while nothing is locked); this says what fixes it
            if (product.isPolyluteHome && !product.isLockHeld) Caption(stringResource(R.string.home_mode_not_locked_hint))
            SystemRow(Icons.AutoMirrored.Rounded.ExitToApp, R.string.maintenance_exit) { confirm(HomeConfirmation.RETURN_TO_DAP) }
            DapDestructiveRow(
                label = stringResource(R.string.home_mode_release_owner),
                description = stringResource(R.string.home_mode_release_owner_subtitle),
                onClick = { confirm(HomeConfirmation.RELEASE_OWNER) },
            )
        }
    }
}

@Composable
private fun Caption(text: String, strong: Boolean = false) {
    Text(
        text = text,
        style = if (strong) DapType.SettingLabel else DapType.Caption,
        color = if (strong) Dap.primaryText else Dap.secondaryText,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun homeNowText(home: HomeApp): String = when {
    home.isChooser -> stringResource(R.string.home_mode_home_none)
    home.label != null -> stringResource(R.string.home_mode_home_now, home.label)
    else -> stringResource(R.string.home_mode_home_unknown)
}

private fun levelLabel(level: ProductLevel): Int = when (level) {
    ProductLevel.LAUNCHER -> R.string.home_mode_level_launcher
    ProductLevel.PINNED -> R.string.home_mode_level_pinned
    ProductLevel.DEDICATED -> R.string.home_mode_level_dedicated
}
