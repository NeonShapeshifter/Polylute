package com.lostf1sh.pixelplayeross.data.backup.validation

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.format.BackupFormatDetector
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.Severity
import com.lostf1sh.pixelplayeross.data.backup.model.ValidationError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupFileValidator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val formatDetector: BackupFormatDetector
) {
    companion object {
        const val MAX_BACKUP_SIZE_BYTES = 50L * 1024 * 1024
        const val MAX_ZIP_RATIO = 100
        private const val MAX_TOTAL_DECOMPRESSED_BYTES = 256L * 1024 * 1024
    }

    fun validate(uri: Uri): BackupValidationResult {
        val errors = mutableListOf<ValidationError>()
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val fileName = docFile?.name
        val fileSize = docFile?.length()?.takeIf { it > 0L }

        val format = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = formatDetector.readHeader(input)
                if (header.isEmpty()) {
                    errors.add(ValidationError("FILE_EMPTY", BackupMessage.of(R.string.backup_validation_file_empty)))
                    return BackupValidationResult.Invalid(errors)
                }
                formatDetector.detect(header)
            }
        } catch (e: Exception) {
            errors.add(ValidationError(
                        "FILE_ACCESS",
                        BackupMessage.of(R.string.backup_validation_file_access_reason, e.message ?: ""),
                    ))
            return BackupValidationResult.Invalid(errors)
        }

        if (format == null) {
            errors.add(
                ValidationError(
                    "FILE_EMPTY",
                    BackupMessage.of(R.string.backup_validation_file_empty),
                )
            )
            return BackupValidationResult.Invalid(errors)
        }

        if (fileSize != null && fileSize > MAX_BACKUP_SIZE_BYTES) {
            errors.add(ValidationError(
                        "FILE_TOO_LARGE",
                        BackupMessage.of(
                            R.string.backup_validation_file_too_large,
                            MAX_BACKUP_SIZE_BYTES / (1024 * 1024),
                        ),
                    ))
            return BackupValidationResult.Invalid(errors)
        }

        if (fileName != null && !fileName.endsWith(".pxpl", ignoreCase = true) &&
            !fileName.endsWith(".gz", ignoreCase = true)) {
            errors.add(ValidationError(
                        "FILE_EXTENSION",
                        BackupMessage.of(R.string.backup_validation_file_extension),
                        severity = Severity.WARNING,
                    ))
        }

        if (format == BackupFormatDetector.Format.UNKNOWN) {
            errors.add(ValidationError(
                        "FORMAT_UNKNOWN",
                        BackupMessage.of(R.string.backup_validation_format_unknown),
                    ))
            return BackupValidationResult.Invalid(errors)
        }

        if (format == BackupFormatDetector.Format.PXPL_V3_ZIP) {
            validateZipSafety(uri, fileSize, BackupFormatDetector.PXPL_MAGIC_SIZE, errors)
        }

        return if (errors.any { it.severity == Severity.ERROR }) {
            BackupValidationResult.Invalid(errors)
        } else if (errors.isNotEmpty()) {
            BackupValidationResult.Invalid(errors)
        } else {
            BackupValidationResult.Valid
        }
    }

    private fun validateZipSafety(
        uri: Uri,
        fileSize: Long?,
        offset: Int,
        errors: MutableList<ValidationError>
    ) {
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                skipFully(raw, offset)
                val compressedZipBytes = fileSize?.minus(offset)?.coerceAtLeast(0L)

                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    var totalDecompressed = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (entry != null) {
                        val name = entry.name

                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                            errors.add(ValidationError(
                                "ZIP_PATH_TRAVERSAL",
                                BackupMessage.of(R.string.backup_validation_zip_path_traversal, name),
                            ))
                            return
                        }

                        if (!name.endsWith(".json")) {
                            errors.add(ValidationError(
                                "ZIP_UNEXPECTED_ENTRY",
                                BackupMessage.of(R.string.backup_validation_zip_unexpected_entry, name),
                                severity = Severity.WARNING,
                            ))
                        }

                        val perEntryLimit = if (name == BackupManifest.MANIFEST_FILENAME) {
                            BackupReader.MAX_MANIFEST_BYTES.toLong()
                        } else {
                            BackupReader.MAX_MODULE_PAYLOAD_BYTES.toLong()
                        }

                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read == -1) break

                            entryBytes += read
                            totalDecompressed += read

                            if (entryBytes > perEntryLimit) {
                                errors.add(
                                    ValidationError(
                                        "ZIP_ENTRY_TOO_LARGE",
                                        BackupMessage.of(
                                            R.string.backup_validation_zip_entry_too_large,
                                            name,
                                            perEntryLimit / (1024 * 1024),
                                        )
                                    )
                                )
                                return
                            }

                            if (totalDecompressed > MAX_TOTAL_DECOMPRESSED_BYTES) {
                                errors.add(
                                    ValidationError(
                                        "ZIP_TOO_LARGE",
                                        BackupMessage.of(
                                            R.string.backup_validation_zip_too_large,
                                            MAX_TOTAL_DECOMPRESSED_BYTES / (1024 * 1024),
                                        )
                                    )
                                )
                                return
                            }

                            if (compressedZipBytes != null &&
                                compressedZipBytes > 0 &&
                                totalDecompressed > compressedZipBytes * MAX_ZIP_RATIO
                            ) {
                                errors.add(ValidationError(
                                    "ZIP_BOMB",
                                    BackupMessage.of(R.string.backup_validation_zip_bomb),
                                ))
                                return
                            }
                        }

                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: errors.add(
                        ValidationError(
                            "FILE_ACCESS",
                            BackupMessage.of(R.string.backup_validation_file_access),
                        )
                    )
        } catch (e: Exception) {
            errors.add(ValidationError(
                    "ZIP_CORRUPT",
                    BackupMessage.of(R.string.backup_validation_zip_corrupt, e.message ?: ""),
                ))
        }
    }

    private fun skipFully(input: InputStream, byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped > 0) {
                remaining -= skipped.toInt()
                continue
            }

            if (input.read() == -1) {
                throw IllegalArgumentException("Backup file is truncated.")
            }
            remaining--
        }
    }
}
