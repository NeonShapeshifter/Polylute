package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Keeps old root-level custom artwork shareable without exposing all app storage. */
internal object SharedArtworkFiles {
    @Synchronized fun uri(context: Context, source: File): Uri {
        val authority = "${context.packageName}.provider"
        try { return FileProvider.getUriForFile(context, authority, source) }
        catch (_: IllegalArgumentException) { /* Legacy image outside a dedicated artwork folder. */ }
        require(source.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp"))
        val canonical = source.canonicalFile
        require(listOf(context.filesDir, context.cacheDir).any { canonical.path.startsWith(it.canonicalPath + File.separator) })
        val folder = File(context.cacheDir, "shared_artwork").apply { check(mkdirs() || isDirectory) }
        val key = MessageDigest.getInstance("SHA-256").digest(canonical.path.toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(folder, "$key.${source.extension}")
        if (!target.isFile || target.length() != source.length() || target.lastModified() != source.lastModified()) {
            val temporary = File.createTempFile("artwork-", ".tmp", folder)
            try {
                source.copyTo(temporary, overwrite = true)
                check(temporary.renameTo(target))
                target.setLastModified(source.lastModified())
            } finally { temporary.delete() }
        }
        return FileProvider.getUriForFile(context, authority, target)
    }
}
