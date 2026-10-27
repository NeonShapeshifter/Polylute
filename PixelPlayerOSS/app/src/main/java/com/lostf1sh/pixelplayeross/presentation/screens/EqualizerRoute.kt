package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.presentation.components.DacEqConsentDialog
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ResonanceMode

/**
 * The Resonance screen's route (B30-P3): which screen it is, decided before either exists.
 *
 * - a FiiO/Snowsky DAC whose EQ was read and that this build may write (the profile is verified, or the debug override, or, B40-F2, the
 *   listener allowed it): the DAC's own EQ ([DacEqualizerScreen]);
 * - anything else: the software equalizer, as before ([EqualizerScreen]), which offers the DAC's own EQ when the attached DAC has one
 *   Polylute can edit and the listener has not allowed it yet.
 *
 * The decision is made by [DacEqViewModel] **first**, and the software screen's view model is created only if the answer is the
 * software EQ: that view model holds the signal on the processed path while its editor is open, which editing a DAC's own EQ
 * must never cause. The answer is kept for as long as the screen lives, except that the listener's permission (given or taken back)
 * decides it again.
 */
@Composable
fun EqualizerRoute(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    dacEqViewModel: DacEqViewModel = hiltViewModel(),
    /** B41: opened from the Audio page's "Use the DAC's EQ": the listener's yes is asked as soon as there is something to ask it about. */
    askDacEqOnArrival: Boolean = false,
) {
    val mode by dacEqViewModel.controller.mode.collectAsStateWithLifecycle()
    val offer by dacEqViewModel.controller.offer.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    // Once per arrival: a listener who dismissed the question is not asked again by a recomposition, only by the row on the screen.
    var arrivalAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(askDacEqOnArrival, offer != null) {
        if (askDacEqOnArrival && offer != null && !arrivalAsked) {
            arrivalAsked = true
            asking = true
        }
    }
    when (mode) {
        ResonanceMode.DECIDING -> DacEqReadingContent()
        ResonanceMode.DAC -> DacEqualizerScreen(navController, dacEqViewModel.controller)
        ResonanceMode.SOFTWARE -> EqualizerScreen(
            navController = navController,
            playerViewModel = playerViewModel,
            dacOffer = offer,
            onUseDacEq = { asking = true },
        )
    }
    offer?.takeIf { asking }?.let { current ->
        DacEqConsentDialog(
            offer = current,
            onAllow = {
                asking = false
                dacEqViewModel.controller.grantConsent()
            },
            onDismiss = { asking = false },
        )
    }
}
