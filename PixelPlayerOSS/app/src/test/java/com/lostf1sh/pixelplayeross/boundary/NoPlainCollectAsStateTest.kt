package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B22, Part 2: the lock screen freezes the app underneath by holding its lifecycle at CREATED
 * (`FreezeWhileCovered`), and that works because **every flow the app draws is collected with its lifecycle**.
 *
 * A plain `collectAsState()` ignores the lifecycle: it would keep recomposing the hidden app on every song change, and
 * nothing would say so. This pins the premise: there is none, and the shell does wrap the app in the freeze.
 */
class NoPlainCollectAsStateTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    @Test
    fun `no composable collects a flow without its lifecycle`() {
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { Regex("""\.collectAsState\(""").containsMatchIn(it.substringBefore("//")) } }
            .map { it.name }.toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the shell holds the app in the freeze while the lock surface is up, and lifts it as the surface leaves`() {
        val shell = File(main, "MainActivity.kt").readText()
        assertThat(shell).contains("FreezeWhileCovered(")
        assertThat(shell).contains("frozen = lockSurfaceVisible,")
        // B25-S4: and from the moment the wake cycle arms (the cover signal), not only once the surface is on screen
        assertThat(shell).contains("hidden = (lockSurfaceVisible || lockCover.covered) && !lockRevealing,")
        assertThat(shell).contains("MainAppContent(playerViewModel, mainViewModel)")
        // What the surface reads is read in its own composable, so a song change restarts it and not the shell.
        assertThat(shell).doesNotContain("collectAsStateWithLifecycle(initialValue = StablePlayerState())")
        assertThat(File(main, "presentation/lockscreen/DapLockSurface.kt").readText())
            .contains("playerViewModel.stablePlayerState")
    }
}
