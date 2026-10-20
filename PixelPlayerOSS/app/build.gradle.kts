import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    id("kotlin-parcelize")
}

val keystoreProperties = Properties().apply {
    val propFile = rootProject.file("keystore.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

// B35 (R6): the release key comes from `keystore.properties` (ignored by git) and from nowhere else: no key in the repository, no fallback
// to the debug key or to anyone else's. The store name is only a default for `storeFile`; `docs/release-signing.md` says how to make one.
val releaseSigningStoreFile = rootProject.file(
    keystoreProperties.getProperty("storeFile") ?: "polylute-release.jks"
)
val disableReleaseSigning = providers.gradleProperty("pixelplayer.disableReleaseSigning")
    .getOrElse("false")
    .toBoolean()
val hasReleaseSigningConfig = !disableReleaseSigning &&
    releaseSigningStoreFile.isFile &&
    keystoreProperties.getProperty("storePassword") != null &&
    keystoreProperties.getProperty("keyAlias") != null &&
    keystoreProperties.getProperty("keyPassword") != null

// A release that cannot be signed must not come out of the build as an unsigned file nobody notices: asking for one without signing fails
// here, saying what is missing, unless `-Ppixelplayer.disableReleaseSigning=true` says an unsigned artifact is what is wanted (the gates,
// the F-Droid recipe). Decided from the tasks asked for, like `requestsBundle` below; the aggregate tasks build the release as well.
val requestsSignedRelease = gradle.startParameter.taskNames.map { it.substringAfterLast(':').lowercase() }.any { task ->
    task in setOf("assemble", "bundle", "build") ||
        (task.contains("release") && listOf("assemble", "bundle", "package", "install", "publish", "sign").any { task.startsWith(it) })
}
if (requestsSignedRelease && !disableReleaseSigning && !hasReleaseSigningConfig) {
    val problem = when {
        !rootProject.file("keystore.properties").isFile -> "there is no keystore.properties in ${rootProject.projectDir}"
        !releaseSigningStoreFile.isFile -> "the key store ${releaseSigningStoreFile.name} (keystore.properties: storeFile) does not exist"
        else -> "keystore.properties does not give storePassword, keyAlias and keyPassword"
    }
    throw GradleException(
        "A release build was asked for, but it cannot be signed: $problem.\n" +
            "Create the key and keystore.properties as docs/release-signing.md says, or pass -Ppixelplayer.disableReleaseSigning=true " +
            "to build an unsigned artifact on purpose."
    )
}

val debugSigningStoreFile = rootProject.file(
    keystoreProperties.getProperty("debugStoreFile") ?: "signing/dap-debug.jks"
)
val hasPinnedDebugSigning = debugSigningStoreFile.isFile &&
    keystoreProperties.getProperty("debugStorePassword") != null &&
    keystoreProperties.getProperty("debugKeyAlias") != null &&
    keystoreProperties.getProperty("debugKeyPassword") != null

// ABI splits are for the APK. An Android App Bundle must be built without them: with splits on, R8's resource
// shrinker writes one shrunk-resources file per ABI and `bundleRelease` fails ("Multiple shrunk-resources files
// found ... Please disable building multiple APKs", audit A-08). Play does its own ABI splitting from the bundle
// (see `bundle { abi.enableSplit }` below), so nothing is lost. Decided from what was asked for, so that
// `./gradlew assembleRelease` and `./gradlew bundleRelease` each work with no flag. `-Ppixelplayer.enableAbiSplits=`
// still overrides. Asking for both in one invocation gives the bundle's configuration: build them separately.
val requestsBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val enableAbiSplits = providers.gradleProperty("pixelplayer.enableAbiSplits")
    .map { it.toBoolean() }
    .getOrElse(!requestsBundle)

val enableComposeCompilerReports = providers.gradleProperty("pixelplayer.enableComposeCompilerReports")
    .getOrElse("false")
    .toBoolean()

@Suppress("DEPRECATION")
android {
    namespace = "com.lostf1sh.pixelplayeross"
    compileSdk = 37

    sourceSets {
        getByName("androidTest") {
            assets.directories.add(file("$projectDir/schemas").path)
        }
    }

    androidResources {
        // Keep only the languages we actually maintain. Without this the app picks up
        // every locale our AndroidX dependencies carry, which bloats the APK and
        // makes the system language picker offer languages we have no strings for.
        //
        // English and Spanish only (B14-4). The other ten folders under res/ (ar de fr in it ko nb ru tr zh-rCN)
        // are what upstream translated of an older, smaller string set: about 1,520 of the 2,411 strings today
        // (zh-rCN 1,548, and 856 of its keys are missing), so a user in those languages saw a screen that is two
        // thirds translated and one third English. A store listing that claims twelve languages on that basis
        // would not be honest. The folders stay in the repository, unbuilt, as the starting point for a language
        // someone is prepared to finish and maintain: re-add it here once it is complete.
        localeFilters.addAll(listOf("en", "es", "sv"))
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/CONTRIBUTORS.md",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md"
            )
            pickFirsts += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt"
            )
        }
    }

    defaultConfig {
        // B35 (R6): the 1.0 identity. The Kotlin namespace above and the code packages stay `com.lostf1sh.pixelplayeross`: the application id
        // is what Android, the Device Owner, the authorities (`${applicationId}.provider`) and the media session see; the packages are not.
        // The debug build is another app (`app.polylute.debug`).
        applicationId = "app.polylute"
        minSdk = 30
        targetSdk = 37
        // Both overridable with -PAPP_VERSION_CODE / -PAPP_VERSION_NAME; gradle.properties holds the release's values (1.0.0, code 1) and
        // these are what is used when it holds none.
        versionCode = (project.findProperty("APP_VERSION_CODE") as? String)?.toInt() ?: 1
        versionName = (project.findProperty("APP_VERSION_NAME") as? String) ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasPinnedDebugSigning) {
            getByName("debug") {
                storeFile = debugSigningStoreFile
                storePassword = checkNotNull(keystoreProperties.getProperty("debugStorePassword"))
                keyAlias = checkNotNull(keystoreProperties.getProperty("debugKeyAlias"))
                keyPassword = checkNotNull(keystoreProperties.getProperty("debugKeyPassword"))
            }
        }
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseSigningStoreFile
                storePassword = checkNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = checkNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = checkNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            // Signed with the release key or not signed at all; never with `signingConfigs.debug`.
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // B21: the render tests resolve the app's own strings and its manifest under Robolectric.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            // B35: the whole suite runs in one worker JVM, and its live heap climbs to ~470 MB of the default 512 MB (GC log of the B34 tree: 89 full
            // collections, 0 headroom); 43 more tests tipped `QueueUtilsTest`'s 10k-song fixtures into OutOfMemoryError. A worker that has room.
            it.maxHeapSize = "1g"
        }
    }

    lint {
        checkReleaseBuilds = false
        // B14-4 (audit T-06). `lintDebug` is a gate, and it is green with ZERO errors: the 932 it started with were
        // 883 MissingTranslation (ten partial languages, now unbuilt: see translations-unbuilt/README.md), 15 media3
        // opt-ins, 14 non-observable locale reads, 11 Russian plural quantities, and a handful of single defects
        // (an unguarded Bluetooth toggle, a Compose configuration read, an ignored Scaffold padding), all fixed.
        // The baseline holds only what is left: warnings and hints, reviewed by category in lint-baseline.md. An
        // error never goes in it, and a new warning shows up in the report because it is not in it.
        baseline = file("lint-baseline.xml")
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            if (enableAbiSplits) {
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = false
            }
        }
    }

    bundle {
        abi.enableSplit = true
        density.enableSplit = true
        // The in-app language picker (AppLanguage) can choose English or Spanish whatever the device language is, so
        // both ship in every install: with language splits Play delivers only the device language, and a picked
        // language that was not delivered shows nothing (lint AppBundleLocaleChanges).
        language.enableSplit = false
    }
}

composeCompiler {
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")

        if (enableComposeCompilerReports) {
            val buildDir = project.layout.buildDirectory.get().asFile.absolutePath
            freeCompilerArgs.addAll(
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$buildDir/compose_compiler_reports",
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$buildDir/compose_compiler_metrics"
            )
        }

        freeCompilerArgs.addAll(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=${project.rootDir.absolutePath}/app/compose_stability.conf"
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lifecycleprocess)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.paging.common)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.ffmpeg)
    implementation(libs.androidx.media3.exoplayer.midi)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media)
    implementation(project(":decent-usb-audio-driver"))
    implementation(project(":decent-usb-audio-wrapper-media3"))
    implementation(project(":decent-media3-decoder-flac"))
    implementation(libs.coil.compose)
    implementation(libs.taglib)
    implementation(libs.jaudiotagger)
    implementation(libs.vorbisjava.core)
    implementation(libs.wavy.slider)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.timber)
    implementation(libs.smooth.corner.rect.android.compose)
    implementation(libs.reorderables)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.pinyin4j.core)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.accompanist.permissions)
    implementation(libs.capturable) {
        exclude(group = "androidx.compose.animation")
        exclude(group = "androidx.compose.foundation")
        exclude(group = "androidx.compose.material")
        exclude(group = "androidx.compose.runtime")
        exclude(group = "androidx.compose.ui")
    }

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit)
    testImplementation(libs.worktesting)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junitplatformlauncher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // B19-S4: the host measurement of what a timeline change costs the session needs the real Bundle/Uri/MediaSession classes.
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    // B21: render tests of the split screens (a composition on the JVM, under Robolectric, asserted by text). This is the
    // artifact the instrumented tests below already use, from the same BOM and the same catalog entry: no new library.
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.worktesting)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
    androidTestImplementation(libs.androidx.uiautomator)

    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // B24-Q2: names the composables inside `Recomposer:recompose` / `measureAndLayout` in a Perfetto trace. Debug only: neither
    // release nor benchmark carries a tracer, and the compiler's trace markers are removed from minified builds. The version comes
    // from the Compose BOM (it resolves to the same 1.12 as the rest of Compose); the native library is the Perfetto SDK's, at the
    // version tracing-perfetto's Java side insists on. Idle cost: one interface call and a static boolean read per composable,
    // until a trace is armed (analysis/audit-2026-09-27/batches/B24-resultado.md, Q2).
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto.binary)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
