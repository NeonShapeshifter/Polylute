package com.lostf1sh.pixelplayeross.data.backup.restore

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessageException
import android.net.Uri
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.model.BackupOperationType
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupTransferProgressUpdate
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.backup.module.BackupModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.validation.ValidationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class RestoreExecutor @Inject constructor(
    private val backupReader: BackupReader,
    private val validationPipeline: ValidationPipeline,
    private val handlers: Map<BackupSection, @JvmSuppressWildcards BackupModuleHandler>
) {
    companion object {
        private const val TAG = "RestoreExecutor"
    }

    suspend fun execute(
        uri: Uri,
        plan: RestorePlan,
        onProgress: (BackupTransferProgressUpdate) -> Unit
    ): RestoreResult = withContext(Dispatchers.IO) {
        val selectedModules = plan.selectedModules.toList().sortedBy { it.key }
        val totalSteps = selectedModules.size * 2 + 3
        var step = 0

        reportProgress(
            onProgress, ++step, totalSteps,
            BackupMessage.of(R.string.backup_step_snapshot_title),
            BackupMessage.of(R.string.backup_step_snapshot_detail),
        )

        val snapshots = mutableMapOf<BackupSection, String>()
        try {
            selectedModules.forEach { section ->
                val handler = handlers[section]
                if (handler != null) {
                    snapshots[section] = handler.snapshot()
                }
            }
        } catch (e: CancellationException) {
            // Nothing has been written yet, so there is nothing to undo: let the cancellation through (B35, RT-02).
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Snapshot phase failed")
            return@withContext RestoreResult.TotalFailure(
                BackupMessage.of(
                    R.string.backup_error_snapshot_failed,
                    e.message ?: "",
                )
            )
        }

        reportProgress(
            onProgress, ++step, totalSteps,
            BackupMessage.of(R.string.backup_step_restore_prepare_title),
            BackupMessage.of(R.string.backup_step_restore_prepare_detail),
        )

        val restoredModules = mutableListOf<BackupSection>()
        var currentSection: BackupSection? = null
        try {
            selectedModules.forEach { section ->
                currentSection = section
                val moduleInfo = plan.manifest.modules[section.key]
                if (moduleInfo != null &&
                    moduleInfo.sizeBytes > BackupReader.MAX_MODULE_PAYLOAD_BYTES
                ) {
                    throw BackupMessageException(
                        BackupMessage.of(
                            R.string.backup_error_payload_over_limit,
                            section,
                            moduleInfo.sizeBytes / (1024 * 1024),
                            BackupReader.MAX_MODULE_PAYLOAD_BYTES / (1024 * 1024),
                        )
                    )
                }

                val payload = backupReader.readModulePayload(uri, section.key).getOrThrow()

                val validationResult = validationPipeline.validateModulePayload(
                    section, payload, plan.manifest
                )
                if (validationResult is BackupValidationResult.Invalid && validationResult.fatalErrors.isNotEmpty()) {
                    throw BackupMessageException(
                        BackupMessage.of(
                            R.string.backup_error_validation_failed_section,
                            section,
                            validationResult.fatalErrors.first().message,
                        )
                    )
                }

                reportProgress(
                    onProgress, ++step, totalSteps,
                    BackupMessage.of(R.string.backup_step_validated_title, section),
                    BackupMessage.of(section.descriptionRes),
                    section
                )

                reportProgress(
                    onProgress, ++step, totalSteps,
                    BackupMessage.of(R.string.backup_step_restoring_title, section),
                    BackupMessage.of(section.descriptionRes),
                    section
                )

                val handler = handlers[section]
                    ?: throw IllegalStateException("No handler for module ${section.key}")
                handler.restore(payload)
                restoredModules.add(section)
            }
        } catch (e: Exception) {
            // B35 (RT-02): a cancellation lands here too (CancellationException is an Exception). The rollback is what puts the data
            // back, and the handlers switch to IO to do it, so it must not run in the context that was just cancelled: there their
            // first suspension throws and nothing is written. It runs to its end under NonCancellable, and only then is the
            // cancellation let through.
            val cancelled = e is CancellationException
            if (cancelled) {
                Timber.tag(TAG).w(e, "Restore cancelled while processing backup module, rolling back")
            } else {
                Timber.tag(TAG).e(e, "Restore failed while processing backup module, rolling back")
            }
            val rollbackOrder = (restoredModules + listOfNotNull(currentSection)).distinct().asReversed()
            val rollbackSuccess = withContext(NonCancellable) { rollBack(rollbackOrder, snapshots) }
            if (cancelled) {
                if (!rollbackSuccess) Timber.tag(TAG).e("Restore cancelled and some modules could not be rolled back")
                throw e
            }

            // A reason the backup layer raised keeps its resource; anything else keeps its own
            // text. Only a failure with no message at all falls back to "unknown".
            val failedReason = BackupMessageException.messageOf(e)
                ?: e.message?.takeIf { it.isNotBlank() }?.let(BackupMessage::raw)
                ?: BackupMessage.of(R.string.backup_error_unknown)
            val failedSection = currentSection

            // A nested message: `backup_error_rolled_back` takes the section name as an argument,
            // and when there is no section the argument is itself a message.
            val failedLabel: Any = failedSection
                ?: BackupMessage.of(R.string.backup_error_unknown_section)

            if (rollbackSuccess) {
                return@withContext RestoreResult.TotalFailure(
                    BackupMessage.of(
                        R.string.backup_error_rolled_back,
                        failedLabel,
                        failedReason,
                    )
                )
            }

            return@withContext RestoreResult.PartialFailure(
                succeeded = restoredModules.toSet(),
                failed = failedSection?.let { mapOf(it to failedReason) }.orEmpty(),
                rolledBack = false
            )
        }

        reportProgress(
            onProgress, ++step, totalSteps,
            BackupMessage.of(R.string.backup_step_restore_done_title),
            BackupMessage.of(R.string.backup_step_restore_done_detail),
        )

        RestoreResult.Success
    }

    /** Puts every module in [order] back to its snapshot, whatever one of them does. False if any could not be restored. */
    private suspend fun rollBack(order: List<BackupSection>, snapshots: Map<BackupSection, String>): Boolean {
        var success = true
        order.forEach { section ->
            try {
                val snapshot = snapshots[section]
                if (snapshot != null) {
                    handlers[section]?.rollback(snapshot)
                }
            } catch (rollbackError: Exception) {
                Timber.tag(TAG).e(rollbackError, "Rollback failed for ${section.key}")
                success = false
            }
        }
        return success
    }

    private fun reportProgress(
        onProgress: (BackupTransferProgressUpdate) -> Unit,
        step: Int,
        totalSteps: Int,
        title: BackupMessage,
        detail: BackupMessage,
        section: BackupSection? = null
    ) {
        onProgress(
            BackupTransferProgressUpdate(
                operation = BackupOperationType.IMPORT,
                step = step,
                totalSteps = totalSteps,
                title = title,
                detail = detail,
                section = section
            )
        )
    }
}
