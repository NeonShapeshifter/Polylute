package com.lostf1sh.pixelplayeross.data.media

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class B63SafeWriteFaultTest {
    private fun fault(stage: String) {
        val dir = Files.createTempDirectory("b63-fault").toFile()
        try {
            val original = File(dir, "original.flac").apply { writeBytes(ByteArray(16384) { it.toByte() }) }
            val source = File(dir, "new.flac").apply { writeBytes(ByteArray(32768) { 77 }) }
            val before = original.readBytes()
            val temp = File(dir, "sibling.flac")
            val ops = object : MetadataFileReplacement.Operations {
                override fun sibling(target: File): File {
                    if(stage == "create") throw IOException("no directory access")
                    return temp.apply { createNewFile() }
                }
                override fun copyAndSync(source: File, sibling: File) {
                    if(stage == "write" || stage == "disk-full") {
                        sibling.writeBytes(source.readBytes().take(100).toByteArray())
                        throw IOException(if(stage == "disk-full") "ENOSPC" else "EIO")
                    }
                    source.copyTo(sibling, overwrite = true)
                }
                override fun rename(sibling: File, target: File) = if(stage == "rename") false else sibling.renameTo(target)
            }
            val outcome = runCatching { MetadataFileReplacement.replace(source, original, ops) }
            assertTrue(outcome.exceptionOrNull() is SafeMetadataWriteUnavailable)
            assertArrayEquals(before, original.readBytes())
            assertFalse(temp.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `temp creation failure leaves original byte equal`() { fault("create") }
    @Test fun `write failure leaves original byte equal`() { fault("write") }
    @Test fun `rename failure leaves original byte equal`() { fault("rename") }
    @Test fun `full disk midway through copying leaves original byte equal`() { fault("disk-full") }
    @Test fun `successful sibling rename installs complete edited bytes`() {
        val dir = Files.createTempDirectory("b63-success").toFile()
        try {
            val old = File(dir,"original.flac").apply { writeBytes(byteArrayOf(1,2,3)) }
            val new = File(dir,"new.flac").apply { writeBytes(ByteArray(32768) { it.toByte() }) }
            MetadataFileReplacement.replace(new, old)
            assertArrayEquals(new.readBytes(), old.readBytes())
            assertEquals(setOf("original.flac", "new.flac"), dir.list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }
}
