package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The pause fade is on the critical path, and it must stay short.
 *
 * `FadingPlayer.pause()` does not pause the player and then fade — it fades and *then* pauses, so
 * the ramp is the delay between the user's finger and the music stopping. It used to share the
 * fade-in's 500 ms constant, which measured as ~604 ms from tap to the audio sink being told to
 * stop, against ~45 ms for play. Same gesture, same control, one of them ~560 ms behind the other.
 *
 * A ramp still has to exist — it is there to avoid a discontinuity at the cut — so this pins it
 * short rather than absent.
 *
 * Source assertions, like the rest of this suite: the claim is about which constant a delay is
 * derived from, which is exactly what the source says.
 */
class PauseFadeContractTest {

    private val source: String by lazy {
        val relative = "data/service/player/FadingPlayer.kt"
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        candidates.firstOrNull { it.isFile }
            ?.readText()
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }

    private fun pauseBody(): String {
        val start = source.indexOf("override fun pause()")
        assertThat(start).isGreaterThan(-1)
        // Up to the next override, so we are measuring the pause branch and not the file.
        val end = source.indexOf("override fun ", start + 1).let { if (it == -1) source.length else it }
        return source.substring(start, end)
    }

    @Test
    fun `the pause ramp has its own constant and is not the fade-in duration`() {
        assertThat(source).contains("PAUSE_FADE_DURATION_MS")
        // The regression this guards: pause going back to sharing FADE_DURATION_MS.
        assertThat(pauseBody()).doesNotContain("FADE_DURATION_MS / FADE_STEPS")
        assertThat(pauseBody()).contains("PAUSE_FADE_DURATION_MS")
    }

    @Test
    fun `the pause ramp stays inside the acceptable band`() {
        val duration = Regex("PAUSE_FADE_DURATION_MS = (\\d+)L")
            .find(source)?.groupValues?.get(1)?.toLong()
        assertThat(duration).isNotNull()

        // Measured cost of the old one: ~604 ms. The brief's scale is <=100 ms ideal, <=150 ms
        // acceptable, >200 ms a defect. Bound well clear of the defect line, and leave headroom
        // for the ~16 ms main-looper delay floor that makes real elapsed time exceed the nominal
        // duration.
        assertThat(duration!!).isAtMost(150L)
    }

    @Test
    fun `the step count cannot stretch the ramp back out`() {
        val steps = Regex("PAUSE_FADE_STEPS = (\\d+)")
            .find(source)?.groupValues?.get(1)?.toInt()
        assertThat(steps).isNotNull()

        val duration = Regex("PAUSE_FADE_DURATION_MS = (\\d+)L")
            .find(source)?.groupValues?.get(1)?.toLong()!!

        // Each step is a real `delay`, and each `delay` costs about a frame on the main looper.
        // A large step count silently re-creates the original problem even with a short nominal
        // duration, so bound the step count too.
        assertThat(steps!!).isAtMost(12)
        assertThat(steps * 16L).isAtMost(200L)
        assertThat(duration).isAtLeast(1L)
    }

    @Test
    fun `pause still ramps rather than cutting hard`() {
        // The fix was to shorten the ramp, not to delete it: a hard cut is what the fade prevents.
        val body = pauseBody()
        assertThat(body).contains("super.setVolume(")
        assertThat(body).contains("super.pause()")
        // Scope to the fade path itself. `pause()` also has an early-return `super.pause()` for an
        // already-stopped player, and matching that one first would make this pass vacuously.
        val fadeStart = body.indexOf("scope.launch")
        assertThat(fadeStart).isGreaterThan(-1)
        val fade = body.substring(fadeStart)

        val rampSteps = fade.indexOf("for (i in 1..")
        // Ramp steps go through rampSet, which marks them for volume observers (FadingPlayerVolumeTest).
        val silenced = fade.indexOf("rampSet(0f)")
        val pause = fade.indexOf("super.pause()")
        assertThat(rampSteps).isGreaterThan(-1)
        assertThat(silenced).isGreaterThan(-1)
        assertThat(pause).isGreaterThan(-1)

        // The fade runs, the volume is zeroed, and only then is the player paused — otherwise the
        // ramp would be inaudible. The logical volume is restored after the pause so the next play
        // resumes at the right level.
        assertThat(silenced).isGreaterThan(rampSteps)
        assertThat(pause).isGreaterThan(silenced)
        assertThat(fade.indexOf("rampSet(startVolume)")).isGreaterThan(pause)
    }

    @Test
    fun `play remains immediate, which is the whole point of the asymmetry`() {
        val start = source.indexOf("override fun play()")
        val end = source.indexOf("override fun pause()")
        val body = source.substring(start, end)
        // `super.play()` must come before the ramp, not after it.
        val play = body.indexOf("super.play()")
        val loop = body.indexOf("for (i in 1..")
        assertThat(play).isGreaterThan(-1)
        assertThat(loop).isGreaterThan(play)
    }
}
