package com.lostf1sh.pixelplayeross.presentation.safety

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxRefusalNotifier
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ThemeStateHolder
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import com.lostf1sh.pixelplayeross.ui.theme.resolveDrawnBaseTheme
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

    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository

    @Inject
    lateinit var themeStateHolder: ThemeStateHolder

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
            val systemDark = isSystemInDarkTheme()
            val appThemeMode by themePreferencesRepository.appThemeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.FOLLOW_SYSTEM)
            val dapAccent by themePreferencesRepository.accentFlow.collectAsStateWithLifecycle(initialValue = DapAccent.DEFAULT)
            val dapBaseTheme by themePreferencesRepository.baseThemeFlow.collectAsStateWithLifecycle(initialValue = DapBaseTheme.DEFAULT)
            val appColorSource by themePreferencesRepository.appColorSourceFlow.collectAsStateWithLifecycle(initialValue = AppColorSource.THEME)
            val schemePair by themeStateHolder.currentAlbumArtColorSchemePair.collectAsStateWithLifecycle()
            val drawnBaseTheme = resolveDrawnBaseTheme(appColorSource, appThemeMode, dapBaseTheme, systemDark)
            val useDark = !drawnBaseTheme.palette.isLight
            val takesCover = AppColorSource.takesColoursFromCover(appColorSource)
            val coverScheme = if (takesCover) schemePair?.let { if (useDark) it.dark else it.light } else null
            val look = remember(takesCover, drawnBaseTheme, dapAccent, coverScheme) {
                if (takesCover) AlbumArtColors.look(drawnBaseTheme.palette, coverScheme?.primary, dapAccent) else null
            }
            PixelPlayerTheme(
                darkTheme = useDark,
                accent = dapAccent,
                baseTheme = drawnBaseTheme,
                palette = look?.palette ?: drawnBaseTheme.palette,
                accentColorOverride = look?.accent,
                coverSchemeOverride = coverScheme,
                coverAccentOverride = look?.accent,
            ) {
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
