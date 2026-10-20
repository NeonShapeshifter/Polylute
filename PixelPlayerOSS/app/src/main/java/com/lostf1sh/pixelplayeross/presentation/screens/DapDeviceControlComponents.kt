package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.ResonanceSource
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import com.lostf1sh.pixelplayeross.dap.compat.CapabilityStatus
import com.lostf1sh.pixelplayeross.dap.compat.DeviceCapability
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapBatteryPlug
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapDeviceControlPolicy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ScreenTimeoutPolicy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DigitalGainSlider
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.utils.StorageType
import java.util.Locale
import com.lostf1sh.pixelplayeross.ui.ds.DapVolumeChannel
import com.lostf1sh.pixelplayeross.ui.ds.DapVolumeControl
import com.lostf1sh.pixelplayeross.ui.ds.DapLevelControl
import com.lostf1sh.pixelplayeross.ui.ds.formatDecibels
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlin.math.roundToInt

@Composable
fun DapQuickDeviceControls(
    state: DeviceControlUiState,
    onBrightnessChanged: (Int) -> Unit,
    onVolumeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDigitalGainChanged: (Int) -> Unit = {},
    /** F-05: opens the page that owns the screen timeout. Null hides the "never" notice. */
    onReviewScreenTimeout: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DapGroup(title = stringResource(R.string.dap_controls_title)) {
            Column(modifier = Modifier.padding(top = DapSpacing.Md)) {
                DapLevelControl(
                    title = stringResource(R.string.dap_brightness_title),
                    primary = DapVolumeChannel(
                        label = stringResource(R.string.dap_brightness_title),
                        fraction = (state.brightnessPercent - 5) / 95f,
                        valueText = "${state.brightnessPercent}%",
                        onChange = { fraction ->
                            val percent = 5 + (fraction * 95f).roundToInt()
                            if (percent != state.brightnessPercent) onBrightnessChanged(percent)
                        },
                    ),
                    leading = {
                        Icon(
                            Icons.Rounded.Brightness6,
                            contentDescription = null,
                            tint = Dap.secondaryText,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // F-05: on the appliance, a system timeout of "never" is said where the controls
                // are, once, with a way to the page that changes it. It changes nothing by itself.
                val timeoutOffer = state.screenTimeoutOffer
                if (timeoutOffer is ScreenTimeoutPolicy.Offer.CorrectNever &&
                    timeoutOffer.prominent &&
                    onReviewScreenTimeout != null
                ) {
                    ScreenTimeoutNeverNotice(onReview = onReviewScreenTimeout)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                }

                DapVolumeBlock(state, onVolumeChanged, onDigitalGainChanged)

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                DapControlHeader(
                    icon = { Icon(Icons.Rounded.BatteryChargingFull, null) },
                    title = stringResource(R.string.dap_battery_title),
                    value = "${state.batteryPercent}%",
                )
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (state.batteryCharging) R.string.dap_battery_charging
                                else R.string.dap_battery_not_charging
                            )
                        )
                        append(" · ")
                        append(dapBatteryPlugLabel(state.batteryPlug))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Volume for the output in use: Android media volume, or on USB Direct the DAC's own hardware level
 * in dB. In PROCESSED the app's digital level joins it as a second track; on a DAC without a hardware
 * control the digital level is the only one there is. A fixed PRISTINE output has nothing to move and
 * keeps the plain readout.
 *
 * B22: the Pure Direct switch, its status, the "digital volume keeps it off" notice and the "level would
 * rise" dialog are no longer here. They live on the Audio page (Audio, Pure Direct), once, so the same
 * switch is not on two pages.
 */
@Composable
private fun DapVolumeBlock(
    state: DeviceControlUiState,
    onVolumeChanged: (Int) -> Unit,
    onDigitalGainChanged: (Int) -> Unit,
) {
    DapVolumeLevels(state, onVolumeChanged, onDigitalGainChanged)
}

@Composable
private fun DapVolumeLevels(
    state: DeviceControlUiState,
    onVolumeChanged: (Int) -> Unit,
    onDigitalGainChanged: (Int) -> Unit,
) {
    val title = stringResource(R.string.dap_volume_title)
    val detail = dapOutputSignalLine(state)
    val fixedDirect = state.isUsbDirectActive && !state.volumeIsDacHardware
    if (fixedDirect && !state.directProcessed) {
        DapControlHeader(
            icon = { Icon(Icons.Rounded.VolumeUp, null) },
            title = title,
            value = stringResource(R.string.dap_direct_digital_unchanged),
        )
        // B11-3: the digital level is where the lock's effect is plainest: it is not applied, and
        // this row used to say only that it was "unchanged". The switch is right below.
        state.audio.frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME, assumeDirect = true)?.let { notice ->
            Text(
                text = stringResource(notice, stringResource(R.string.pristine_lock_name)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val digital = DapVolumeChannel(
        label = stringResource(R.string.dap_volume_row_digital),
        fraction = state.digitalGainPercent / 100f,
        valueText = "${state.digitalGainPercent}%",
        onChange = { fraction ->
            // AUD-120: the last ~3 % of the track is 100 %, the only level that leaves PRISTINE free.
            val percent = DigitalGainSlider.snapPercent(fraction)
            if (percent != state.digitalGainPercent) onDigitalGainChanged(percent)
        },
        // AUD-120: a double tap on the Digital label or value restores 100 %.
        onDoubleTap = { onDigitalGainChanged(100) },
    )
    if (fixedDirect) {
        DapVolumeControl(title = title, detail = detail, primary = digital, secondary = null)
        return
    }
    val max = state.mediaVolumeMax.coerceAtLeast(1)
    val output = DapVolumeChannel(
        label = stringResource(R.string.dap_volume_row_dac),
        fraction = state.mediaVolume / max.toFloat(),
        valueText = if (state.volumeIsDacHardware) {
            stringResource(R.string.dap_volume_db, state.dacVolumeDecibels?.let(::formatDecibels) ?: "\u2014")
        } else {
            "${state.mediaVolumePercent}%"
        },
        // Only whole steps reach the output; a drag reports many positions per step, and on a DAC
        // each write is a USB control transfer.
        onChange = { fraction ->
            val raw = (fraction * max).roundToInt()
            if (raw != state.mediaVolume) onVolumeChanged(raw)
        },
    )
    DapVolumeControl(
        title = title,
        detail = detail,
        primary = output,
        secondary = digital.takeIf { state.directProcessed },
    )
    if (state.isUsbDirectActive) {
        // AUD-119: the DAC's front-panel buttons change a level the app neither reads nor caps.
        Text(
            text = stringResource(R.string.dap_volume_dac_own_not_reflected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DapControlHeader(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                icon()
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Screen timeout, and only screen timeout.
 *
 * This used to be `DapDisplayControlContent` and also drew a brightness slider under a
 * "Brightness" heading, which made brightness reachable from two places with two owners and no way
 * to say which one the device was reading. Brightness is a live device control, so it belongs to
 * the Settings root's control block, once; a preference page that owns the timeout is the only
 * thing left here.
 */
@Composable
fun DapScreenTimeoutControlContent(
    state: DeviceControlUiState,
    onTimeoutSelected: (Long) -> Unit,
) {
    DapSettingsSection(stringResource(R.string.dap_screen_timeout_title)) {
        // F-05: the system value is "never" (set outside this app: see ScreenTimeoutPolicy). The
        // listener is told and offered the DAP default; nothing is written until they tap it.
        // FIX-2: the list marks the system's REAL value; when the choice stored by the app is not
        // that value, it says so (on the appliance the app puts it back when it is returned to).
        val drift = state.screenTimeoutDrift
        if (drift.drifted) ScreenTimeoutDriftNote(drift)
        if (state.screenTimeoutOffer is ScreenTimeoutPolicy.Offer.CorrectNever) {
            ScreenTimeoutNeverNotice(
                onSetRecommended = { onTimeoutSelected(ScreenTimeoutPolicy.DAP_DEFAULT_MS) },
            )
        }
        timeoutOptions().forEach { (millis, labelRes) ->
            val selected = DapDeviceControlPolicy.isTimeoutOptionSelected(
                storedMode = state.screenTimeoutMode,
                effectiveSystemTimeoutMillis = state.effectiveScreenTimeoutMillis,
                optionMillis = millis,
            )
            Surface(onClick = { onTimeoutSelected(millis) }, color = androidx.compose.ui.graphics.Color.Transparent) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(
            stringResource(R.string.dap_timeout_never_while_playing_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (!state.canWriteSystemSettings && state.screenTimeoutMode != DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING) {
            Text(
                stringResource(R.string.dap_write_settings_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** FIX-2: "30 s" is what was chosen, "never" is what the system has: say both. */
@Composable
private fun ScreenTimeoutDriftNote(drift: ScreenTimeoutPolicy.Drift) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                R.string.dap_timeout_drift_note,
                timeoutValueLabel(drift.systemMs),
                timeoutValueLabel(drift.storedMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        if (drift.action == ScreenTimeoutPolicy.DriftAction.REAPPLY) {
            Text(
                stringResource(R.string.dap_timeout_drift_reapply),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun timeoutValueLabel(ms: Long): String = when {
    ScreenTimeoutPolicy.isNever(ms) -> stringResource(R.string.dap_timeout_value_never)
    ms % 60_000L == 0L -> stringResource(R.string.dap_timeout_value_minutes, (ms / 60_000L).toInt())
    else -> stringResource(R.string.dap_timeout_value_seconds, (ms / 1000L).toInt())
}

/**
 * F-05: says that the system's screen timeout is "never", and offers to put it right.
 *
 * Reporting only. [onSetRecommended] and [onReview] are buttons: the setting is never written
 * because this was shown. (Writing it still needs Android's one-time `WRITE_SETTINGS` grant, which
 * the timeout page asks for itself.)
 */
@Composable
private fun ScreenTimeoutNeverNotice(
    onSetRecommended: (() -> Unit)? = null,
    onReview: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.dap_timeout_system_never_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.dap_timeout_system_never_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm)) {
            if (onSetRecommended != null) {
                TextButton(onClick = onSetRecommended) {
                    Text(stringResource(R.string.dap_timeout_system_never_action))
                }
            }
            if (onReview != null) {
                TextButton(onClick = onReview) {
                    Text(stringResource(R.string.dap_timeout_system_never_review))
                }
            }
        }
    }
}

@Composable
fun DapWirelessControlContent(
    state: DeviceControlUiState,
    onManageWifi: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onManageBluetooth: () -> Unit,
    onWifiEnabledChanged: ((Boolean) -> Unit)? = null,
    onBluetoothEnabledChanged: ((Boolean) -> Unit)? = null,
) {
    val wifiAvailable = state.capabilities[DeviceCapability.WIFI_MANAGER].status !=
        CapabilityStatus.NOT_SUPPORTED
    val bluetoothAvailable = state.capabilities[DeviceCapability.BLUETOOTH_ADAPTER].status !=
        CapabilityStatus.NOT_SUPPORTED
    DapSettingsSection(stringResource(R.string.dap_wireless_wifi_title)) {
        if (!wifiAvailable) {
            DapStatusRow(
                Icons.Rounded.Wifi,
                stringResource(R.string.dap_capability_unavailable),
                "",
            )
        } else if (state.isDeviceOwner && onWifiEnabledChanged != null) {
            SwitchSettingItem(
                title = stringResource(R.string.dap_wireless_wifi_title),
                subtitle = state.wifiName ?: stringResource(
                    if (state.wifiConnected) R.string.dap_wireless_connected else R.string.dap_wireless_not_connected
                ),
                checked = state.wifiRadioEnabled,
                onCheckedChange = onWifiEnabledChanged,
                leadingIcon = { Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        } else {
            DapStatusRow(
                Icons.Rounded.Wifi,
                stringResource(if (state.wifiRadioEnabled) R.string.dap_wireless_on else R.string.dap_wireless_off),
                state.wifiName ?: stringResource(
                    if (state.wifiConnected) R.string.dap_wireless_connected else R.string.dap_wireless_not_connected
                ),
            )
        }
        FilledTonalButton(
            onClick = onManageWifi,
            enabled = wifiAvailable,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(R.string.dap_manage_wifi))
        }
    }
    Spacer(Modifier.height(16.dp))
    DapSettingsSection(stringResource(R.string.dap_wireless_bluetooth_title)) {
        if (!bluetoothAvailable) {
            DapStatusRow(
                Icons.Rounded.Bluetooth,
                stringResource(R.string.dap_capability_unavailable),
                "",
            )
        } else if (state.isDeviceOwner && onBluetoothEnabledChanged != null) {
            SwitchSettingItem(
                title = stringResource(R.string.dap_wireless_bluetooth_title),
                subtitle = state.bluetoothAudioDevice ?: stringResource(R.string.dap_wireless_not_connected),
                checked = state.bluetoothEnabled,
                onCheckedChange = onBluetoothEnabledChanged,
                leadingIcon = { Icon(Icons.Rounded.Bluetooth, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        } else {
            DapStatusRow(
                Icons.Rounded.Bluetooth,
                stringResource(if (state.bluetoothEnabled) R.string.dap_wireless_on else R.string.dap_wireless_off),
                state.bluetoothAudioDevice ?: stringResource(R.string.dap_wireless_not_connected),
            )
        }
        if (bluetoothAvailable && !state.bluetoothEnabled && !state.isDeviceOwner) {
            FilledTonalButton(onClick = onEnableBluetooth, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.dap_enable_bluetooth))
            }
        }
        OutlinedButton(
            onClick = onManageBluetooth,
            enabled = bluetoothAvailable,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.dap_manage_bluetooth))
        }
        Text(
            stringResource(R.string.dap_wireless_platform_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun DapStorageControlContent(
    state: DeviceControlUiState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DapSettingsSection(stringResource(R.string.dap_storage_internal_title)) {
        DapStatusRow(
            Icons.Rounded.Storage,
            stringResource(R.string.dap_storage_internal_title),
            stringResource(
                R.string.dap_storage_free_of_total,
                Formatter.formatFileSize(context, state.storage.freeBytes),
                Formatter.formatFileSize(context, state.storage.totalBytes),
            ),
        )
    }
    Spacer(Modifier.height(16.dp))
    DapSettingsSection(stringResource(R.string.dap_storage_sources_title)) {
        state.storage.mountedSources.forEach { source ->
            val typeLabel = when (source.storageType) {
                StorageType.INTERNAL -> stringResource(R.string.dap_storage_internal)
                StorageType.SD_CARD -> stringResource(R.string.dap_storage_sd)
                StorageType.USB -> stringResource(R.string.dap_storage_usb)
            }
            DapStatusRow(Icons.Rounded.Storage,
                if (source.storageType == StorageType.USB) source.displayName else typeLabel,
                if (source.storageType == StorageType.USB) typeLabel else "",
            )
        }
        if (state.storage.mountedSources.none { it.storageType != StorageType.INTERNAL }) {
            Text(
                stringResource(R.string.dap_storage_no_external),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        Text(
            stringResource(R.string.dap_pssd_future_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DapSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun DapStatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The one-line description of where the audio is going: "SNOWSKY Melody · USB Direct · 24 bits ·
 * 48 kHz", "Cubitt Sound · Bluetooth". Shared by Settings and the DAP lock surface so both say the
 * same thing about the same output.
 */
@Composable
internal fun dapOutputSignalLine(state: DeviceControlUiState): String =
    "${dapOutputPrimaryLabel(state)} · ${dapOutputSecondaryLabel(state)}"

@Composable
private fun dapOutputPrimaryLabel(state: DeviceControlUiState): String = when (state.audioRouteKind) {
    DapAudioRouteKind.USB_DIRECT -> state.audioOutputName ?: stringResource(R.string.dap_output_usb_direct)
    DapAudioRouteKind.BLUETOOTH -> state.audioOutputName ?: stringResource(R.string.dap_output_bluetooth)
    DapAudioRouteKind.USB_ANDROID -> state.audioOutputName ?: stringResource(R.string.dap_output_android_usb)
    DapAudioRouteKind.WIRED -> state.audioOutputName ?: stringResource(R.string.dap_output_wired)
    DapAudioRouteKind.SPEAKER -> stringResource(R.string.dap_output_speaker)
    DapAudioRouteKind.ANDROID_AUDIO -> stringResource(R.string.dap_output_android)
}

@Composable
private fun dapOutputSecondaryLabel(state: DeviceControlUiState): String {
    if (state.usbDirectState == UsbDirectSinkState.USB_DIRECT_ERROR) {
        return stringResource(R.string.dap_direct_failed_short)
    }
    if (state.isUsbDirectActive) {
        val parts = buildList {
            add(stringResource(R.string.dap_output_usb_direct))
            // B30: where Resonance comes from, or that the DAC's EQ could not be read (then neither Purity nor Resonance is said).
            when (state.audio.resonanceSource) {
                ResonanceSource.DAC_EQ -> add(stringResource(R.string.dap_output_resonance_dac))
                ResonanceSource.APP_DSP -> add(stringResource(R.string.dap_output_resonance_app))
                ResonanceSource.BOTH -> add(stringResource(R.string.dap_output_resonance_both))
                null -> if (state.audio.dacEqUnknownOnDirect) add(stringResource(R.string.dap_output_dac_eq_unknown))
            }
            state.usbDirectBitDepth?.let { add(stringResource(R.string.dap_bits, it)) }
            state.usbDirectRate?.let { rate ->
                add(stringResource(R.string.dap_rate_khz, formatKhz(rate)))
            }
        }
        return parts.joinToString(" · ")
    }
    // B16-G5: a DAC is connected but crossfade, speed or an effect keeps playback on Android audio: said, not just "Android".
    if (state.audioRouteKind == DapAudioRouteKind.USB_ANDROID) {
        state.audio.signalBlockerReason?.let { return stringResource(it) }
    }
    return when (state.audioRouteKind) {
        DapAudioRouteKind.BLUETOOTH -> stringResource(R.string.dap_output_bluetooth)
        DapAudioRouteKind.USB_ANDROID -> stringResource(R.string.dap_output_android)
        DapAudioRouteKind.WIRED, DapAudioRouteKind.SPEAKER -> stringResource(R.string.dap_output_android)
        else -> stringResource(R.string.dap_output_no_usb_dac)
    }
}

@Composable
private fun dapBatteryPlugLabel(plug: DapBatteryPlug): String = stringResource(
    when (plug) {
        DapBatteryPlug.NONE -> R.string.dap_battery_plug_none
        DapBatteryPlug.AC -> R.string.dap_battery_plug_ac
        DapBatteryPlug.USB -> R.string.dap_battery_plug_usb
        DapBatteryPlug.WIRELESS -> R.string.dap_battery_plug_wireless
        DapBatteryPlug.OTHER -> R.string.dap_battery_plug_other
    }
)

private fun formatKhz(rate: Int): String = if (rate % 1000 == 0) {
    (rate / 1000).toString()
} else {
    String.format(Locale.US, "%.1f", rate / 1000.0)
}

private fun timeoutOptions(): List<Pair<Long, Int>> = listOf(
    30_000L to R.string.dap_timeout_30_seconds,
    60_000L to R.string.dap_timeout_1_minute,
    120_000L to R.string.dap_timeout_2_minutes,
    300_000L to R.string.dap_timeout_5_minutes,
    600_000L to R.string.dap_timeout_10_minutes,
    DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING to R.string.dap_timeout_never_while_playing,
)

fun applyDapWindowBrightness(context: Context, percent: Int) {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) {
            val attributes = current.window.attributes
            attributes.screenBrightness = percent.coerceIn(5, 100) / 100f
            current.window.attributes = attributes
            return
        }
        current = current.baseContext
    }
}
