package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ResonanceMode

/**
 * The Resonance screen's route (B30-P3): which screen it is, decided before either exists.
 *
 * - a FiiO/Snowsky DAC whose EQ was read and that this build may write: the DAC's own EQ ([DacEqualizerScreen]);
 * - anything else: the software equalizer, as before ([EqualizerScreen]).
 *
 * The decision is made by [DacEqViewModel] **first**, and the software screen's view model is created only if the answer is the
 * software EQ: that view model holds the signal on the processed path while its editor is open, which editing a DAC's own EQ
 * must never cause. The answer is kept for as long as the screen lives.
 */
@Composable
fun EqualizerRoute(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    dacEqViewModel: DacEqViewModel = hiltViewModel(),
) {
    val mode by dacEqViewModel.controller.mode.collectAsStateWithLifecycle()
    when (mode) {
        ResonanceMode.DECIDING -> DacEqReadingContent()
        ResonanceMode.DAC -> DacEqualizerScreen(navController, dacEqViewModel.controller)
        ResonanceMode.SOFTWARE -> EqualizerScreen(navController = navController, playerViewModel = playerViewModel)
    }
}
