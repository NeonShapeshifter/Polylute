package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.model.TransitionMode
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.presentation.components.AndroidRouteHintLine
import com.lostf1sh.pixelplayeross.presentation.components.FeatureStatusLine
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.presentation.model.labelRes
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacLiveStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatusKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.androidRouteHint
import com.lostf1sh.pixelplayeross.presentation.viewmodel.crossfadeStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.equalizerStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.gaplessStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.replayGainStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.safetyMaxOutputState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.safetyMaxStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.speedStatus
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapRuledSection
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSlider
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapStatusLine
import com.lostf1sh.pixelplayeross.ui.ds.DapStatusTone
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitch
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.FrozenAlpha
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * B22: the Audio page, as `Audio.dc.html` draws it: five sections (Output, Pure Direct, Sound, Transitions, Queue) and
 * one status line on every row that has something to say about what reaches the signal.
 *
 * Everything the page says about that comes from [AudioStatus] through `AudioFeatureStatus`: the page has no sentence
 * of its own about the lock. The output line is not here (it lives on the lock screen, Details and the Settings root).
 */

/** The speeds the slider offers: 0.5× to 2× in quarter steps (what it has always offered). */
internal val PlaybackSpeedSteps: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** The slider is a fraction of the way along the speed range; the nearest speed step answers. */
internal fun speedForFraction(fraction: Float): Float {
    val index = (fraction.coerceIn(0f, 1f) * (PlaybackSpeedSteps.size - 1)).roundToInt()
    return PlaybackSpeedSteps[index]
}

internal fun fractionForSpeed(speed: Float): Float {
    val nearest = PlaybackSpeedSteps.indices.minByOrNull { kotlin.math.abs(PlaybackSpeedSteps[it] - speed) } ?: 0
    return nearest / (PlaybackSpeedSteps.size - 1).toFloat()
}

/** What the Pure Direct section needs besides the switch: the digital level that holds it off, and a rise to confirm. */
@Immutable
data class PureDirectPanel(
    val digitalGainPercent: Int = 100,
    /** The digital level alone keeps Direct off PRISTINE (USB Direct on, level below 100 %, lock off). */
    val digitalBlocksPristine: Boolean = false,
    /** Turning the lock on would raise the level by this many dB and needs the listener's say-so. */
    val riseToConfirmDb: Float? = null,
    val riseReason: PristineModePolicy.RiseReason? = null,
) {
    companion object {
        fun of(device: DeviceControlUiState) = PureDirectPanel(
            digitalGainPercent = device.digitalGainPercent,
            digitalBlocksPristine = device.digitalBlocksPristine,
            riseToConfirmDb = device.pristineRiseToConfirm,
            riseReason = device.pristineRiseReason,
        )
    }
}

/** The equalizer row: the saved mode and preset of the headphones in use, and what the output does with them. */
@Immutable
data class EqualizerRowState(
    val profileId: String,
    val profileName: String,
    val enabled: Boolean,
    val mode: HeadphoneEqMode,
    val presetLabel: String,
    val status: FeatureStatus,
)

/** The Safety Max row: the headphones it belongs to, the saved step, and the output it is read for. */
@Immutable
data class SafetyMaxRowState(
    val profileId: String,
    val profileName: String,
    val ceilingRaw: Int?,
    val status: FeatureStatus,
)

/** What the crossfade row says about the global rule: how long, and whether it follows the songs' tempo. */
@Immutable
data class CrossfadeSummary(val durationMs: Int, val mode: TransitionMode)

/** What the Audio page draws: the preferences it edits, the devices it lists and what is applied right now ([audio]). */
@Immutable
data class AudioSettingsState(
    val currentDac: DacProfileUiItem?,
    val selectedHeadphone: HeadphoneProfileUiItem?,
    /** Where the sound goes when no DAC is connected (the row that says so). */
    val outputKind: DapAudioRouteKind,
    val pureDirect: PureDirectPanel,
    val equalizer: EqualizerRowState?,
    val safetyMax: SafetyMaxRowState?,
    val replayGainEnabled: Boolean,
    val replayGainUseAlbumGain: Boolean,
    val isCrossfadeEnabled: Boolean,
    val smartCrossfadeEnabled: Boolean,
    val crossfade: CrossfadeSummary?,
    val playbackSpeed: Float,
    val randomMode: RandomMode,
    val persistentShuffleEnabled: Boolean,
    val audio: AudioStatus,
) {
    companion object {
        fun from(
            ui: SettingsUiState,
            playbackSpeed: Float,
            audio: AudioStatus,
            dacs: DacProfilesUiState,
            headphones: HeadphoneProfilesUiState,
            device: DeviceControlUiState = DeviceControlUiState(),
            volume: SafetyVolumeState = SafetyVolumeState(),
            transition: TransitionSettings? = null,
        ): AudioSettingsState {
            val selected = headphones.selected
            return AudioSettingsState(
                currentDac = dacs.current,
                selectedHeadphone = selected,
                outputKind = device.audioRouteKind,
                pureDirect = PureDirectPanel.of(device),
                equalizer = selected?.let { item ->
                    val eq = item.profile.equalizer
                    EqualizerRowState(
                        profileId = item.profile.profileId,
                        profileName = item.profile.displayName,
                        enabled = eq.enabled,
                        mode = eq.mode,
                        presetLabel = presetLabel(eq.mode, eq.presetName),
                        status = audio.equalizerStatus(isProfileSelected = true, enabled = eq.enabled),
                    )
                },
                safetyMax = selected?.let { item ->
                    SafetyMaxRowState(
                        profileId = item.profile.profileId,
                        profileName = item.profile.displayName,
                        ceilingRaw = item.profile.safetyMax
                            .calibrationFor(volume.backend.outputContextId, volume.backend.kind)?.ceilingRaw,
                        status = safetyMaxStatus(safetyMaxOutputState(item.profile, volume)),
                    )
                },
                replayGainEnabled = ui.replayGainEnabled,
                replayGainUseAlbumGain = ui.replayGainUseAlbumGain,
                isCrossfadeEnabled = ui.isCrossfadeEnabled,
                smartCrossfadeEnabled = ui.smartCrossfadeEnabled,
                crossfade = transition?.let { CrossfadeSummary(it.durationMs, it.mode) },
                playbackSpeed = playbackSpeed,
                randomMode = ui.randomMode,
                persistentShuffleEnabled = ui.persistentShuffleEnabled,
                audio = audio,
            )
        }

        /** The preset the saved mode points at: a 15-band curve is always a custom one, a 10-band one has a name. */
        private fun presetLabel(mode: HeadphoneEqMode, presetName: String): String =
            if (mode == HeadphoneEqMode.GRAPHIC_15) {
                EqualizerPreset.custom(emptyList()).displayName
            } else {
                EqualizerPreset.fromName(presetName).displayName
            }
    }
}

/** Everything the Audio page can do. */
interface AudioSettingsActions {
    /** The Pure Direct switch. */
    fun onPureDirectChange(value: Boolean)
    /** The "level would rise" dialog: go on anyway / leave it off. */
    fun onConfirmPureDirectRise()
    fun onDismissPureDirectRise()
    /** "Restablecer a 100 %": the digital level goes back to unity. */
    fun onResetDigitalGain()
    fun onReplayGainEnabledChange(value: Boolean)
    fun onReplayGainUseAlbumGainChange(value: Boolean)
    fun onCrossfadeEnabledChange(value: Boolean)
    fun onSmartCrossfadeEnabledChange(value: Boolean)
    fun onPlaybackSpeedChange(value: Float)
    fun onRandomModeChange(value: RandomMode)
    fun onPersistentShuffleEnabledChange(value: Boolean)
    fun onOpenDacProfile(profileId: String)
    fun onOpenKnownDacs()
    fun onOpenHeadphoneProfile(profileId: String)
    fun onOpenKnownHeadphones()
    /** The equalizer of the headphones in use, one tap from here. */
    fun onOpenEqualizer(profileId: String)
    fun onOpenSafetyMax(profileId: String)
    /** The global transition rules (duration, curves). */
    fun onOpenGlobalTransitionEditor()
}

@Composable
fun AudioSettingsScreen(
    state: AudioSettingsState,
    actions: AudioSettingsActions,
    /** See [PureDirectRiseDialog]: only a JVM render turns it off. */
    mountInputBridge: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        OutputSection(state, actions)
        PureDirectSection(state, actions)
        SoundSection(state, actions)
        TransitionsSection(state, actions)
        QueueSection(state, actions)
    }
    PureDirectRiseDialog(
        riseDb = state.pureDirect.riseToConfirmDb,
        reason = state.pureDirect.riseReason,
        onConfirm = actions::onConfirmPureDirectRise,
        onDismiss = actions::onDismissPureDirectRise,
        mountInputBridge = mountInputBridge,
    )
}

// ── Output ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OutputSection(state: AudioSettingsState, actions: AudioSettingsActions) {
    DapRuledSection(title = stringResource(R.string.audio_section_output)) {
        val current = state.currentDac
        if (current == null) {
            // No profile to open: the row says where the sound goes instead, and has no chevron.
            DapNavRow(
                label = stringResource(R.string.dac_no_connected),
                description = stringResource(outputSentence(state.outputKind)),
            )
        } else {
            val status = stringResource(
                if (current.status == DacLiveStatus.ACTIVE) R.string.dac_status_active else R.string.dac_status_connected
            )
            val maker = current.profile.detectedManufacturer?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(current.profile.displayName, ignoreCase = true) }
            DapNavRow(
                label = current.profile.displayName,
                description = if (maker != null) stringResource(R.string.audio_row_join, status, maker) else status,
                onClick = { actions.onOpenDacProfile(current.profile.profileId) },
            )
        }
        DapNavRow(
            label = stringResource(R.string.dac_more),
            onClick = { actions.onOpenKnownDacs() },
        )
        val headphone = state.selectedHeadphone
        if (headphone == null) {
            DapNavRow(label = stringResource(R.string.headphone_no_selected))
        } else {
            DapNavRow(
                label = headphone.profile.displayName,
                description = headphone.associatedDacName?.let {
                    stringResource(R.string.headphone_status_selected_for, it)
                } ?: stringResource(R.string.headphone_status_selected),
                onClick = { actions.onOpenHeadphoneProfile(headphone.profile.profileId) },
            )
        }
        DapNavRow(
            label = stringResource(R.string.headphone_more),
            onClick = { actions.onOpenKnownHeadphones() },
        )
    }
}

/** "Salida: altavoz del equipo": where the sound goes when there is no DAC. */
internal fun outputSentence(kind: DapAudioRouteKind): Int = when (kind) {
    DapAudioRouteKind.SPEAKER -> R.string.audio_output_speaker
    DapAudioRouteKind.WIRED -> R.string.audio_output_wired
    DapAudioRouteKind.BLUETOOTH -> R.string.audio_output_bluetooth
    DapAudioRouteKind.USB_DIRECT, DapAudioRouteKind.USB_ANDROID, DapAudioRouteKind.ANDROID_AUDIO ->
        R.string.audio_output_android
}

// ── Pure Direct ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PureDirectSection(state: AudioSettingsState, actions: AudioSettingsActions) {
    DapRuledSection(title = stringResource(R.string.audio_section_pure)) {
        val line = state.audio.purityLine
        val status = stringResource(line.statusRes)
        val sentence = if (line.reasonRes != null) {
            stringResource(R.string.pristine_status_with_reason, status, stringResource(line.reasonRes))
        } else {
            status
        }
        DapSwitchRow(
            label = stringResource(R.string.pristine_lock_name),
            description = sentence,
            checked = state.audio.purityPreference,
            onCheckedChange = actions::onPureDirectChange,
        )
        if (state.pureDirect.digitalBlocksPristine) {
            Column(Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm)) {
                Text(
                    text = stringResource(R.string.audio_digital_warning, state.pureDirect.digitalGainPercent),
                    style = DapType.Body,
                    color = Dap.secondaryText,
                )
                DapTextAction(
                    label = stringResource(R.string.digital_reset_full),
                    onClick = actions::onResetDigitalGain,
                    color = LocalDapAccent.current,
                    // The action's own padding must not push its word off the row's left edge.
                    modifier = Modifier.offset(x = -DapSpacing.Xs),
                )
            }
            DapDivider()
        }
    }
}

// ── Sound ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SoundSection(state: AudioSettingsState, actions: AudioSettingsActions) {
    DapRuledSection(title = stringResource(R.string.audio_section_sound)) {
        val eq = state.equalizer
        if (eq == null) {
            DapNavRow(
                label = stringResource(R.string.audio_eq_title),
                description = stringResource(R.string.audio_eq_no_headphones),
                enabled = false,
            )
        } else {
            val modeLabel = stringResource(
                when {
                    !eq.enabled -> R.string.headphone_eq_mode_off
                    eq.mode == HeadphoneEqMode.GRAPHIC_15 -> R.string.headphone_eq_mode_15
                    else -> R.string.headphone_eq_mode_10
                }
            )
            val saved = eq.enabled && eq.status.kind != FeatureStatusKind.APPLIED
            val detail = if (eq.enabled) stringResource(R.string.audio_row_join, modeLabel, eq.presetLabel) else modeLabel
            DapNavRow(
                label = stringResource(R.string.audio_eq_title),
                description = stringResource(
                    if (saved) R.string.audio_eq_sub_saved else R.string.audio_eq_sub,
                    eq.profileName,
                    detail,
                ),
                onClick = { actions.onOpenEqualizer(eq.profileId) },
                extra = { FeatureStatusLine(eq.status) },
            )
        }

        val rg = state.audio.replayGainStatus(state.replayGainEnabled)
        val rgFrozen = rg.kind == FeatureStatusKind.FROZEN
        DapSwitchRow(
            label = stringResource(R.string.audio_replaygain_title),
            description = stringResource(R.string.audio_replaygain_sub),
            checked = state.replayGainEnabled,
            onCheckedChange = actions::onReplayGainEnabledChange,
            extra = { FeatureStatusLine(rg) },
            // Frozen is not disabled: the choice is kept and can be changed, it just does not reach the signal.
            dim = rgFrozen,
        )
        if (state.replayGainEnabled) {
            // A normal row: its whole label, the same type as the rows around it. When the lock freezes ReplayGain it
            // follows the same frozen rule as the switch above (kept, 40 %), and it stays enabled.
            val trackLabel = stringResource(R.string.setcat_gain_mode_track)
            val albumLabel = stringResource(R.string.setcat_gain_mode_album)
            DapSegmentedRow(
                label = stringResource(R.string.audio_gain_of),
                options = listOf(false, true),
                selected = state.replayGainUseAlbumGain,
                onSelect = actions::onReplayGainUseAlbumGainChange,
                optionLabel = { useAlbum -> if (useAlbum) albumLabel else trackLabel },
                dim = rgFrozen,
            )
        }

        val safety = state.safetyMax
        if (safety != null) {
            val step = safety.ceilingRaw?.let { stringResource(R.string.headphone_safety_ceiling_value, it) }
            val where = stringResource(routeNameRes(state.outputKind))
            DapNavRow(
                label = stringResource(R.string.safety_max_title),
                description = if (step != null) {
                    stringResource(R.string.audio_safety_sub, safety.profileName, step, where)
                } else {
                    safety.profileName
                },
                onClick = { actions.onOpenSafetyMax(safety.profileId) },
                extra = { FeatureStatusLine(safety.status) },
            )
        }
    }
}

/** The name of the output a step is read for: "USB Direct", "Altavoz", "Bluetooth"… */
internal fun routeNameRes(kind: DapAudioRouteKind): Int = when (kind) {
    DapAudioRouteKind.USB_DIRECT -> R.string.dap_output_usb_direct
    DapAudioRouteKind.USB_ANDROID -> R.string.dap_output_android_usb
    DapAudioRouteKind.BLUETOOTH -> R.string.dap_output_bluetooth
    DapAudioRouteKind.WIRED -> R.string.dap_output_wired
    DapAudioRouteKind.SPEAKER -> R.string.dap_output_speaker
    DapAudioRouteKind.ANDROID_AUDIO -> R.string.dap_output_android
}

// ── Transitions ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TransitionsSection(state: AudioSettingsState, actions: AudioSettingsActions) {
    DapRuledSection(title = stringResource(R.string.audio_section_transitions)) {
        val audio = state.audio
        val xf = audio.crossfadeStatus(state.isCrossfadeEnabled)
        val frozen = audio.purityInForce
        val xfHint = audio.androidRouteHint(PristineFrozenFeature.CROSSFADE, featureInUse = state.isCrossfadeEnabled)
        DapNavRow(
            label = stringResource(R.string.setcat_crossfade_label),
            description = crossfadeSummary(state),
            onClick = { actions.onOpenGlobalTransitionEditor() },
            // The one switch of the page for crossfade stays: the editor holds the rules (duration, curves), and the
            // saved on/off is what playback checks first.
            trailing = {
                DapSwitch(
                    checked = state.isCrossfadeEnabled,
                    onCheckedChange = { actions.onCrossfadeEnabledChange(it) },
                    enabled = !frozen,
                )
            },
            extra = {
                // "Desactivado" is already the row's own sentence; only a status that adds something is drawn.
                if (xf.kind != FeatureStatusKind.OFF) FeatureStatusLine(xf)
                if (xfHint != null) {
                    Spacer(Modifier.height(DapSpacing.Xs))
                    AndroidRouteHintLine(stringResource(xfHint))
                }
            },
        )
        if (state.isCrossfadeEnabled && !frozen) {
            DapSwitchRow(
                label = stringResource(R.string.setcat_smart_crossfade_title),
                description = stringResource(R.string.setcat_smart_crossfade_subtitle),
                checked = state.smartCrossfadeEnabled,
                onCheckedChange = actions::onSmartCrossfadeEnabledChange,
            )
        }

        SpeedRow(state, actions)

        val gapless = audio.gaplessStatus(state.isCrossfadeEnabled)
        DapNavRow(
            label = stringResource(R.string.audio_gapless_title),
            description = stringResource(R.string.audio_gapless_sub),
            trailing = {
                DapStatusLine(
                    text = stringResource(gapless.textRes),
                    tone = if (gapless.applied) DapStatusTone.Applied else DapStatusTone.Plain,
                )
            },
        )
    }
}

@Composable
private fun crossfadeSummary(state: AudioSettingsState): String {
    if (!state.isCrossfadeEnabled) return stringResource(R.string.audio_crossfade_off)
    val summary = state.crossfade ?: return stringResource(R.string.audio_crossfade_on)
    if (summary.mode == TransitionMode.NONE) return stringResource(R.string.audio_crossfade_style_none)
    val seconds = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }
        .format(summary.durationMs / 1000.0)
    val duration = stringResource(R.string.audio_crossfade_seconds, seconds)
    return if (state.smartCrossfadeEnabled) {
        stringResource(R.string.audio_row_join, duration, stringResource(R.string.audio_smart_crossfade_name))
    } else {
        duration
    }
}

/**
 * Playback speed: the label and its status, the value at the end, the track under them. With the lock in force the
 * control cannot be changed: the track is drawn at 40 % and does not answer, and the status says why.
 */
@Composable
private fun SpeedRow(state: AudioSettingsState, actions: AudioSettingsActions) {
    val audio = state.audio
    val frozen = audio.purityInForce
    val status = audio.speedStatus(state.playbackSpeed)
    val hint = audio.androidRouteHint(PristineFrozenFeature.SPEED, featureInUse = state.playbackSpeed != 1.0f)
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.audio_speed_title),
                    style = DapType.SettingLabel,
                    color = if (frozen) Dap.tertiaryText else Dap.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status != null) {
                    Spacer(Modifier.height(DapSpacing.Xs))
                    FeatureStatusLine(status)
                }
            }
            Spacer(Modifier.width(DapSpacing.Md))
            Text(
                text = String.format(locale, "%.2f×", state.playbackSpeed),
                style = DapType.Value,
                color = if (frozen) Dap.tertiaryText else Dap.primaryText,
                maxLines = 1,
            )
        }
        FrozenAlphaBox(frozen) {
            DapSlider(
                value = fractionForSpeed(state.playbackSpeed),
                onValueChange = { actions.onPlaybackSpeedChange(speedForFraction(it)) },
                enabled = !frozen,
            )
        }
        if (hint != null) {
            AndroidRouteHintLine(stringResource(hint))
            Spacer(Modifier.height(DapSpacing.Sm))
        }
        DapDivider()
    }
}

/** Draws [content] at the frozen alpha when [frozen]. */
@Composable
private fun FrozenAlphaBox(frozen: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (frozen) Modifier.alpha(FrozenAlpha) else Modifier)) { content() }
}

// ── Queue ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun QueueSection(state: AudioSettingsState, actions: AudioSettingsActions) {
    DapRuledSection(title = stringResource(R.string.audio_section_queue)) {
        // Random mode and the persistent mix live here (B22): the same preferences and the same actions as before.
        val randomLabels = RandomMode.entries.associateWith { stringResource(it.labelRes) }
        DapSegmentedRow(
            label = stringResource(R.string.setcat_random_mode_title),
            options = RandomMode.entries.toList(),
            selected = state.randomMode,
            onSelect = actions::onRandomModeChange,
            optionLabel = { randomLabels.getValue(it) },
        )
        DapSwitchRow(
            label = stringResource(R.string.audio_persistent_mix_title),
            description = stringResource(R.string.audio_persistent_mix_sub),
            checked = state.persistentShuffleEnabled,
            onCheckedChange = actions::onPersistentShuffleEnabledChange,
        )
    }
}
