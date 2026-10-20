package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.*

val predefinedTimes = listOf(5, 10, 15, 20, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerOptionsBottomSheet(
    onPlayCounter: (Int) -> Unit,
    activeTimerValueDisplay: String?,
    activeTimerDurationMinutes: Int?,
    playCount: Float,
    isEndOfTrackTimerActive: Boolean,
    onDismiss: () -> Unit,
    onCancelCountedPlay: () -> Unit,
    onSetPredefinedTimer: (Int) -> Unit,
    onSetEndOfTrackTimer: (Boolean) -> Unit,
    onOpenCustomTimePicker: () -> Unit,
    onCancelTimer: () -> Unit,
) {
    // Read active state once on opening. Ticks and service changes must never overwrite a draft.
    var mode by rememberSaveable { mutableStateOf(when {
        isEndOfTrackTimerActive -> "end"
        activeTimerDurationMinutes != null -> if (activeTimerDurationMinutes in predefinedTimes) "duration" else "custom"
        playCount > 0 -> "repeat"
        else -> "off"
    }) }
    var selectedMinutes by rememberSaveable { mutableIntStateOf(activeTimerDurationMinutes ?: 15) }
    var hours by rememberSaveable { mutableStateOf(((activeTimerDurationMinutes ?: 30) / 60).toString()) }
    var minutes by rememberSaveable { mutableStateOf(((activeTimerDurationMinutes ?: 30) % 60).toString()) }
    var repetitions by rememberSaveable { mutableIntStateOf(playCount.toInt().coerceIn(1, 10)) }
    val customMinutes = customTimerMinutes(hours, minutes)
    val selection = when (mode) {
        "duration" -> SleepTimerSelection.Duration(selectedMinutes)
        "custom" -> customMinutes?.let(SleepTimerSelection::Duration)
        "end" -> SleepTimerSelection.EndOfTrack
        "repeat" -> SleepTimerSelection.RepeatCurrent(repetitions)
        else -> SleepTimerSelection.Off
    }
    val activeLabel = when {
        isEndOfTrackTimerActive -> stringResource(R.string.timer_end_track)
        activeTimerValueDisplay != null -> activeTimerValueDisplay
        playCount > 0 -> pluralStringResource(R.plurals.timer_repeat_value, playCount.toInt(), playCount.toInt())
        else -> stringResource(R.string.timer_off)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = DapRadius.SheetTop, topEnd = DapRadius.SheetTop)) {
        ModalInputBridge()
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = DapSpacing.Gutter)
            .heightIn(max = 640.dp)) {
            Text(stringResource(R.string.sleep_timer_ui_title), style = DapType.Heading)
            Text(stringResource(R.string.timer_active, activeLabel), style = DapType.Note,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerChoice(stringResource(R.string.timer_off), mode == "off") { mode = "off" }
                Text(stringResource(R.string.timer_durations), style = DapType.Caption,
                    modifier = Modifier.padding(top = 8.dp))
                val columns = if (LocalDensity.current.fontScale > 1.2f) 2 else 3
                predefinedTimes.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { duration ->
                            OutlinedButton(onClick = { selectedMinutes = duration; mode = "duration" },
                                shape = RoundedCornerShape(DapRadius.Surface),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                    selected = mode == "duration" && selectedMinutes == duration
                                }, colors = ButtonDefaults.outlinedButtonColors(containerColor =
                                    if (mode == "duration" && selectedMinutes == duration) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface)) {
                                Text(stringResource(R.string.dap_timeout_value_minutes, duration), style = DapType.ValueSmall)
                            }
                        }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                TimerChoice(stringResource(R.string.timer_custom), mode == "custom") { mode = "custom" }
                if (mode == "custom") {
                    Text(stringResource(R.string.timer_interval_hint), style = DapType.Note)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = hours, onValueChange = { hours = it }, singleLine = true,
                            label = { Text(stringResource(R.string.timer_hours), style = DapType.Caption) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = dapDismissKeyboardActions(),
                            textStyle = DapType.Value, shape = RoundedCornerShape(DapRadius.Surface),
                            isError = customMinutes == null, modifier = Modifier.weight(1f).dapTextField())
                        OutlinedTextField(value = minutes, onValueChange = { minutes = it }, singleLine = true,
                            label = { Text(stringResource(R.string.timer_minutes), style = DapType.Caption) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = dapDismissKeyboardActions(),
                            textStyle = DapType.Value, shape = RoundedCornerShape(DapRadius.Surface),
                            isError = customMinutes == null, modifier = Modifier.weight(1f).dapTextField())
                    }
                    if (customMinutes == null) Text(stringResource(R.string.timer_interval_error),
                        color = MaterialTheme.colorScheme.error, style = DapType.Note)
                }
                TimerChoice(stringResource(R.string.timer_end_track), mode == "end") { mode = "end" }
                TimerChoice(stringResource(R.string.timer_repeat_current), mode == "repeat") { mode = "repeat" }
                if (mode == "repeat") {
                    Text(stringResource(R.string.timer_repeat_hint), style = DapType.Note)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TimerCountButton("−", stringResource(R.string.timer_repeat_less), repetitions > 1) { repetitions-- }
                        Text(pluralStringResource(R.plurals.timer_repeat_value, repetitions, repetitions), Modifier.weight(1f),
                            style = DapType.Value, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TimerCountButton("+", stringResource(R.string.timer_repeat_more), repetitions < 10) { repetitions++ }
                    }
                }
                Text(stringResource(R.string.timer_pending_hint), style = DapType.Note,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(DapRadius.Surface)) { Text(stringResource(R.string.timer_close), style = DapType.Button) }
                Button(onClick = {
                    // All old modes are cancelled only here, after validating the complete draft.
                    val confirmed = selection ?: return@Button
                    onCancelCountedPlay(); onCancelTimer()
                    when (confirmed) {
                        SleepTimerSelection.Off -> Unit
                        is SleepTimerSelection.Duration -> onSetPredefinedTimer(confirmed.minutes)
                        SleepTimerSelection.EndOfTrack -> onSetEndOfTrackTimer(true)
                        is SleepTimerSelection.RepeatCurrent -> onPlayCounter(confirmed.count)
                    }
                    onDismiss()
                }, enabled = selection != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(DapRadius.Surface)) {
                    Text(stringResource(if (mode == "off") R.string.timer_confirm_off else R.string.timer_start), style = DapType.Button)
                }
            }
        }
    }
}

@Composable
private fun TimerChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), style = DapType.RowSmall)
    }
}

@Composable
private fun TimerCountButton(glyph: String, name: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, shape = RoundedCornerShape(DapRadius.Surface),
        modifier = Modifier.size(48.dp).clearAndSetSemantics {
            contentDescription = name; role = Role.Button
            if (enabled) onClick { onClick(); true } else disabled()
        }, contentPadding = PaddingValues(0.dp)) { Text(glyph, style = DapType.Heading) }
}
