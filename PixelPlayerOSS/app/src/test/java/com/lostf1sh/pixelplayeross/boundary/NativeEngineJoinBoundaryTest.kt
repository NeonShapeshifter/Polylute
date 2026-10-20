package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-014: nativeDestroy joined the decode thread only while `running` was true. A thread that
 * ended on its own (EOS/error — every natural track end) was never joined, and `delete engine` could
 * run while its epilogue still read the engine. Kotlin calls destroy() without stop() on that path.
 */
class NativeEngineJoinBoundaryTest {
    private val engine = File(
        "../../decent-player/libs/decent-usb-audio-driver/src/main/jni/native-audio-engine.cpp"
    ).readText().replace("\r\n", "\n")

    private fun body(signature: String): String {
        val start = engine.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return engine.substring(start, engine.indexOf("\n}\n", start))
    }

    @Test
    fun `destroy joins a started thread whether or not it is still running`() {
        val destroy = body("Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(")
        assertThat(destroy).contains("joinableThreadJoin(&engine->decodeThread)")
        assertThat(destroy).doesNotContain("if (engine->running.load()) {")
        val join = destroy.indexOf("joinableThreadJoin(&engine->decodeThread)")
        assertThat(join).isLessThan(destroy.indexOf("delete engine->parser"))
    }

    @Test
    fun `stop and start go through the same once-only join`() {
        assertThat(body("Java_com_decent_usbaudio_NativeAudioEngine_nativeStop("))
            .contains("joinableThreadJoin(&engine->decodeThread)")
        assertThat(body("Java_com_decent_usbaudio_NativeAudioEngine_nativeStart("))
            .contains("joinableThreadStart(&engine->decodeThread, decodeThreadFunc, engine)")
        assertThat(engine).doesNotContain("pthread_join(engine->thread")
    }

    @Test
    fun `the decode thread publishes not-running as its last access to the engine`() {
        val fn = body("static void *decodeThreadFunc(")
        val epilogue = fn.substring(fn.lastIndexOf("LOGI(\"Decode thread exited"))
        assertThat(epilogue).contains("engine->running.store(false);")
    }
}
