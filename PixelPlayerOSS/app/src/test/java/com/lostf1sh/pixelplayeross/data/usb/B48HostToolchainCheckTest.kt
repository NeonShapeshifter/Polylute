package com.lostf1sh.pixelplayeross.data.usb

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B48-3: `buildHostTransportPlanLib` (the host library the JVM unit tests load) fails with one clear line when the host cannot build it. The task runs
 * `decent-player/tools/check-host-toolchain.sh` first and throws its line as the failure; this runs that script against fake NDK trees (a missing toolchain, a missing clang,
 * a host that is not Linux) and against a complete one.
 */
class B48HostToolchainCheckTest {

    private val appRoot = File(".").canonicalFile
    private val script = File(appRoot, "../../decent-player/tools/check-host-toolchain.sh").canonicalFile
    private val driverBuild = File(appRoot, "../../decent-player/libs/decent-usb-audio-driver/build.gradle.kts").canonicalFile

    private class Outcome(val exit: Int, val out: String, val err: String)

    private fun run(ndk: String?, os: String? = null): Outcome {
        val command = mutableListOf("bash", script.absolutePath)
        if (ndk != null) command += ndk
        val builder = ProcessBuilder(command)
        if (os != null) builder.environment()["HOST_OS_OVERRIDE"] = os
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
        return Outcome(process.exitValue(), out.trim(), err.trim())
    }

    private fun fakeNdk(clang: Boolean, clangExecutable: Boolean = true, jni: Boolean = true, prebuilt: Boolean = true): File {
        val ndk = Files.createTempDirectory("fake-ndk").toFile()
        if (prebuilt) {
            val host = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64").apply { mkdirs() }
            if (clang) File(host, "bin").mkdirs().also { File(host, "bin/clang").apply { writeText("#!/bin/sh\n"); setExecutable(clangExecutable) } }
            if (jni) File(host, "sysroot/usr/include").mkdirs().also { File(host, "sysroot/usr/include/jni.h").writeText("// jni") }
        }
        return ndk
    }

    private fun assertOneClearLine(outcome: Outcome, mustMention: String) {
        assertThat(outcome.exit).isEqualTo(1)
        assertThat(outcome.out).isEmpty()
        assertWithMessage("one line").that(outcome.err.lines()).hasSize(1)
        assertThat(outcome.err).startsWith("buildHostTransportPlanLib:")
        assertThat(outcome.err).contains(mustMention)
        assertThat(outcome.err.length).isLessThan(450)
        assertThat(outcome.err).doesNotContain("error:")
    }

    @Test
    fun `no ndk directory is one clear line`() {
        assertOneClearLine(run(null), "no NDK directory was given")
        assertOneClearLine(run(""), "no NDK directory was given")
        assertOneClearLine(run("/nonexistent/ndk/29.0.14206865"), "/nonexistent/ndk/29.0.14206865 does not exist")
    }

    @Test
    fun `an ndk with no host toolchain is one clear line that names it`() {
        val ndk = fakeNdk(clang = false, prebuilt = false)
        try {
            assertOneClearLine(run(ndk.absolutePath), "has no host toolchain under toolchains/llvm/prebuilt")
        } finally {
            ndk.deleteRecursively()
        }
    }

    @Test
    fun `a host toolchain without an executable clang or without jni h is one clear line`() {
        val noClang = fakeNdk(clang = false)
        val notExecutable = fakeNdk(clang = true, clangExecutable = false)
        val noJni = fakeNdk(clang = true, jni = false)
        try {
            assertOneClearLine(run(noClang.absolutePath), "bin/clang is missing or not executable")
            assertOneClearLine(run(notExecutable.absolutePath), "bin/clang is missing or not executable")
            assertOneClearLine(run(noJni.absolutePath), "jni.h is missing")
        } finally {
            listOf(noClang, notExecutable, noJni).forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `a host that is not linux is one clear line even with a complete ndk`() {
        val ndk = fakeNdk(clang = true)
        try {
            assertOneClearLine(run(ndk.absolutePath, os = "Darwin"), "this host is Darwin")
            assertOneClearLine(run(ndk.absolutePath, os = "MINGW64_NT"), "this host is MINGW64_NT")
        } finally {
            ndk.deleteRecursively()
        }
    }

    @Test
    fun `a complete tree passes and prints the clang`() {
        val ndk = fakeNdk(clang = true)
        try {
            val outcome = run(ndk.absolutePath, os = "Linux")
            assertThat(outcome.exit).isEqualTo(0)
            assertThat(outcome.err).isEmpty()
            assertThat(outcome.out).isEqualTo(File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/clang").absolutePath)
        } finally {
            ndk.deleteRecursively()
        }
    }

    @Test
    fun `the task runs the check first, fails with its line, and has no kotlin copy of the plan`() {
        val build = driverBuild.readText().replace("\r\n", "\n")
        val task = build.substringAfter("tasks.register(\"buildHostTransportPlanLib\")")
        assertThat(task).contains("tools/check-host-toolchain.sh")
        assertThat(task).contains("throw GradleException(problem.ifEmpty")
        assertThat(task.indexOf("check-host-toolchain.sh")).isLessThan(task.indexOf("ProcessBuilder(command)"))
        // the ndk can be pointed elsewhere to show the message without uninstalling anything
        assertThat(task).contains("decent.hostToolchain.ndkDir")
    }
}
