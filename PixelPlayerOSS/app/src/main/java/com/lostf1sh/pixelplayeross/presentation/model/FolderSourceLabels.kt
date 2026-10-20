package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.FolderSource

/**
 * The folder breadcrumb's root label, resolved in the presentation layer.
 *
 * [FolderSource.displayName] is a data-layer label — it sits next to the storage key the folder
 * navigation persists and reads back — so it stays English; the breadcrumb draws this instead, and
 * reuses the two strings the device control centre already ships for the same two places.
 */
@StringRes
fun FolderSource.displayNameRes(): Int = when (this) {
    FolderSource.INTERNAL -> R.string.dap_storage_internal
    FolderSource.SD_CARD -> R.string.dap_storage_sd
}
