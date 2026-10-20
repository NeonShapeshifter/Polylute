package com.lostf1sh.pixelplayeross.data.backup.model

import com.lostf1sh.pixelplayeross.data.stats.ListenAudioFacts
import com.lostf1sh.pixelplayeross.data.stats.ListenContext
import com.lostf1sh.pixelplayeross.data.stats.PlaybackOutcome
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason

enum class BackupOperationType {
    EXPORT,
    IMPORT
}

data class BackupTransferProgressUpdate(
    val operation: BackupOperationType,
    val step: Int,
    val totalSteps: Int,
    val title: BackupMessage,
    val detail: BackupMessage,
    val section: BackupSection? = null
) {
    val progress: Float
        get() = if (totalSteps > 0) (step.toFloat() / totalSteps).coerceIn(0f, 1f) else 0f
}

/**
 * One listen as the backup holds it. Everything after [endTimestamp] is optional (B28a): an old backup has none of it and
 * restores as it always did, and a new one carries every field of the history event, so a restore gives back what was recorded.
 */
data class PlaybackHistoryBackupEntry(
    val songId: String,
    val timestamp: Long,
    val durationMs: Long,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val libraryItemId: String? = null,
    val occurrenceId: String? = null,
    val outcome: PlaybackOutcome? = null,
    val endReason: TrackEndReason? = null,
    val itemDurationMs: Long? = null,
    val audio: ListenAudioFacts? = null,
    val context: ListenContext? = null,
)

fun PlaybackStatsRepository.PlaybackEvent.toBackupEntry() = PlaybackHistoryBackupEntry(
    songId = songId,
    timestamp = timestamp,
    durationMs = durationMs,
    startTimestamp = startTimestamp,
    endTimestamp = endTimestamp,
    libraryItemId = libraryItemId,
    occurrenceId = occurrenceId,
    outcome = outcome,
    endReason = endReason,
    itemDurationMs = itemDurationMs,
    audio = audio,
    context = context,
)

fun PlaybackHistoryBackupEntry.toPlaybackEvent() = PlaybackStatsRepository.PlaybackEvent(
    songId = songId,
    timestamp = timestamp,
    durationMs = durationMs,
    startTimestamp = startTimestamp,
    endTimestamp = endTimestamp,
    libraryItemId = libraryItemId,
    occurrenceId = occurrenceId,
    outcome = outcome,
    endReason = endReason,
    itemDurationMs = itemDurationMs,
    audio = audio,
    context = context,
)

data class ArtistImageBackupEntry(
    val artistName: String,
    val imageUrl: String,
    val customImageBase64: String? = null
)

data class BackupHistoryEntry(
    val uri: String,
    val displayName: String,
    val createdAt: Long,
    val schemaVersion: Int,
    val modules: Set<String>,
    val sizeBytes: Long,
    val appVersion: String = ""
)

data class RestorePlan(
    val manifest: BackupManifest,
    val backupUri: String,
    val availableModules: Set<BackupSection>,
    val selectedModules: Set<BackupSection>,
    val moduleDetails: Map<BackupSection, ModuleRestoreDetail>,
    val warnings: List<BackupMessage> = emptyList()
)

data class ModuleRestoreDetail(
    val entryCount: Int,
    val sizeBytes: Long,
    val willOverwrite: Boolean = true
)

sealed class BackupValidationResult {
    data object Valid : BackupValidationResult()
    data class Invalid(val errors: List<ValidationError>) : BackupValidationResult() {
        val fatalErrors: List<ValidationError>
            get() = errors.filter { it.severity == Severity.ERROR }
        val warnings: List<ValidationError>
            get() = errors.filter { it.severity == Severity.WARNING }
    }

    fun isValid(): Boolean = this is Valid || (this is Invalid && fatalErrors.isEmpty())
}

data class ValidationError(
    val code: String,
    val message: BackupMessage,
    val module: String? = null,
    val severity: Severity = Severity.ERROR
)

enum class Severity { ERROR, WARNING }

sealed class RestoreResult {
    data object Success : RestoreResult()
    data class PartialFailure(
        val succeeded: Set<BackupSection>,
        val failed: Map<BackupSection, BackupMessage>,
        val rolledBack: Boolean
    ) : RestoreResult()
    data class TotalFailure(val error: BackupMessage) : RestoreResult()
}
