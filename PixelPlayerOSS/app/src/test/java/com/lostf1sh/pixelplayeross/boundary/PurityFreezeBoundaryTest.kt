package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B15-F2: `PurityFreezeTest` tests the rule; the scheduler and the service are Android-bound, so that they use it
 * (and that nothing else can start an overlap or set a speed around it) is checked at source level.
 */
class PurityFreezeBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(path: String) = File("src/main", "$java/$path").readText().replace("\r\n", "\n")

    private val scheduler = source("data/service/player/TransitionController.kt")
    private val service = source("data/service/MusicService.kt")

    @Test
    fun `the scheduler reads the crossfade switch through the lock, before it resolves any rule or starts any overlap`() {
        assertThat(scheduler).contains("PristineModePolicy.effectiveCrossfade(enabled, locked)")
        // B16-G4: the lock as the scheduler reads it is "in force", i.e. the preference and a USB DAC.
        assertThat(scheduler).contains("PristineLockDiagnostic.inForce(")
        assertThat(scheduler).contains("userPreferencesRepository.pristineLockFlow,")
        assertThat(scheduler).contains("engine.audioRouteState.map { it.usbDacPresent }.distinctUntilChanged()")
        val gate = scheduler.indexOf("if (!isEnabled) {")
        assertThat(gate).isGreaterThan(0)
        // Everything that can start an overlap, or lets a playlist's own rule matter, is after the master switch.
        for (after in listOf("settings.mode == TransitionMode.NONE", "engine.prepareNext(transitionTarget)", "engine.performTransition(")) {
            assertThat(scheduler.indexOf(after)).isGreaterThan(gate)
        }
        // The only reader of the raw preference is the one wrapped by the lock.
        assertThat(Regex("""isCrossfadeEnabledFlow""").findAll(scheduler).count()).isEqualTo(3)
    }

    @Test
    fun `an overlap can only be started by the scheduler`() {
        val others = listOf("data/service/player/DualPlayerEngine.kt", "data/service/MusicService.kt")
        for (path in others) {
            val text = source(path)
            assertThat(Regex("""\bperformTransition\(""").findAll(text).count()).isAtMost(if (path.endsWith("Engine.kt")) 1 else 0)
        }
    }

    @Test
    fun `the player's speed is the lock's reading of the preference, and a speed set from outside is put back while locked`() {
        assertThat(service).contains("PristineModePolicy.effectiveSpeed(speed, locked)")
        assertThat(service).contains("PristineLockDiagnostic.inForce(")
        assertThat(service).contains("engine.audioRouteState.map { it.usbDacPresent }.distinctUntilChanged()")
        val apply = service.substringAfter("private fun applyPlaybackSpeed(").substringBefore("\n    }\n")
        assertThat(apply).contains("effectivePlaybackSpeed")
        assertThat(apply).doesNotContain("playbackSpeedFlow")
        // The only setter of the player's speed is applyPlaybackSpeed.
        assertThat(Regex("""\.setPlaybackSpeed\(""").findAll(service).count()).isEqualTo(1)
        assertThat(Regex("""\.setPlaybackParameters\(""").findAll(service).count()).isEqualTo(0)
        val listener = service.substringAfter("override fun onPlaybackParametersChanged(").substringBefore("override fun onIsPlayingChanged(")
        assertThat(listener).contains("playbackSpeedFrozen")
        assertThat(listener).contains("applyPlaybackSpeed(")
    }

    @Test
    fun `the route's requirements still pass through the lock, which the engine applies with a DAC`() {
        assertThat(service).contains("engine.updateProcessingRequirements(requirements, pristineLock = locked)")
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine).contains("PristineModePolicy.effectiveRequirements(rawProcessingRequirements, pristineLockPreference, usbDacPresentFact)")
    }
}
