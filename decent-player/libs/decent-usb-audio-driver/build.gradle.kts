import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    id("com.android.library")
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        externalNativeBuild { cmake { cppFlags("") } }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake { path("src/main/jni/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin { jvmToolchain(21) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
}

// B46-A3: Kotlin asks the native transport plan (UsbTransportPlanner), and the JVM unit tests have no Android native library. This builds
// the plan alone (the packetizer, the container rule and the JNI facade: C headers only, no C++ library) as a host `libdecent_usb_audio.so`
// with the NDK's clang, so the tests call the same C++ the device runs. Not part of any APK; the app's unit tests depend on it and put its
// directory on java.library.path.
tasks.register("buildHostTransportPlanLib") {
    group = "verification"
    description = "Builds the transport plan's JNI facade for the host JVM (unit tests)."
    // Locals, not script properties: the task's action must not capture the build script (configuration cache).
    val jniDir = layout.projectDirectory.dir("src/main/jni")
    val sourceNames = listOf("usb-transport-plan-jni.cpp", "usb-pcm-packetizer.cpp", "usb-pcm-convert.cpp")
    val ndkDirectory = project.extensions.getByType<LibraryAndroidComponentsExtension>().sdkComponents.ndkDirectory
    // B48-3: `-Pdecent.hostToolchain.ndkDir=<dir>` replaces the SDK's NDK (how the missing-toolchain message is shown without removing an NDK).
    val ndkPath = providers.gradleProperty("decent.hostToolchain.ndkDir")
        .orElse(ndkDirectory.map { it.asFile.absolutePath }.orElse(""))
    val checkScript = layout.projectDirectory.file("../../tools/check-host-toolchain.sh")
    val outDir = layout.buildDirectory.dir("host-native")
    inputs.files(sourceNames.map { jniDir.file(it) })
    inputs.files(fileTree(jniDir) { include("*.h") })
    inputs.property("ndk", ndkPath)
    inputs.file(checkScript)
    outputs.dir(outDir)
    doLast {
        // One line, from tools/check-host-toolchain.sh, when this host cannot build the library (not Linux, no NDK, no host clang): not a clang error page.
        val check = try {
            ProcessBuilder("bash", checkScript.asFile.absolutePath, ndkPath.get()).redirectErrorStream(false).start()
        } catch (e: java.io.IOException) {
            throw GradleException("buildHostTransportPlanLib: this host cannot run bash, which checks the NDK's host clang; the JVM unit tests need a Linux host with the NDK (29.0.14206865) installed")
        }
        val clangPath = check.inputStream.bufferedReader().readText().trim()
        val problem = check.errorStream.bufferedReader().readText().trim()
        if (check.waitFor() != 0) throw GradleException(problem.ifEmpty { "buildHostTransportPlanLib: the host toolchain check failed without saying why" })
        val clang = File(clangPath)
        val prebuilt = clang.parentFile.parentFile
        val out = outDir.get().asFile.also { it.deleteRecursively(); it.resolve("include").mkdirs() }
        // jni.h alone: the NDK sysroot's other headers are bionic's and must not shadow the host's.
        prebuilt.resolve("sysroot/usr/include/jni.h").copyTo(out.resolve("include/jni.h"))
        val command = listOf(
            clang.absolutePath, "-x", "c++", "-std=c++11", "-O2", "-shared", "-fPIC", "-fno-exceptions", "-fno-rtti",
            "-Wall", "-Wextra", "-Werror", "-I", jniDir.asFile.absolutePath, "-I", out.resolve("include").absolutePath,
        ) + sourceNames.map { jniDir.file(it).asFile.absolutePath } +
            listOf("-o", out.resolve("libdecent_usb_audio.so").absolutePath, "-lm")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("Building the host transport plan library failed:\n$log")
    }
}
