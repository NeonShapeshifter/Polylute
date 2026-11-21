package com.lostf1sh.pixelplayeross.data.backup.module

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessageException

internal fun imageItemsFailed(items: List<String>) = BackupMessageException(
    BackupMessage.of(R.string.backup_error_image_items_failed, items.distinct().joinToString(", "))
)
