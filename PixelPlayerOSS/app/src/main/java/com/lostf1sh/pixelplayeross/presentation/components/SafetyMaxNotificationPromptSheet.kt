package com.lostf1sh.pixelplayeross.presentation.components

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxNotificationPromptPolicy
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent

/**
 * In-context glass sheet explaining why Safety Max needs notifications before launching the Android 13+ permission prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyMaxNotificationPromptSheet(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
) {
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    GlassModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = sheetState,
    ) {
        ModalInputBridge()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DapSpacing.Gutter),
        ) {
            Text(
                text = stringResource(R.string.safety_max_notif_prompt_title),
                style = DapType.Title,
                color = Dap.primaryText,
            )
            Spacer(Modifier.height(DapSpacing.Sm))
            Text(
                text = stringResource(R.string.safety_max_notif_prompt_body),
                style = DapType.Body,
                color = Dap.secondaryText,
            )
            Spacer(Modifier.height(DapSpacing.Lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
            ) {
                DapSecondaryButton(
                    label = stringResource(R.string.fixed_output_notice_not_now),
                    onClick = onNotNow,
                    modifier = Modifier.weight(1f),
                )
                DapPrimaryButton(
                    label = stringResource(R.string.safety_max_notif_prompt_allow),
                    onClick = onAllow,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(DapSpacing.Lg))
        }
    }
}

/**
 * Calm inline line shown on Safety Max surfaces on API 33+ when notification permission is missing,
 * paired with a direct link to the system notification settings for Polylute.
 */
@Composable
fun NotificationsOffNotice(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
    ) {
        Text(
            text = stringResource(R.string.safety_max_notifications_off_note),
            style = DapType.Caption,
            color = Dap.secondaryText,
        )
        DapTextAction(
            label = stringResource(R.string.safety_max_open_notification_settings),
            onClick = onOpenSettings,
            color = LocalDapAccent.current,
            modifier = Modifier.offset(x = -DapSpacing.Xs),
        )
    }
}

internal fun isPostNotificationsGranted(
    context: Context,
    androidApi: Int = Build.VERSION.SDK_INT,
): Boolean =
    androidApi < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

internal fun isNotificationPermissionMissing(
    context: Context,
    androidApi: Int = Build.VERSION.SDK_INT,
): Boolean = SafetyMaxNotificationPromptPolicy.shouldShowNotificationsOffNotice(
    androidApi = androidApi,
    permissionGranted = isPostNotificationsGranted(context, androidApi),
)

@Composable
internal fun rememberNotificationPermissionMissing(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var missing by remember(context) { mutableStateOf(isNotificationPermissionMissing(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missing = isNotificationPermissionMissing(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return missing
}

internal fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    val activity = context.findMainActivity()
    if (activity != null) {
        activity.beginTemporarySystemConfirmation { flowReady ->
            if (flowReady) {
                runCatching { activity.startActivity(intent) }
                    .onFailure { activity.finishTemporarySystemConfirmation() }
            }
        }
    } else {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

internal fun launchPostNotificationsRequest(
    context: Context,
    onLaunched: () -> Unit,
    launchPermission: (String) -> Unit,
) {
    val activity = context.findMainActivity()
    if (activity != null) {
        activity.beginTemporarySystemConfirmation { flowReady ->
            if (flowReady) {
                onLaunched()
                launchPermission(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    } else {
        onLaunched()
        launchPermission(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun finishNotificationPermissionFlow(context: Context) {
    context.findMainActivity()?.finishTemporarySystemConfirmation()
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
