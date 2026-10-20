package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * F-05 / F-06 (device, 2026-10-06): `screen_off_timeout = 2147483647` and `deviceidle mState=ACTIVE`.
 *
 * The behaviour is tested as rules in `ScreenTimeoutPolicyTest` and
 * `DapDeviceControlPolicyKeepScreenOnTest`; the platform calls are Android-bound, so the wiring is
 * asserted at source level, as the rest of this area is.
 */
class ScreenTimeoutBoundaryTest {

    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun source(relative: String): String =
        File(main, relative).readText().replace("\r\n", "\n")

    private fun allSources(): List<Pair<String, String>> =
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(main).invariantSeparatorsPath to it.readText().replace("\r\n", "\n") }
            .toList()

    @Test
    fun `the app writes the system screen timeout from exactly one place, behind the preset guard`() {
        val writers = allSources().filter { (_, text) -> text.contains("Settings.System.SCREEN_OFF_TIMEOUT") }
            .filter { (_, text) -> text.contains("putLong(") || text.contains("putInt(") }
            .map { it.first }
        // FIX-2: the settings list and the drift guard both write through this one function.
        assertThat(writers).containsExactly("presentation/viewmodel/SystemScreenTimeout.kt")

        val write = source("presentation/viewmodel/SystemScreenTimeout.kt")
            .substringAfter("fun write(context: Context, timeoutMs: Long): Boolean {")
        assertThat(write.indexOf("ScreenTimeoutPolicy.isAppWritable(timeoutMs)")).isAtLeast(0)
        assertThat(write.indexOf("ScreenTimeoutPolicy.isAppWritable(timeoutMs)"))
            .isLessThan(write.indexOf("Settings.System.putLong("))

        val vm = source("presentation/viewmodel/DeviceControlViewModel.kt")
        val apply = vm.substringAfter("private fun applySystemScreenTimeout(").substringBefore("\n    }\n")
        assertThat(apply).contains("ScreenTimeoutPolicy.isAppWritable(mode)")
        assertThat(apply).contains("SystemScreenTimeout.write(context, mode)")
        assertThat(apply).doesNotContain("Settings.System.putLong(")
    }

    @Test
    fun `no option the screen offers is a value the app may not write`() {
        val options = source("presentation/screens/DapDeviceControlComponents.kt")
            .substringAfter("private fun timeoutOptions()").substringBefore("\n)\n")
        val finite = Regex("""(\d[\d_]*)L to R\.string""").findAll(options)
            .map { it.groupValues[1].replace("_", "").toLong() }.toList()
        assertThat(finite).isNotEmpty()
        assertThat(finite).containsExactlyElementsIn(listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L))
        assertThat(options).contains("DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING to R.string.dap_timeout_never_while_playing")
    }

    @Test
    fun `the keep-screen-on flag follows playing and the player being in front, and never writes the system`() {
        val activity = source("MainActivity.kt")
        val block = activity.substringAfter("private fun observeDapDisplayPolicy()").substringBefore("private fun restoreDeviceOwnerPolicy")
        assertThat(block).contains("playerViewModel.sheetState.map { it == PlayerSheetState.EXPANDED }")
        assertThat(block).contains("DapDeviceControlPolicy.shouldKeepScreenOn(timeoutMode, isPlaying, playerVisible)")
        assertThat(block).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        assertThat(block).doesNotContain("SCREEN_OFF_TIMEOUT")
    }

    @Test
    fun `the lyrics toggle releases the screen when playback pauses`() {
        val sheet = source("presentation/components/LyricsSheet.kt")
        assertThat(sheet).contains("DisposableEffect(keepScreenOn, isPlaying, lifecycleOwner)")
        assertThat(sheet).contains("DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(keepScreenOn, isPlaying)")
    }

    @Test
    fun `nothing else holds the screen on`() {
        // The window flag and the view flag are the only screen-on holders; each has one owner.
        val holders = allSources().filter { (_, text) ->
            text.contains("FLAG_KEEP_SCREEN_ON") || text.contains("keepScreenOn = true")
        }.map { it.first }
        assertThat(holders).containsExactly("MainActivity.kt", "presentation/components/LyricsSheet.kt")
    }

    @Test
    fun `F-06 the app holds no wake lock of its own, except the one timed screen wake of FIX-5`() {
        // The only CPU wake locks are Media3's, taken by setWakeMode and released by the player
        // itself when playback is not in progress. A hand-rolled lock would outlive a pause.
        //
        // FIX-5 adds exactly one: the screen wake for the Safety Max override confirmation, in
        // OverrideScreenWaker. It is the exception that proves the rule: it is acquired only with a
        // timeout (it releases itself), and only after five deliberate presses at the ceiling.
        val allowed = "data/volume/OverrideScreenWaker.kt"
        for ((path, text) in allSources()) {
            if (path == allowed) {
                assertThat(Regex("""newWakeLock\(""").findAll(text).count()).isEqualTo(1)
                // Never an unbounded acquire(): every acquire carries its timeout.
                assertThat(Regex("""acquire\(\)""").containsMatchIn(text)).isFalse()
                assertThat(text).contains("acquire(timeoutMs)")
            } else {
                assertThat(text).doesNotContain("newWakeLock(")
            }
            assertThat(text).doesNotContain("createWifiLock(")
            assertThat(text).doesNotContain("WifiManager.WifiLock")
            if (text.contains("setWakeMode(")) {
                assertThat(path).isEqualTo("data/service/player/DualPlayerEngine.kt")
            }
        }
    }

    @Test
    fun `F-06 the only wake mode the engine asks for with local media is the player's own`() {
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine).contains("setWakeMode(C.WAKE_MODE_LOCAL)")
        // A network lock only for remote schemes; the library is local files.
        assertThat(engine).contains("C.WAKE_MODE_NETWORK")
        assertThat(engine.substringAfter("private fun wakeModeFor(").substringBefore("\n    }\n"))
            .contains("REMOTE_MEDIA_SCHEMES")
    }

    @Test
    fun `a drifted screen timeout is checked on resume and at boot, and only the appliance re-applies it`() {
        val activity = source("MainActivity.kt")
        val onResume = activity.substringAfter("override fun onResume() {").substringBefore("override fun onDestroy")
        assertThat(onResume).contains("reconcileScreenTimeoutDrift(\"onResume\")")
        val reconcile = activity.substringAfter("private fun reconcileScreenTimeoutDrift(").substringBefore("private fun restoreDeviceOwnerPolicy()")
        assertThat(reconcile).contains("val dapMode = owner?.isDeviceOwner == true && owner.isPixelPlayerHome")
        assertThat(reconcile).contains("ScreenTimeoutDriftGuard.reconcile(applicationContext, stored, dapMode, trigger)")

        val receiver = source("dap/admin/DapPolicyRestoreReceiver.kt")
        assertThat(receiver).contains("intent.action == Intent.ACTION_BOOT_COMPLETED")
        assertThat(receiver).contains("trigger = \"boot\"")
        assertThat(receiver).contains("owner.isDeviceOwner && owner.isPixelPlayerHome")
        assertThat(receiver).contains("pending.finish()")
    }

    @Test
    fun `the guard writes only on REAPPLY, logs the drift with the time, and the list shows the real value`() {
        val guard = source("presentation/viewmodel/ScreenTimeoutDriftGuard.kt")
        assertThat(guard).contains("if (drift.action == ScreenTimeoutPolicy.DriftAction.REAPPLY) {")
        assertThat(guard.substringBefore("if (drift.action == ScreenTimeoutPolicy.DriftAction.REAPPLY) {"))
            .doesNotContain("SystemScreenTimeout.write(")
        assertThat(guard).contains("\"Screen timeout drifted from %d to %d; %s (trigger=%s, at=%s)\"")
        assertThat(guard).contains("\"re-applied\"")
        assertThat(guard).contains("SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\"")

        val components = source("presentation/screens/DapDeviceControlComponents.kt")
        assertThat(components).contains("val drift = state.screenTimeoutDrift")
        assertThat(components).contains("if (drift.drifted) ScreenTimeoutDriftNote(drift)")
        assertThat(source("presentation/viewmodel/DapDeviceControlPolicy.kt"))
            .contains("else -> effectiveSystemTimeoutMillis == optionMillis")
    }
}
