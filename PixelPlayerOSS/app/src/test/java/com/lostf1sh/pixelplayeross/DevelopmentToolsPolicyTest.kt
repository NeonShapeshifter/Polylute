package com.lostf1sh.pixelplayeross

import android.content.Context
import android.os.Trace
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.justRun
import io.mockk.verify
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverride
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideKind
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverridePolicy
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.utils.timedSection
import com.lostf1sh.pixelplayeross.utils.traceAsyncSection
import com.lostf1sh.pixelplayeross.utils.traceSection
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Also run on the release classpath: a persisted development control cannot silently change production playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DevelopmentToolsPolicyTest {
    @Test fun `only development variants can enable engineering instrumentation`() {
        assertThat(DevelopmentToolsPolicy.enabled("debug")).isTrue()
        assertThat(DevelopmentToolsPolicy.enabled("benchmark")).isTrue()
        for (build in listOf("release", "", "custom")) assertThat(DevelopmentToolsPolicy.enabled(build)).isFalse()
        assertThat(DevelopmentToolsPolicy.enabled()).isEqualTo(BuildConfig.BUILD_TYPE in setOf("debug", "benchmark"))
    }

    @Test fun `launch extras cannot put a public or debug app into benchmark mode`() {
        for (type in listOf("release", "debug", "")) {
            assertThat(BenchmarkLaunchPolicy.isBenchmarkLaunch(type, true)).isFalse()
        }
        assertThat(BenchmarkLaunchPolicy.isBenchmarkLaunch("benchmark", true)).isTrue()
        assertThat(BenchmarkLaunchPolicy.isBenchmarkLaunch("benchmark", false)).isFalse()
    }

    @Test fun `release keeps the OEM compatibility option and refuses every diagnostic override`() {
        MaintenanceOverride.entries.forEach {
            assertThat(MaintenanceOverridePolicy.allowed(it, "release")).isEqualTo(it.kind == MaintenanceOverrideKind.FEATURE_KILL)
            assertThat(MaintenanceOverridePolicy.allowed(it, "debug")).isTrue()
        }
    }

    @Test fun `the real store drops forbidden persisted overrides and cannot reactivate them`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("maintenance_overrides", Context.MODE_PRIVATE)
        val all = MaintenanceOverride.entries.map { it.id }.toSet()
        prefs.edit().clear().putStringSet("active_override_ids", all).commit()
        try {
            val store = MaintenanceOverrideStore(context)
            val allowed = MaintenanceOverride.entries.filter { MaintenanceOverridePolicy.allowed(it) }.map { it.id }
            assertThat(store.current().activeIds).containsExactlyElementsIn(allowed)
            assertThat(prefs.getStringSet("active_override_ids", emptySet())).containsExactlyElementsIn(allowed)
            store.clearAll()
            MaintenanceOverride.entries.forEach { store.setActive(it, true) }
            assertThat(store.current().activeIds).containsExactlyElementsIn(allowed)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `release tracing wrappers make no calls to Android tracing`() = runTest {
        mockkStatic(Trace::class)
        try {
            justRun { Trace.beginSection(any()) }
            justRun { Trace.endSection() }
            justRun { Trace.beginAsyncSection(any(), any()) }
            justRun { Trace.endAsyncSection(any(), any()) }
            var calls = 0
            traceSection("one") { ++calls }
            timedSection("two") { ++calls }
            traceAsyncSection("three") { ++calls }
            assertThat(calls).isEqualTo(3)
            val synchronous = if (DevelopmentToolsPolicy.enabled()) 2 else 0
            val asynchronous = if (DevelopmentToolsPolicy.enabled()) 1 else 0
            verify(exactly = synchronous) { Trace.beginSection(any()) }
            verify(exactly = synchronous) { Trace.endSection() }
            verify(exactly = asynchronous) { Trace.beginAsyncSection(any(), any()) }
            verify(exactly = asynchronous) { Trace.endAsyncSection(any(), any()) }
        } finally { unmockkStatic(Trace::class) }
    }

    @Test fun `tracing wrappers preserve their work and results in either build`() = runTest {
        var calls = 0
        assertThat(traceSection("test") { ++calls }).isEqualTo(1)
        assertThat(timedSection("test") { ++calls }).isEqualTo(2)
        assertThat(traceAsyncSection("test") { ++calls }).isEqualTo(3)
        assertThat(calls).isEqualTo(3)
    }
}
