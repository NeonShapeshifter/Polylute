package com.lostf1sh.pixelplayeross.presentation.model

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition

/**
 * B39-A4: what the route says, in words. Maintenance used to print the enum's own name (`DIRECT_PRISTINE`), the product's name for that
 * path being **Purity** and for the processed one **Resonance**. The enums, the log keys and the persisted ids keep their names; only the
 * text a listener reads comes from here.
 */
fun AudioRouteReason.labelRes(): Int = when (this) {
    AudioRouteReason.STARTING -> R.string.route_reason_starting
    AudioRouteReason.NO_USB_DAC -> R.string.route_reason_no_usb_dac
    AudioRouteReason.USB_PERMISSION_REQUIRED -> R.string.route_reason_usb_permission
    AudioRouteReason.SOURCE_NOT_DIRECT_COMPATIBLE -> R.string.route_reason_source
    AudioRouteReason.SAFETY_TRANSITION_DEFERRED -> R.string.route_reason_deferred
    AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS -> R.string.route_reason_dac_shared
    AudioRouteReason.CROSSFADE_REQUIRES_ANDROID -> R.string.route_reason_crossfade
    AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID -> R.string.route_reason_effect
    AudioRouteReason.SPEED_REQUIRES_ANDROID -> R.string.route_reason_speed
    AudioRouteReason.DIRECT_PRISTINE -> R.string.route_reason_purity
    AudioRouteReason.DIRECT_PROCESSED -> R.string.route_reason_resonance
    AudioRouteReason.DIRECT_ACTIVE -> R.string.route_reason_direct_active
    AudioRouteReason.DIRECT_FAILED_FALLBACK -> R.string.route_reason_direct_failed
    AudioRouteReason.MAINTENANCE_FORCE_ANDROID -> R.string.route_reason_maintenance_force
    AudioRouteReason.MAINTENANCE_DIRECT_DISABLED -> R.string.route_reason_maintenance_disabled
}

fun AudioRouteTransition.labelRes(): Int = when (this) {
    AudioRouteTransition.ANDROID_ACTIVE -> R.string.route_transition_android_active
    AudioRouteTransition.DIRECT_ELIGIBLE -> R.string.route_transition_direct_eligible
    AudioRouteTransition.DIRECT_NEGOTIATING -> R.string.route_transition_direct_negotiating
    AudioRouteTransition.DIRECT_ACTIVE -> R.string.route_transition_direct_active
    AudioRouteTransition.DIRECT_FAILED_FALLBACK -> R.string.route_transition_direct_failed
}
