package com.lostf1sh.pixelplayeross.presentation.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus

/** What the Pure Direct lock freezes. */
enum class PristineFrozenFeature { EQ, REPLAY_GAIN, DIGITAL_VOLUME, CROSSFADE, SPEED }

/**
 * B11-3: which sentence tells the listener that the Pure Direct lock is keeping [feature] from being
 * applied. Pure, so the copy can be tested without a composition, and every id lives in
 * `strings_pristine_mode.xml` where the rebrand renames the lock in one place.
 *
 * When Direct is not carrying the stream, the setting *does* apply (the lock only holds the Direct
 * signal on PRISTINE), so the sentence says it will not apply *in USB Direct* instead of claiming it
 * is off now: on the phone without a DAC the EQ works, and the notice must not say it does not.
 *
 * Crossfade and speed (B15-F2) are different: with a DAC connected the lock holds them off whatever the route is (a crossfade
 * is an Android-route feature, so letting it apply there would be the lock undoing itself), so their sentence does not
 * depend on [directActive]. B16-G4: it does depend on [dacPresent]: with no DAC the lock is not in force, they apply, and the
 * sentence says only that they will not once a DAC is connected.
 */
object PristineLockCopy {
    @StringRes
    fun notice(feature: PristineFrozenFeature, directActive: Boolean, dacPresent: Boolean = true): Int = when (feature) {
        PristineFrozenFeature.EQ ->
            if (directActive) R.string.pristine_lock_notice_eq else R.string.pristine_lock_notice_eq_pending
        PristineFrozenFeature.REPLAY_GAIN ->
            if (directActive) R.string.pristine_lock_notice_replaygain else R.string.pristine_lock_notice_replaygain_pending
        PristineFrozenFeature.DIGITAL_VOLUME ->
            if (directActive) R.string.pristine_lock_notice_digital else R.string.pristine_lock_notice_digital_pending
        PristineFrozenFeature.CROSSFADE ->
            if (dacPresent) R.string.pristine_lock_notice_crossfade else R.string.pristine_lock_notice_crossfade_pending
        PristineFrozenFeature.SPEED ->
            if (dacPresent) R.string.pristine_lock_notice_speed else R.string.pristine_lock_notice_speed_pending
    }

    /**
     * B16-G5: the reason, in plain words, when a **listening choice** (crossfade, speed, an Android session effect) is what
     * keeps the stream off USB Direct with a DAC connected; null for every other reason. The device status says it
     * even with the lock off, where the lock's own status line has nothing to explain.
     */
    @StringRes
    fun signalBlockerReason(reason: AudioRouteReason): Int? = when (reason) {
        AudioRouteReason.CROSSFADE_REQUIRES_ANDROID,
        AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID,
        AudioRouteReason.SPEED_REQUIRES_ANDROID -> routeReason(reason)
        else -> null
    }

    /**
     * B16-G5: the note under the crossfade and speed settings when a DAC is connected and the lock is off: they work, and they
     * take playback off USB Direct. Null for the features that do not (EQ, ReplayGain, digital volume stay on Direct).
     */
    @StringRes
    fun androidRouteNote(feature: PristineFrozenFeature): Int? = when (feature) {
        PristineFrozenFeature.CROSSFADE -> R.string.setcat_crossfade_android_route_note
        PristineFrozenFeature.SPEED -> R.string.setcat_speed_android_route_note
        else -> null
    }

    /**
     * Why the stream is not on USB Direct while the lock is on (B15-F2): the route's own reason, in words. Null when
     * the reason is Direct itself, which needs no explanation.
     */
    @StringRes
    fun routeReason(reason: AudioRouteReason): Int? = when (reason) {
        AudioRouteReason.STARTING -> R.string.pristine_reason_starting
        AudioRouteReason.NO_USB_DAC -> R.string.pristine_reason_no_dac
        AudioRouteReason.USB_PERMISSION_REQUIRED -> R.string.pristine_reason_permission
        AudioRouteReason.SOURCE_NOT_DIRECT_COMPATIBLE -> R.string.pristine_reason_source
        AudioRouteReason.SAFETY_TRANSITION_DEFERRED -> R.string.pristine_reason_deferred
        AudioRouteReason.DIRECT_FAILED_FALLBACK -> R.string.pristine_reason_failed
        AudioRouteReason.MAINTENANCE_FORCE_ANDROID,
        AudioRouteReason.MAINTENANCE_DIRECT_DISABLED -> R.string.pristine_reason_maintenance
        AudioRouteReason.CROSSFADE_REQUIRES_ANDROID -> R.string.pristine_reason_crossfade
        AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID -> R.string.pristine_reason_effect
        AudioRouteReason.SPEED_REQUIRES_ANDROID -> R.string.pristine_reason_speed
        AudioRouteReason.DIRECT_PRISTINE,
        AudioRouteReason.DIRECT_PROCESSED,
        AudioRouteReason.DIRECT_ACTIVE -> null
    }
}

/**
 * B11-3 (F-02): on a setting the Pure Direct lock freezes (EQ, ReplayGain, digital volume), says so
 * plainly, and offers the way to change it. The setting stays editable and is kept; it is simply not
 * applied while the lock holds the Direct stream untouched. Nothing while the lock is off.
 *
 * It used to be a grey sentence with no way out; on 2026-10-06 the lock froze ReplayGain with nothing
 * on screen that said why.
 *
 * B21: stateless. It used to ask a ViewModel of its own (`PristineLockViewModel`) whether the lock was on and
 * whether Direct was carrying the stream, which was a third copy of the same two facts. It now draws what
 * [AudioStatus] says; the screen that shows it passes the status it already has.
 *
 * @param onChange where the lock's switch is. Null when the switch is already on the page (the
 *        notice then has nothing to point at).
 */
@Composable
fun PristineFrozenNote(
    feature: PristineFrozenFeature,
    status: AudioStatus,
    modifier: Modifier = Modifier,
    onChange: (() -> Unit)? = null,
) {
    val notice = status.frozenNotice(feature) ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(notice, stringResource(R.string.pristine_lock_name)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            if (onChange != null) {
                TextButton(onClick = onChange) { Text(stringResource(R.string.pristine_lock_change)) }
            }
        }
    }
}

/**
 * B16-G5: under the crossfade and speed settings, with a USB DAC connected and the lock off: they work, and they take
 * playback off USB Direct (USB Direct has neither yet). Said plainly, because the readout switches to % and the DAC's own
 * volume and Safety Max's hardware level are not what is playing.
 */
@Composable
fun AndroidRouteNote(feature: PristineFrozenFeature, status: AudioStatus, modifier: Modifier = Modifier) {
    val note = status.androidRouteNote(feature) ?: return
    Text(
        text = stringResource(note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
