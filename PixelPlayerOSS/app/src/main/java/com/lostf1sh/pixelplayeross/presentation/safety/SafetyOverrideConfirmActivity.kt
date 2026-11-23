package com.lostf1sh.pixelplayeross.presentation.safety

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxRefusalNotifier
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * B69: the small window that "Raise the limit" opens over the app in front. Translucent, in its own task (`taskAffinity=""`), out of
 * the recents, not kept in history: the listener stays where they were, and Polylute's own task is not brought forward.
 *
 * Not exported, and not shown over the lock screen: it is reached only from Polylute's own notification, after an unlock.
 */
@AndroidEntryPoint
class SafetyOverrideConfirmActivity : ComponentActivity() {

    @Inject
    lateinit var safetyVolumeController: SafetyVolumeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened or not, the action has been used.
        SafetyMaxRefusalNotifier.cancel(this)
        val confirmation = SafetyOverrideConfirmation(safetyVolumeController) {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }
        if (!confirmation.shouldShow()) {
            finish()
            return
        }
        val profileName = safetyVolumeController.state.value.selectedProfileName.orEmpty()
        setContent {
            PixelPlayerTheme {
                SafetyOverrideConfirmDialog(
                    profileName = profileName,
                    onConfirm = {
                        confirmation.confirm()
                        finish()
                    },
                    onCancel = {
                        confirmation.cancel()
                        finish()
                    },
                )
            }
        }
    }
}
