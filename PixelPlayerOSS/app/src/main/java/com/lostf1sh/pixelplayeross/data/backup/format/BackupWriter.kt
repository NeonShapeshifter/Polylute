package com.lostf1sh.pixelplayeross.data.backup.format

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessageException
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupModuleInfo
import com.lostf1sh.pixelplayeross.data.backup.validation.BackupFileValidator
import com.lostf1sh.pixelplayeross.di.BackupGson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @BackupGson private val gson: Gson
) {
    private data class PayloadInfo(val bytes: Long, val crc: Long, val checksum: String, val store: Boolean)

    suspend fun write(
        uri: Uri,
        manifest: BackupManifest,
        modulePayloads: Map<String, String>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        passphrase: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val infos = linkedMapOf<String, PayloadInfo>()
            var totalBytes = 0L
            modulePayloads.forEach { (key, payload) ->
                require(key.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid backup module '$key'" }
                val info = inspect(payload, BackupReader.maxModulePayloadBytes(key), key)
                totalBytes += info.bytes
                checkLimit(totalBytes, BackupFileValidator.MAX_TOTAL_DECOMPRESSED_BYTES, key)
                infos[key] = info
            }
            val modules = modulePayloads.mapValues { (key, payload) ->
                val info = infos.getValue(key)
                BackupModuleInfo(info.checksum, countEntries(payload, key), info.bytes)
            }
            val manifestJson = gson.toJson(manifest.copy(modules = modules))
            val manifestInfo = inspect(manifestJson, BackupReader.MAX_MANIFEST_BYTES, "manifest")
            checkLimit(totalBytes + manifestInfo.bytes, BackupFileValidator.MAX_TOTAL_DECOMPRESSED_BYTES, "manifest")
            // Finish and check a private archive before opening the user's destination. No JSON byte map.
            val archive = File.createTempFile("backup-export-", ".pxpl", context.cacheDir)
            try {
                archive.outputStream().use { out ->
                    out.write(BackupFormatDetector.PXPL_MAGIC)
                    ZipOutputStream(out).use { zip ->
                        writeEntry(zip, BackupManifest.MANIFEST_FILENAME, manifestJson, manifestInfo)
                        onProgress(1, modules.size + 1)
                        modulePayloads.entries.forEachIndexed { index, (key, payload) ->
                            writeEntry(zip, "$key.json", payload, infos.getValue(key))
                            checkLimit(archive.length(), BackupFileValidator.MAX_BACKUP_SIZE_BYTES, key)
                            onProgress(index + 2, modules.size + 1)
                        }
                    }
                }
                val overhead = if (passphrase == null) 0L else 1024L // encryption header/tag fits within this bound
                checkLimit(archive.length() + overhead, BackupFileValidator.MAX_BACKUP_SIZE_BYTES,
                    modulePayloads.keys.lastOrNull() ?: "manifest")
                require(totalBytes + manifestInfo.bytes <= (archive.length() - BackupFormatDetector.PXPL_MAGIC_SIZE) * BackupFileValidator.MAX_ZIP_RATIO) {
                    "Backup modules exceed the import compression ratio limit"
                }
                context.contentResolver.openOutputStream(uri)?.use { raw ->
                    raw.write(BackupFormatDetector.PXPL_MAGIC)
                    val output = if (passphrase != null) BackupCrypto.encryptingStream(raw, passphrase) else raw
                    output.use { dest -> archive.inputStream().use { input ->
                        repeat(BackupFormatDetector.PXPL_MAGIC_SIZE) { check(input.read() != -1) }
                        input.copyTo(dest)
                    } }
                } ?: error("Unable to open output stream for backup")
            } finally { archive.delete() }
            Unit
        }
    }

    /** Encodes in small chunks for size/hash/CRC and a compression probe, without copying the JSON. */
    private fun inspect(payload: String, limit: Int, key: String): PayloadInfo {
        var bytes = 0L
        var compressed = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            val counter = object : OutputStream() {
                override fun write(b: Int) { compressed++ }
                override fun write(b: ByteArray, off: Int, len: Int) { compressed += len }
            }
            val probe = DeflaterOutputStream(counter, deflater)
            val sink = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    bytes += len
                    checkLimit(bytes, limit.toLong(), key)
                    digest.update(b, off, len); crc.update(b, off, len); probe.write(b, off, len)
                }
            }
            OutputStreamWriter(sink, Charsets.UTF_8).use { payload.reader().copyTo(it) }
            probe.finish()
            // Stored ZIP entries keep highly repetitive legitimate text within the existing bomb ratio.
            return PayloadInfo(bytes, crc.value, "sha256:" + digest.digest().joinToString("") { "%02x".format(it) },
                bytes > compressed.coerceAtLeast(1L) * BackupFileValidator.MAX_ZIP_RATIO)
        } finally { deflater.end() }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, payload: String, info: PayloadInfo) {
        val entry = ZipEntry(name)
        if (info.store) {
            entry.method = ZipEntry.STORED
            entry.size = info.bytes; entry.compressedSize = info.bytes; entry.crc = info.crc
        }
        zip.putNextEntry(entry)
        // Flushing completes UTF-8 encoding; closing this writer would close the whole archive.
        val unclosed = object : java.io.FilterOutputStream(zip) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { flush() }
        }
        OutputStreamWriter(unclosed, Charsets.UTF_8).use { payload.reader().copyTo(it) }
        zip.closeEntry()
    }

    private fun checkLimit(bytes: Long, limit: Long, key: String) {
        if (bytes > limit) throw BackupMessageException(BackupMessage.of(
            R.string.backup_error_export_module_limit, BackupSection.fromKey(key) ?: key, limit / (1024 * 1024)
        ))
    }

    private fun countEntries(payload: String, key: String): Int = JsonReader(payload.reader()).use { reader ->
        if (reader.peek() == JsonToken.BEGIN_ARRAY) return@use countArray(reader, key)
        if (key != "playlists" || reader.peek() != JsonToken.BEGIN_OBJECT) return@use 1
        reader.beginObject()
        var count = 1
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "playlists" && reader.peek() == JsonToken.BEGIN_ARRAY) count = countArray(reader, key)
            else reader.skipValue()
        }
        reader.endObject()
        count
    }

    private fun countArray(reader: JsonReader, key: String): Int {
        val limit = com.lostf1sh.pixelplayeross.data.backup.validation.ModuleSchemaValidator.MAX_ENTRIES_PER_MODULE
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            reader.skipValue()
            if (++count > limit) throw BackupMessageException(BackupMessage.of(
                R.string.backup_validation_too_many_entries, key, count, limit
            ))
        }
        reader.endArray()
        return count
    }
}
