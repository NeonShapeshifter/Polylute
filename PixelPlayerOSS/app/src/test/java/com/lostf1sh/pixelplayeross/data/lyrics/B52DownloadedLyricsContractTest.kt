package com.lostf1sh.pixelplayeross.data.lyrics

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B52DownloadedLyricsContractTest {
    private val root = Files.createTempDirectory("b52-lrc").toFile()
    private val files = mutableMapOf<String, String>()
    private val operations = mutableListOf<String>()
    private var removable = false
    private var tree: String? = null
    private var failure: IOException? = null
    private val song = Song.emptySong().copy(id = "52", libraryItemId = "identity-52", path = "/storage/emulated/0/Music/Song.flac")
    private val target = "/storage/emulated/0/Music/Song.lrc"
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private val storage = Proxy.newProxyInstance(LyricsStorage::class.java.classLoader, arrayOf(LyricsStorage::class.java)) { proxy, method, args ->
        when (method.name) {
            "isRemovable" -> removable
            "matchingTree" -> tree
            "exists" -> files.containsKey(args!![0])
            "contentHash" -> files[args!![0]]?.let(::digest)
            "deletePath" -> { operations += "delete"; files.remove(args!![0]); null }
            "writePath" -> { failure?.let { throw it }; operations += "path"; files[args!![0] as String] = args[1] as String; null }
            "writeTree" -> { failure?.let { throw it }; operations += "tree"; files[args!![1] as String] = args[2] as String; null }
            "writeAccessRequest" -> error("A background download must not request an Android launcher")
            "dropTree" -> null
            "toString" -> "FakeLyricsStorage"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            else -> error("Unexpected storage call ${method.name}")
        }
    } as LyricsStorage

    @After fun cleanup() { root.deleteRecursively() }

    private fun saver(): Any = Class.forName("com.lostf1sh.pixelplayeross.data.lyrics.DownloadedLyricsFiles")
        .constructors.single().newInstance(storage, File(root, "sidecars.json"))

    private suspend fun save(saver: Any, fallback: suspend () -> Unit): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        try { saver.javaClass.getMethod("save", Song::class.java, String::class.java, kotlin.jvm.functions.Function1::class.java, kotlin.coroutines.Continuation::class.java)
            .invoke(saver, song, "[00:01] sång", fallback, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun delete(saver: Any) { saver.javaClass.getMethod("delete", Song::class.java).invoke(saver, song) }

    @Test fun `downloaded internal lyrics use the shared path and an owned sidecar can be deleted after restart`(): Unit = runBlocking {
        val saver = saver()
        assertThat(save(saver) { error("must write beside the song") }?.javaClass?.simpleName).isEqualTo("FileSaved")
        assertThat(files[target]).isEqualTo("[00:01] sång")
        assertThat(operations).containsExactly("path")
        delete(saver())
        assertThat(files).isEmpty()
    }

    @Test fun `downloaded SD lyrics reuse the folder grant through the same file saver`(): Unit = runBlocking {
        removable = true; tree = "grant"
        assertThat(save(saver()) { error("grant is available") }?.javaClass?.simpleName).isEqualTo("FileSaved")
        assertThat(operations).containsExactly("tree")
    }

    @Test fun `SD without a grant keeps lyrics in the app without opening a picker`(): Unit = runBlocking {
        removable = true
        var fallback = 0
        val result = save(saver()) { fallback++ }
        assertThat(result?.javaClass?.simpleName).isEqualTo("SavedInApp")
        assertThat(fallback).isEqualTo(1)
        assertThat(operations).isEmpty()
    }

    @Test fun `a preexisting user sidecar is preserved by download and by deletion`(): Unit = runBlocking {
        files[target] = "user lyrics"
        var fallback = 0
        val saver = saver()
        save(saver) { fallback++ }
        delete(saver)
        assertThat(fallback).isEqualTo(1)
        assertThat(files[target]).isEqualTo("user lyrics")
        assertThat(operations).isEmpty()
    }

    @Test fun `user edits to a downloaded sidecar survive later downloaded info deletion`(): Unit = runBlocking {
        val saver = saver()
        save(saver) {}
        files[target] = "edited by the listener"
        delete(saver)
        assertThat(files[target]).isEqualTo("edited by the listener")
        assertThat(operations).containsExactly("path")
    }

    @Test fun `a manual save takes ownership even when its text is identical`(): Unit = runBlocking {
        val saver = saver()
        save(saver) {}
        saver.javaClass.getMethod("forget", Song::class.java).invoke(saver, song)
        delete(saver)
        assertThat(files[target]).isEqualTo("[00:01] sång")
    }

    @Test fun `a failed downloaded sidecar falls back with the shared no space reason`(): Unit = runBlocking {
        failure = IOException("ENOSPC")
        var fallback = 0
        val result = save(saver()) { fallback++ }!!
        assertThat(result.javaClass.getMethod("getReason").invoke(result).toString()).isEqualTo("NO_SPACE")
        assertThat(fallback).isEqualTo(1)
        assertThat(files).isEmpty()
    }
}
