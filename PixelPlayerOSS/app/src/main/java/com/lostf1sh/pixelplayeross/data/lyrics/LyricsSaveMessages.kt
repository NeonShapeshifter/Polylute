package com.lostf1sh.pixelplayeross.data.lyrics

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R

@StringRes
internal fun LyricsWriteReason.messageRes(): Int = when (this) {
    LyricsWriteReason.PERMISSION -> R.string.lyrics_reason_permission
    LyricsWriteReason.CARD_READ_ONLY -> R.string.lyrics_reason_card_read_only
    LyricsWriteReason.FILE_READ_ONLY -> R.string.lyrics_reason_file_read_only
    LyricsWriteReason.NO_SPACE -> R.string.lyrics_reason_no_space
    LyricsWriteReason.WRONG_FOLDER -> R.string.lyrics_reason_wrong_folder
    LyricsWriteReason.UNAVAILABLE -> R.string.lyrics_reason_unavailable
    LyricsWriteReason.IO -> R.string.lyrics_reason_io
    LyricsWriteReason.EXISTING_FILE -> R.string.lyrics_reason_existing_file
}
