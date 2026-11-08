package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqEditCheck
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedPicker
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTonalRoundButton
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqUiState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeout

/**
 * B61 item 4 (owner 11-07): **the DAC EQ's filter editor, numbers only, easy to use.**
 *
 * - Tapping a number (Hz, dB, Q) opens a **decimal keyboard** and takes **the exact value typed** (no snapping to the steps of the arrows); a value outside the profile's limits is refused in the
 *   field with the range shown. The keyboard is `KeyboardType.Decimal` (digits and the point); a "+/−" action beside the gain field puts the sign in whatever the keyboard offers.
 * - Holding an arrow repeats and speeds up ([HoldRepeat]); a single press is one step.
 * - **One write per finished edit.** The editor works on a draft; nothing goes to the DAC while a value is typed or an arrow is held. Done (or dismissing the sheet) hands the finished band over
 *   once, and the screen's usual debounce makes it one write. A curve the gate would refuse is shown as refused here ([DacEqEditCheck]) and is not handed over.
 */
internal object HoldRepeat {
    /** A press is one step at once; the repeating starts after this. */
    const val FIRST_DELAY_MS = 400L
    const val START_INTERVAL_MS = 180L
    const val MIN_INTERVAL_MS = 40L
    private const val DECAY = 0.85

    /** The wait before repeat number [repeat] (0-based): 180 ms, shrinking by 15 % each time, never under 40 ms. */
    fun intervalAfter(repeat: Int): Long = max(MIN_INTERVAL_MS, (START_INTERVAL_MS * DECAY.pow(repeat)).toLong())

    /** How many steps repeat number [repeat] takes: it speeds up, so a long hold crosses the whole range without endless tapping. */
    fun multiplierAfter(repeat: Int): Int = when {
        repeat < 10 -> 1
        repeat < 25 -> 2
        repeat < 45 -> 5
        else -> 10
    }
}

/**
 * Press: one step at once, then, held past [HoldRepeat.FIRST_DELAY_MS], a step every [HoldRepeat.intervalAfter] that grows by [HoldRepeat.multiplierAfter]. The press is taken in the initial pass and
 * consumed, so the button under it does not also click (its own `onClick` stays for assistive technology, which calls it without a pointer).
 */
internal fun Modifier.holdToRepeat(onStep: (multiplier: Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down.consume()
        onStep(1)
        var repeat = 0
        var wait = HoldRepeat.FIRST_DELAY_MS
        while (true) {
            val released = try {
                withTimeout(wait) {
                    var ended = false
                    while (!ended) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        ended = event.changes.all { !it.pressed }
                        event.changes.forEach { it.consume() }
                    }
                }
                true
            } catch (_: PointerEventTimeoutCancellationException) {
                false
            }
            if (released) break
            onStep(HoldRepeat.multiplierAfter(repeat))
            wait = HoldRepeat.intervalAfter(repeat)
            repeat++
        }
    }
}

/** What a typed number is: a value in the DAC's units, or a reason it is not one. */
internal sealed interface DacEqTyped {
    data class Value(val number: Int) : DacEqTyped
    data object Empty : DacEqTyped
    data object NotANumber : DacEqTyped
    data object TooManyDecimals : DacEqTyped
    data object OutOfRange : DacEqTyped
}

/** The three fields' parsing: the exact value typed, in whole hertz, tenths of a dB and hundredths of Q. The comma is read as the point. */
internal object DacEqNumbers {
    private fun decimal(text: String, maxDecimals: Int, scale: Int, min: Int, max: Int): DacEqTyped {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty() || t == "-" || t == "." || t == "-.") return DacEqTyped.Empty
        if (!Regex("-?\\d*\\.?\\d*").matches(t)) return DacEqTyped.NotANumber
        val decimals = t.substringAfter('.', "").length
        if (decimals > maxDecimals) return DacEqTyped.TooManyDecimals
        val value = (t.toBigDecimalOrNull() ?: return DacEqTyped.NotANumber).movePointRight(scale.toString().length - 1)
        val n = value.toBigInteger().toInt()
        return if (n in min..max) DacEqTyped.Value(n) else DacEqTyped.OutOfRange
    }

    fun hz(text: String, min: Int, max: Int): DacEqTyped = decimal(text, 0, 1, min, max)
    fun gainTenths(text: String, min: Int, max: Int): DacEqTyped = decimal(text, 1, 10, min, max)
    fun qHundredths(text: String, min: Int, max: Int): DacEqTyped = decimal(text, 2, 100, min, max)

    /** The plain text a field starts from: the exact number, no unit and no padding. */
    fun hzText(hz: Int): String = hz.toString()
    fun gainText(tenths: Int): String = String.format(Locale.US, "%.1f", tenths / 10.0)
    fun qText(hundredths: Int): String = String.format(Locale.US, "%.2f", hundredths / 100.0)
}

/** What a test reads to know the keyboard a field asked for. */
internal val DacEqFieldKeyboard = SemanticsPropertyKey<String>("DacEqFieldKeyboard")
private var SemanticsPropertyReceiver.dacEqFieldKeyboard by DacEqFieldKeyboard

/** The one keyboard of every number field of the editor: digits and the point. */
internal val DacEqNumberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)

/**
 * One number of the editor: its name, the arrows (hold to repeat), and the value, which is a tap away from being typed. [parse] judges the text; the value is [onValue]'d as soon as it is a
 * valid number in range (the draft follows the typing), and an invalid one shows [rangeText] and changes nothing. [signToggle]: the gain field's "+/−".
 */
@Composable
private fun DacEqNumberRow(
    label: String,
    shown: String,
    startText: String,
    parse: (String) -> DacEqTyped,
    rangeText: String,
    onValue: (Int) -> Unit,
    onLower: (Int) -> Unit,
    onRaise: (Int) -> Unit,
    signToggle: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(startText) }
    var invalid by remember { mutableStateOf(false) }
    var gotFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = DapType.Body, color = Dap.primaryText, modifier = Modifier.weight(1f))
            Box(Modifier.holdToRepeat(onLower)) {
                DapTonalRoundButton(
                    contentDescription = stringResource(R.string.dac_eq_decrease, label),
                    onClick = { onLower(1) },
                    size = 48.dp,
                ) { tint -> Icon(Icons.Rounded.Remove, contentDescription = null, tint = tint) }
            }
            Box(Modifier.width(112.dp), contentAlignment = Alignment.Center) {
                if (!editing) {
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 96.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(DapRadius.Surface))
                            .clickable {
                                text = startText
                                invalid = false
                                gotFocus = false
                                editing = true
                            }
                            .semantics { contentDescription = shown },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(shown, style = DapType.Value, color = Dap.primaryText, maxLines = 1)
                    }
                } else {
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    BasicTextField(
                        value = text,
                        onValueChange = { typed ->
                            text = typed
                            when (val parsed = parse(typed)) {
                                is DacEqTyped.Value -> { invalid = false; onValue(parsed.number) }
                                DacEqTyped.Empty -> invalid = false
                                else -> invalid = true
                            }
                        },
                        singleLine = true,
                        textStyle = DapType.Value.copy(color = if (invalid) Dap.warning else Dap.primaryText),
                        cursorBrush = SolidColor(Dap.primaryText),
                        keyboardOptions = DacEqNumberKeyboard,
                        keyboardActions = KeyboardActions(onDone = {
                            if (!invalid) editing = false
                            keyboard?.hide()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { dacEqFieldKeyboard = "Decimal" }
                            .dapTextField()
                            // leaving the field ends the typing; the first report, before the field has had the focus, is not leaving it
                            .onFocusChanged { if (it.hasFocus) gotFocus = true else if (gotFocus && !invalid) editing = false },
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(DapRadius.Surface))
                                    .background(Dap.surfaceRaised)
                                    .padding(horizontal = DapSpacing.Md, vertical = DapSpacing.Sm),
                                contentAlignment = Alignment.Center,
                            ) { inner() }
                        },
                    )
                }
            }
            Box(Modifier.holdToRepeat(onRaise)) {
                DapTonalRoundButton(
                    contentDescription = stringResource(R.string.dac_eq_increase, label),
                    onClick = { onRaise(1) },
                    size = 48.dp,
                ) { tint -> Icon(Icons.Rounded.Add, contentDescription = null, tint = tint) }
            }
        }
        if (editing && signToggle) {
            DapTextAction(
                label = stringResource(R.string.dac_eq_sign_toggle),
                onClick = {
                    text = if (text.startsWith("-")) text.removePrefix("-") else "-$text"
                    when (val parsed = parse(text)) {
                        is DacEqTyped.Value -> { invalid = false; onValue(parsed.number) }
                        DacEqTyped.Empty -> invalid = false
                        else -> invalid = true
                    }
                },
                color = LocalDapAccent.current,
                modifier = Modifier.align(Alignment.End),
            )
        }
        if (invalid) {
            Text(rangeText, style = DapType.Caption, color = Dap.warning, modifier = Modifier.padding(top = DapSpacing.Xs))
        }
    }
}

/** One filter's editor: its type, and a frequency, a gain and a Q, each typed or stepped, on a draft; Done hands the finished band over once. Limits are the DAC's. */
@Composable
internal fun DacEqFilterEditorContent(
    band: DacEqBand,
    capability: DacEqCapability?,
    onChange: ((DacEqBand) -> DacEqBand) -> Unit,
    onDismiss: () -> Unit,
    /** The whole curve this band is part of (the bands as the screen has them): the editor asks the gate's own level rule of the curve with the draft in it. */
    curve: List<DacEqBand> = emptyList(),
    sense: PreGainSense = PreGainSense.BOOST,
    /** Told every time the draft changes, with whether the gate would take it: the sheet hands the finished band over when it is dismissed by a swipe. */
    onDraft: (DacEqBand, Boolean) -> Unit = { _, _ -> },
) {
    val minGain = ((capability?.minGainDb ?: -12.0) * 10).roundToInt()
    val maxGain = ((capability?.maxGainDb ?: 12.0) * 10).roundToInt()
    val minHz = capability?.minFrequencyHz ?: 20
    val maxHz = capability?.maxFrequencyHz ?: 20_000
    val minQ = ((capability?.minQ ?: 0.1) * 100).roundToInt()
    val maxQ = ((capability?.maxQ ?: 10.0) * 100).roundToInt()
    val types = dacEqUiTypes(band.type)
    val typeLabels = types.associateWith { stringResource(typeNameRes(it)) }
    var draft by remember(band.index) { mutableStateOf(band) }
    val candidate = remember(draft, curve) { if (curve.isEmpty()) listOf(draft) else curve.map { if (it.index == draft.index) draft else it } }
    val verdict = remember(candidate, sense) { DacEqEditCheck.verdict(candidate, sense) }
    val refused = verdict is DacEqEditCheck.Verdict.Refused
    fun update(change: (DacEqBand) -> DacEqBand) {
        draft = change(draft).copy(index = band.index)
        val next = if (curve.isEmpty()) listOf(draft) else curve.map { if (it.index == draft.index) draft else it }
        onDraft(draft, DacEqEditCheck.verdict(next, sense) is DacEqEditCheck.Verdict.Ok)
    }
    fun finish() {
        // one hand-over per finished edit, and only of a band the gate would take
        if (draft != band && !refused) onChange { draft }
        onDismiss()
    }
    val hzRange = stringResource(R.string.dac_eq_range_hz, frequencyText(minHz), frequencyText(maxHz))
    val dbRange = stringResource(R.string.dac_eq_range_db, formatGain(minGain), formatGain(maxGain))
    val qRange = stringResource(R.string.dac_eq_range_q, formatQ(minQ), formatQ(maxQ))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter)
            .padding(bottom = DapSpacing.SectionGap),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Md),
    ) {
        Text(stringResource(R.string.dac_eq_filter_title, band.index + 1), style = DapType.Body, color = Dap.primaryText)
        Text(stringResource(R.string.dac_eq_field_type), style = DapType.Caption, color = Dap.tertiaryText)
        DapSegmentedPicker(
            options = types,
            selected = draft.type ?: FiioFilterType.PEAK,
            onSelect = { type -> update { it.copy(typeCode = type.code) } },
            label = { typeLabels.getValue(it) },
            fillWidth = true,
        )
        DacEqNumberRow(
            label = stringResource(R.string.dac_eq_field_frequency),
            shown = frequencyText(draft.frequencyHz),
            startText = DacEqNumbers.hzText(draft.frequencyHz),
            parse = { DacEqNumbers.hz(it, minHz, maxHz) },
            rangeText = hzRange,
            onValue = { hz -> update { it.copy(frequencyHz = hz) } },
            onLower = { m -> update { b -> b.copy(frequencyHz = (1..m).fold(b.frequencyHz) { hz, _ -> stepFrequency(hz, -1) }.coerceIn(minHz, maxHz)) } },
            onRaise = { m -> update { b -> b.copy(frequencyHz = (1..m).fold(b.frequencyHz) { hz, _ -> stepFrequency(hz, +1) }.coerceIn(minHz, maxHz)) } },
        )
        DacEqNumberRow(
            label = stringResource(R.string.dac_eq_field_gain),
            shown = "${formatGain(draft.gainTenthsDb)} dB",
            startText = DacEqNumbers.gainText(draft.gainTenthsDb),
            parse = { DacEqNumbers.gainTenths(it, minGain, maxGain) },
            rangeText = dbRange,
            onValue = { tenths -> update { it.copy(gainTenthsDb = tenths) } },
            onLower = { m -> update { it.copy(gainTenthsDb = (it.gainTenthsDb - GAIN_STEP_TENTHS * m).coerceIn(minGain, maxGain)) } },
            onRaise = { m -> update { it.copy(gainTenthsDb = (it.gainTenthsDb + GAIN_STEP_TENTHS * m).coerceIn(minGain, maxGain)) } },
            signToggle = true,
        )
        DacEqNumberRow(
            label = stringResource(R.string.dac_eq_field_q),
            shown = formatQ(draft.qHundredths),
            startText = DacEqNumbers.qText(draft.qHundredths),
            parse = { DacEqNumbers.qHundredths(it, minQ, maxQ) },
            rangeText = qRange,
            onValue = { q -> update { it.copy(qHundredths = q) } },
            onLower = { m -> update { b -> b.copy(qHundredths = (1..m).fold(b.qHundredths) { q, _ -> stepQ(q, -1) }.coerceIn(minQ, maxQ)) } },
            onRaise = { m -> update { b -> b.copy(qHundredths = (1..m).fold(b.qHundredths) { q, _ -> stepQ(q, +1) }.coerceIn(minQ, maxQ)) } },
        )
        (verdict as? DacEqEditCheck.Verdict.Refused)?.let {
            Text(
                // the number is formatted by the string resource itself (%.1f), in the locale the resources were made for: no read of the default locale in a composable
                text = stringResource(R.string.dac_eq_edit_refused, it.peakBoostDb),
                style = DapType.Caption,
                color = Dap.warning,
                modifier = Modifier.semantics { contentDescription = "refused" },
            )
        }
        // the ds primary button: a filled 44 dp action, the same size and contrast as the app's other main actions (it was a bare accent text)
        DapPrimaryButton(
            label = stringResource(if (refused) R.string.dac_eq_discard else R.string.dac_eq_done),
            onClick = ::finish,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val GAIN_STEP_TENTHS = 5

private fun dacEqUiTypes(current: FiioFilterType?): List<FiioFilterType> =
    if (current == null || current in DacEqUiState.offeredTypes) DacEqUiState.offeredTypes else DacEqUiState.offeredTypes + current
