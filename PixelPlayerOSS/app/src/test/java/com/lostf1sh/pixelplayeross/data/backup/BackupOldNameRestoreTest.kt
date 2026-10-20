package com.lostf1sh.pixelplayeross.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import com.lostf1sh.pixelplayeross.data.backup.format.BackupFormatDetector
import com.lostf1sh.pixelplayeross.data.backup.format.BackupReader
import com.lostf1sh.pixelplayeross.data.backup.format.BackupWriter
import com.lostf1sh.pixelplayeross.data.backup.format.LegacyPayloadAdapter
import com.lostf1sh.pixelplayeross.data.backup.history.BackupHistoryRepository
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupValidationResult
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.backup.module.BackupModuleHandler
import com.lostf1sh.pixelplayeross.data.backup.restore.RestoreExecutor
import com.lostf1sh.pixelplayeross.data.backup.restore.RestorePlanner
import com.lostf1sh.pixelplayeross.data.backup.validation.BackupFileValidator
import com.lostf1sh.pixelplayeross.data.backup.validation.ContentSanitizer
import com.lostf1sh.pixelplayeross.data.backup.validation.ManifestValidator
import com.lostf1sh.pixelplayeross.data.backup.validation.ModuleSchemaValidator
import com.lostf1sh.pixelplayeross.data.backup.validation.ValidationPipeline
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B38 (E2): a backup the app wrote under the old name, `PixelPlayerOSS_Backup_<time>.pxpl`, still restores.
 *
 * The new backups are called `Polylute_Backup_<time>.pxpl`. Nothing in the restore path reads the name of the file for anything but a
 * `.pxpl` / `.gz` extension warning (`BackupFileValidator`), and the format is told by the `PXPL` magic bytes, never by a name; this test is the
 * proof that stays true. It builds a real `.pxpl` with `BackupWriter`, serves it through a content provider that reports the file's display
 * name the way the system file picker does (that is where `BackupFileValidator` reads it from), and drives the real `BackupManager`: inspect,
 * then restore into a recording module. The same file under the old name, the new name and a name with no extension must restore the same data;
 * only the last one is allowed a warning (the extension).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupOldNameRestoreTest {

    /** Serves files by `content://<authority>/<key>`, answering the two columns `DocumentFile` asks for with the name the test chose. */
    class NamedFileProvider : ContentProvider() {
        class Served(val file: File, val displayName: String)

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val served = served[uri.lastPathSegment] ?: return null
            val columns = projection ?: arrayOf("_display_name", "_size")
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        "_display_name" -> served.displayName
                        "_size" -> served.file.length()
                        "mime_type" -> "application/octet-stream"
                        else -> null
                    }
                })
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(served.getValue(uri.lastPathSegment!!).file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "app.polylute.test.backupfiles"
            val served = mutableMapOf<String, Served>()
        }
    }

    /** A module that keeps what it was given, as the real handlers keep it in their stores. */
    private class RecordingHandler(override val section: BackupSection) : BackupModuleHandler {
        var data = "[\"before\"]"
        override suspend fun export(): String = data
        override suspend fun countEntries(): Int = 1
        override suspend fun snapshot(): String = data
        override suspend fun restore(payload: String) { data = payload }
        override suspend fun rollback(snapshot: String) { data = snapshot }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun servedBackup(key: String, displayName: String, payload: String): Uri {
        Robolectric.setupContentProvider(NamedFileProvider::class.java, NamedFileProvider.AUTHORITY)
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val file = File(context.cacheDir, "$key.pxpl")
        val written = runBlocking {
            BackupWriter(context, gson).write(
                uri = Uri.fromFile(file),
                manifest = BackupManifest(appVersion = "1.0.0", appVersionCode = 1, createdAt = System.currentTimeMillis()),
                modulePayloads = mapOf(BackupSection.FAVORITES.key to payload),
            )
        }
        check(written.isSuccess) { "could not write the test backup: ${written.exceptionOrNull()}" }
        NamedFileProvider.served[key] = NamedFileProvider.Served(file, displayName)
        return Uri.parse("content://${NamedFileProvider.AUTHORITY}/$key")
    }

    private class Rig(val manager: BackupManager, val validator: BackupFileValidator, val favorites: RecordingHandler)

    private fun rig(): Rig {
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val detector = BackupFormatDetector()
        val reader = BackupReader(context, gson, detector, LegacyPayloadAdapter())
        val validator = BackupFileValidator(context, detector)
        val pipeline = ValidationPipeline(validator, ManifestValidator(), ModuleSchemaValidator(ContentSanitizer()))
        val favorites = RecordingHandler(BackupSection.FAVORITES)
        val handlers = mapOf<BackupSection, BackupModuleHandler>(BackupSection.FAVORITES to favorites)
        val manager = BackupManager(
            context = context,
            backupWriter = BackupWriter(context, gson),
            backupReader = reader,
            restorePlanner = RestorePlanner(reader, handlers),
            restoreExecutor = RestoreExecutor(reader, pipeline, handlers),
            validationPipeline = pipeline,
            backupHistoryRepository = mockk<BackupHistoryRepository>(relaxed = true),
            handlers = handlers,
        )
        return Rig(manager, validator, favorites)
    }

    private fun restoresCleanly(displayName: String, key: String): List<String> {
        val payload = "[]"
        val uri = servedBackup(key, displayName, payload)
        val rig = rig()

        // the validator reads the display name from the provider: this is the check that is about the file's name
        val validation = rig.validator.validate(uri)
        val warnings = (validation as? BackupValidationResult.Invalid)?.errors?.map { it.code }.orEmpty()
        assertThat((validation as? BackupValidationResult.Invalid)?.fatalErrors.orEmpty()).isEmpty()

        val plan = runBlocking { rig.manager.inspectBackup(uri) }.getOrThrow()
        assertThat(plan.availableModules).containsExactly(BackupSection.FAVORITES)

        val result = runBlocking { rig.manager.restore(uri, plan.copy(selectedModules = setOf(BackupSection.FAVORITES))) { } }
        assertThat(result).isInstanceOf(RestoreResult.Success::class.java)
        assertThat(rig.favorites.data).isEqualTo(payload)
        return warnings
    }

    @Test
    fun `a backup under the old name PixelPlayerOSS_Backup restores and draws no warning`() {
        assertThat(restoresCleanly("PixelPlayerOSS_Backup_1760000000000.pxpl", "old")).isEmpty()
    }

    @Test
    fun `a backup under the new name Polylute_Backup restores and draws no warning`() {
        assertThat(restoresCleanly("Polylute_Backup_1760000000000.pxpl", "new")).isEmpty()
    }

    @Test
    fun `the name decides nothing but the extension warning`() {
        assertThat(restoresCleanly("PixelPlayerOSS_Backup_1760000000000", "noext")).containsExactly("FILE_EXTENSION")
    }
}
