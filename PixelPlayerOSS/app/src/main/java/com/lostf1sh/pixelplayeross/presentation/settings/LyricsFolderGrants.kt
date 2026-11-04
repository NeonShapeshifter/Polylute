package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.lyrics.AndroidLyricsStorage
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem

@Composable
internal fun LyricsFolderGrants() {
    val context = LocalContext.current
    val storage = remember(context) { AndroidLyricsStorage(context) }
    var grants by remember { mutableStateOf(storage.grants()) }
    if (grants.isNotEmpty()) SettingsSubsection(title = stringResource(R.string.lyrics_folder_grants)) {
        grants.forEach { (uri, path) ->
            SettingsItem(
                title = java.io.File(path).name,
                subtitle = path,
                leadingIcon = {},
                trailingIcon = {
                    TextButton(onClick = { storage.dropTree(uri); grants = storage.grants() }) {
                        Text(stringResource(R.string.lyrics_remove_grant))
                    }
                },
                onClick = {}
            )
        }
    }
}
