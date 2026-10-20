package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron

/** Everything the About category can do: it holds one row, and the row opens the About screen. */
interface AboutSettingsActions {
    fun onOpenAbout()
}

@Composable
fun AboutSettingsScreen(actions: AboutSettingsActions) {
    SettingsSubsection(
        title = stringResource(R.string.setcat_application),
        addBottomSpace = false
    ) {
        SettingsItem(
            title = stringResource(R.string.setcat_about_pixelplayer_title),
            subtitle = stringResource(R.string.setcat_about_pixelplayer_subtitle),
            leadingIcon = { Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onOpenAbout() }
        )
    }
}
