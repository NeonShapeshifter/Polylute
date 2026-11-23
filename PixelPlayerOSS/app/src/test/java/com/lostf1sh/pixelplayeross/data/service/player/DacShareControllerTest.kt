package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.data.service.player.DacShareController.PauseOutcome
import org.junit.jupiter.api.Test

/**
 * B39-A7: "Share the DAC with other apps". The decisions, with a stand-in for the engine that records what it was asked to do.
 *
 * What the owner decided, one test each: another app's audio-focus request (transient or not) pauses Polylute and releases the DAC; a
 * paused or idle Polylute releases it; play takes full focus first, then the DAC, then resumes; Purity never releases at a DAP level; the
 * switch off is today's behaviour. B68 (option D): in app mode Purity releases too.
 *
 * The stand-in defaults to the owner's S22: app mode, Purity off ("Resonance" = the Purity choice off, with or without the app's DSP).
 */
class DacShareControllerTest {
    private class FakeHost : DacShareController.Host {
        override var enabled = true
        override var purityInForce = false
        var mode: EffectiveProductMode? = EffectiveProductMode.APP
        var modeReads = 0
        override fun effectiveProductMode(): EffectiveProductMode? {
            modeReads++
            return mode
        }
        var focusGranted = true
        var directReadyNow = false
        var levelConfirmedNow = false
        val events = mutableListOf<String>()
        val refusals = mutableListOf<DacShareController.ClaimRefusal>()
        var automaticHeld = 0
        var releaseNotConfirmedCount = 0
        var released: Boolean? = null

        override fun setReleased(released: Boolean) {
            this.released = released
            events += if (released) "release" else "claim"
        }

        override fun requestFocus(): Boolean {
            events += "focus:${if (focusGranted) "granted" else "refused"}"
            return focusGranted
        }

        override fun resume() { events += "resume" }
        override fun directReady(): Boolean = directReadyNow
        override fun userPlayReady(): Boolean = levelConfirmedNow
        override fun refused(reason: DacShareController.ClaimRefusal) { refusals += reason }
        override fun automaticResumeHeld() { automaticHeld++ }
        override fun releaseNotConfirmed() { releaseNotConfirmedCount++ }

        /** Direct is carrying the stream at a level the conservative verdict accepts too (a ceiling, or at most -30 dB). */
        fun becomeSafe() {
            directReadyNow = true
            levelConfirmedNow = true
        }
    }

    private val host = FakeHost()
    private val share = DacShareController(host)

    // ── another app takes the floor ──────────────────────────────────────────────────────────────

    @Test
    fun `a transient focus loss releases the DAC`() {
        share.onFocusLost(permanent = false)
        assertThat(share.released).isTrue()
        assertThat(host.events).containsExactly("release")
    }

    @Test
    fun `a permanent focus loss releases the DAC too, and nothing is owed afterwards`() {
        share.onFocusLost(permanent = true)
        assertThat(share.released).isTrue()
        // a focus gain that finds nothing owed leaves it released and paused
        assertThat(share.onFocusGained(resumeOwedByTheEngine = false)).isTrue()
        assertThat(host.events).containsExactly("release")
    }

    @Test
    fun `a second loss while it is already released changes nothing`() {
        share.onFocusLost(permanent = false)
        share.onFocusLost(permanent = true)
        assertThat(host.events).containsExactly("release")
    }

    @Test
    fun `when the other app is done and a resume is owed, the DAC is taken first and playback starts once it is Direct and safe`() {
        share.onFocusLost(permanent = false)
        assertThat(share.onFocusGained(resumeOwedByTheEngine = true)).isTrue()
        assertThat(host.events).containsExactly("release", "claim").inOrder()
        // not before Direct is active with a bounded level
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).containsExactly("release", "claim", "resume").inOrder()
        // once
        share.onRouteState()
        assertThat(host.events.count { it == "resume" }).isEqualTo(1)
    }

    @Test
    fun `a focus gain with the DAC still held is the engine's own resume, as it always was`() {
        assertThat(share.onFocusGained(resumeOwedByTheEngine = true)).isFalse()
        assertThat(host.events).isEmpty()
    }

    // ── paused or idle ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pause releases the DAC after the grace, not at once`() {
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.RELEASE_AFTER_GRACE)
        assertThat(host.events).isEmpty()
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.released).isTrue()
        assertThat(host.events).containsExactly("release")
    }

    @Test
    fun `a pause that ended in playing again does not release`() {
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.RELEASE_AFTER_GRACE)
        share.onPauseGraceElapsed(playing = true)
        assertThat(share.released).isFalse()
        assertThat(host.events).isEmpty()
    }

    @Test
    fun `an already released Polylute has nothing more to release on a pause`() {
        share.onFocusLost(permanent = false)
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.NONE)
    }

    // ── play: focus, then the DAC, then playback ─────────────────────────────────────────────────

    @Test
    fun `play with the DAC released asks for full focus, takes the DAC, holds the play, and resumes once Direct is safe`() {
        share.onFocusLost(permanent = true)
        host.events.clear()

        assertThat(share.onPlayRequested()).isFalse()   // held: the other app must pause before the DAC is touched
        assertThat(host.events).containsExactly("focus:granted", "claim").inOrder()
        // a second play while claiming is held too, and does not start another claim
        assertThat(share.onPlayRequested()).isFalse()
        assertThat(host.events).containsExactly("focus:granted", "claim").inOrder()

        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).containsExactly("focus:granted", "claim", "resume").inOrder()
        // and the next play goes straight through
        assertThat(share.onPlayRequested()).isTrue()
    }

    @Test
    fun `focus comes before the DAC, in every order of events`() {
        share.onFocusLost(permanent = false)
        host.events.clear()
        share.onPlayRequested()
        assertThat(host.events.indexOf("focus:granted")).isLessThan(host.events.indexOf("claim"))
    }

    @Test
    fun `play while another app keeps the floor claims nothing and plays nothing`() {
        share.onFocusLost(permanent = false)
        host.events.clear()
        host.focusGranted = false
        assertThat(share.onPlayRequested()).isFalse()
        assertThat(host.events).containsExactly("focus:refused")
        assertThat(share.released).isTrue()
        assertThat(share.claiming).isFalse()
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")
    }

    @Test
    fun `a claim that does not complete leaves playback paused, with no resume on an unconfirmed level`() {
        share.onFocusLost(permanent = false)
        share.onPlayRequested()
        share.onClaimTimeout(directActive = false)
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")
        assertThat(share.claiming).isFalse()
    }

    @Test
    fun `play with the DAC held goes straight through`() {
        assertThat(share.onPlayRequested()).isTrue()
        assertThat(host.events).isEmpty()
    }

    // ── Purity never releases at a DAP level, and the switch off is today's behaviour ──────────────

    @Test
    fun `Purity at a DAP level never releases, whatever the switch says and whoever asks`() {
        host.purityInForce = true
        host.mode = EffectiveProductMode.DEDICATED_ACTIVE
        assertThat(share.active).isFalse()
        share.onFocusLost(permanent = false)
        share.onFocusLost(permanent = true)
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.NONE)
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.onPlayRequested()).isTrue()
        assertThat(share.released).isFalse()
        assertThat(host.released).isNull()
        assertThat(host.events).isEmpty()
    }

    @Test
    fun `with the switch off nothing changes from today, in Resonance too`() {
        host.enabled = false
        share.onFocusLost(permanent = false)
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.NONE)
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.onPlayRequested()).isTrue()
        assertThat(share.onFocusGained(resumeOwedByTheEngine = true)).isFalse()
        assertThat(host.events).isEmpty()
    }

    @Test
    fun `turning the switch off, or going to Purity at a DAP level, while the DAC is released takes it back, without playing`() {
        share.onFocusLost(permanent = false)
        host.events.clear()
        host.enabled = false
        assertThat(share.onSettingsChanged(paused = true)).isEqualTo(PauseOutcome.NONE)
        assertThat(host.events).containsExactly("claim")
        assertThat(share.released).isFalse()

        host.enabled = true
        host.mode = EffectiveProductMode.LAUNCHER
        share.onFocusLost(permanent = false)
        host.events.clear()
        host.purityInForce = true
        assertThat(share.onSettingsChanged(paused = true)).isEqualTo(PauseOutcome.NONE)
        assertThat(host.events).containsExactly("claim")
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")
    }

    @Test
    fun `a settings change with the DAC held while playing changes nothing`() {
        assertThat(share.onSettingsChanged(paused = false)).isEqualTo(PauseOutcome.NONE)
        assertThat(host.events).isEmpty()
    }

    // ── the whole conversation, as the owner described it ────────────────────────────────────────

    @Test
    fun `a voice note arrives in the middle of a song, ends, and the song comes back in Resonance`() {
        // playing, DAC held. WhatsApp asks for transient focus: the engine paused and owes a resume.
        share.onFocusLost(permanent = false)
        // the voice note ends: focus returns, the DAC is taken, the song resumes once Direct is safe
        share.onFocusGained(resumeOwedByTheEngine = true)
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).containsExactly("release", "claim", "resume").inOrder()
        assertThat(share.released).isFalse()
    }

    @Test
    fun `the listener pauses, a video plays through the DAC, and play takes everything back`() {
        share.onPaused()
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.released).isTrue()
        host.events.clear()
        assertThat(share.onPlayRequested()).isFalse()
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).containsExactly("focus:granted", "claim", "resume").inOrder()
    }

    // ── B40-P1: a play the listener asks for is not judged by the automatic-resume verdict ──────────

    @Test
    fun `the S22 sequence - the DAC is back at minus 21 dB with no ceiling, and the listener's play starts at once`() {
        // 02:12:57 a video takes the floor: pause, release. 02:13:00.94 the video ends: focus gain, the engine owes a resume.
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)
        // 02:13:01.26 Direct is ACTIVE and paused. The DS2 reads -21 dB, no ceiling: the level is confirmed, the automatic verdict is HOLD.
        host.levelConfirmedNow = true
        host.directReadyNow = false
        share.onRouteState()
        // the engine's own resume is held (nothing starts by itself on a loud level) ...
        assertThat(host.events).doesNotContain("resume")
        assertThat(host.automaticHeld).isEqualTo(1)
        assertThat(share.claiming).isFalse()
        // ... and 02:13:05 the listener taps play: it goes through, at once, not six times held
        assertThat(share.onPlayRequested()).isTrue()
        assertThat(share.onPlayRequested()).isTrue()
        assertThat(host.refusals).isEmpty()
    }

    @Test
    fun `a play the listener asks for during the claim starts as soon as the level is confirmed, whatever the automatic verdict says`() {
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)   // an automatic resume is owed
        assertThat(share.onPlayRequested()).isFalse()        // the listener taps before Direct is up: held, remembered
        assertThat(host.events).doesNotContain("resume")
        host.levelConfirmedNow = true                         // -21 dB, no ceiling: HOLD for an automatic resume
        host.directReadyNow = false
        share.onRouteState()
        assertThat(host.events).containsExactly("release", "claim", "resume").inOrder()
        assertThat(host.automaticHeld).isEqualTo(0)
    }

    @Test
    fun `an automatic resume holds when the level is confirmed but the conservative verdict says hold`() {
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)
        host.levelConfirmedNow = true
        host.directReadyNow = false
        share.onRouteState()
        assertThat(host.events).containsExactly("release", "claim").inOrder()
        assertThat(host.automaticHeld).isEqualTo(1)
    }

    @Test
    fun `an automatic resume still starts when the conservative verdict accepts the level`() {
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).containsExactly("release", "claim", "resume").inOrder()
    }

    @Test
    fun `nothing starts on a level nobody confirmed, and the listener is told why the claim ended`() {
        share.onFocusLost(permanent = false)
        assertThat(share.onPlayRequested()).isFalse()
        host.levelConfirmedNow = false
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")
        // Direct came up but its level was never read back: 10 s later the claim ends with that reason
        share.onClaimTimeout(directActive = true)
        assertThat(host.refusals).containsExactly(DacShareController.ClaimRefusal.LEVEL_UNCONFIRMED)
        assertThat(share.claiming).isFalse()
    }

    @Test
    fun `a claim that never got Direct says the DAC did not come back`() {
        share.onFocusLost(permanent = false)
        share.onPlayRequested()
        share.onClaimTimeout(directActive = false)
        assertThat(host.refusals).containsExactly(DacShareController.ClaimRefusal.DAC_NOT_BACK)
    }

    @Test
    fun `an automatic claim that times out says nothing, since the listener asked for nothing`() {
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)
        share.onClaimTimeout(directActive = false)
        assertThat(host.refusals).isEmpty()
    }

    @Test
    fun `a play during a claim lets itself through when the DAC is already back and confirmed`() {
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)
        host.levelConfirmedNow = true   // the route state has not been republished yet
        assertThat(share.onPlayRequested()).isTrue()
        assertThat(share.claiming).isFalse()
    }

    // ── B40-P4: a hand-back whose bus reset could not confirm the DAC's level is not a shared DAC ────

    @Test
    fun `a release whose level after the bus reset is unconfirmed is taken back, with nothing to resume, and says so`() {
        share.onFocusLost(permanent = false)
        assertThat(share.released).isTrue()
        host.events.clear()
        share.onReleaseNotConfirmed()
        assertThat(share.released).isFalse()
        assertThat(host.events).containsExactly("claim")
        assertThat(host.releaseNotConfirmedCount).isEqualTo(1)
        // and no play is owed by the take-back
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")
    }

    @Test
    fun `an unconfirmed level with nothing released changes nothing`() {
        share.onReleaseNotConfirmed()
        assertThat(host.events).isEmpty()
        assertThat(host.releaseNotConfirmedCount).isEqualTo(0)
    }

    // ── B68 (option D): app mode shares Purity too; a duck never releases; the switch on while paused starts the wait ──────

    @Test
    fun `B68 - the owner's case - app mode, Purity off, nothing for the app's DSP to do, paused - releases after the grace`() {
        // the controller is not told whether the DSP has work: "Resonance" is the Purity choice being off (B68-audit H1)
        host.purityInForce = false
        assertThat(share.active).isTrue()
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.RELEASE_AFTER_GRACE)
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.released).isTrue()
        assertThat(host.events).containsExactly("release")
    }

    @Test
    fun `B68 - Purity in app mode releases after the pause grace`() {
        host.purityInForce = true
        host.mode = EffectiveProductMode.APP
        assertThat(share.active).isTrue()
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.RELEASE_AFTER_GRACE)
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.released).isTrue()
        assertThat(host.events).containsExactly("release")
    }

    @Test
    fun `B68 - Purity in app mode releases on a permanent and on a transient focus loss`() {
        host.purityInForce = true
        share.onFocusLost(permanent = true)
        assertThat(share.released).isTrue()
        assertThat(host.events).containsExactly("release")

        val other = FakeHost().apply { purityInForce = true }
        val transient = DacShareController(other)
        transient.onFocusLost(permanent = false)
        assertThat(transient.released).isTrue()
        assertThat(other.events).containsExactly("release")
    }

    @Test
    fun `B68 - Purity in app mode is taken back by play with focus first, and resumes once the level is confirmed`() {
        host.purityInForce = true
        share.onFocusLost(permanent = true)
        host.events.clear()
        assertThat(share.onPlayRequested()).isFalse()
        host.levelConfirmedNow = true   // the ordinary Direct claim restored and read the level (B40-P1: the listener's play)
        share.onRouteState()
        assertThat(host.events).containsExactly("focus:granted", "claim", "resume").inOrder()
    }

    @Test
    fun `B68 - in DEDICATED Purity never releases - focus losses, pauses, the switch, nothing lets it go`() {
        host.purityInForce = true
        host.mode = EffectiveProductMode.DEDICATED_ACTIVE
        share.onFocusLost(permanent = true)
        share.onFocusLost(permanent = false)
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.NONE)
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.onSettingsChanged(paused = true)).isEqualTo(PauseOutcome.NONE)
        assertThat(share.released).isFalse()
        assertThat(host.events).isEmpty()
    }

    @Test
    fun `B68 - every DAP level keeps Purity, and an unknown mode is read as one`() {
        for (mode in listOf(
            EffectiveProductMode.LAUNCHER, EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_SETUP_REQUIRED,
            EffectiveProductMode.DEDICATED_ACTIVE, null,
        )) {
            val h = FakeHost().apply { purityInForce = true; this.mode = mode }
            val c = DacShareController(h)
            c.onFocusLost(permanent = false)
            assertThat(c.onPaused()).isEqualTo(PauseOutcome.NONE)
            assertThat(c.released).isFalse()
            assertThat(h.events).isEmpty()
        }
    }

    @Test
    fun `B68 - Resonance at the Launcher level shares as before, Pinned and Dedicated never (owner 11-23)`() {
        host.purityInForce = false
        host.mode = EffectiveProductMode.LAUNCHER
        share.onFocusLost(permanent = false)
        assertThat(share.released).isTrue()
        for (mode in listOf(EffectiveProductMode.PINNED, EffectiveProductMode.DEDICATED_SETUP_REQUIRED, EffectiveProductMode.DEDICATED_ACTIVE)) {
            val h = FakeHost().apply { purityInForce = false; this.mode = mode }
            val c = DacShareController(h)
            c.onFocusLost(permanent = false)
            assertThat(c.onPaused()).isEqualTo(PauseOutcome.NONE)
            assertThat(c.released).isFalse()
            assertThat(h.events).isEmpty()
        }
    }

    @Test
    fun `B68 - the switch turning on while paused schedules the grace, and while playing it does not`() {
        host.enabled = false
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.NONE)
        host.enabled = true
        assertThat(share.onSettingsChanged(paused = true)).isEqualTo(PauseOutcome.RELEASE_AFTER_GRACE)
        assertThat(host.events).isEmpty()   // the wait, not a release at once
        share.onPauseGraceElapsed(playing = false)
        assertThat(host.events).containsExactly("release")

        val playing = FakeHost().apply { enabled = true }
        assertThat(DacShareController(playing).onSettingsChanged(paused = false)).isEqualTo(PauseOutcome.NONE)
    }

    @Test
    fun `B68 - sharing becoming allowed while paused (Purity turned off at a DAP level) schedules the grace`() {
        host.purityInForce = true
        host.mode = EffectiveProductMode.LAUNCHER
        assertThat(share.onPaused()).isEqualTo(PauseOutcome.NONE)
        host.purityInForce = false
        assertThat(share.onSettingsChanged(paused = true)).isEqualTo(PauseOutcome.RELEASE_AFTER_GRACE)
    }

    @Test
    fun `B68 - Purity turned on while released in app mode keeps it shared, at a DAP level takes it back`() {
        share.onFocusLost(permanent = false)
        host.events.clear()
        host.purityInForce = true
        assertThat(share.onSettingsChanged(paused = true)).isEqualTo(PauseOutcome.NONE)
        assertThat(share.released).isTrue()
        assertThat(host.events).isEmpty()

        host.mode = EffectiveProductMode.LAUNCHER   // the listener made Polylute the home app meanwhile: the next settings change asks again
        share.onSettingsChanged(paused = true)
        assertThat(share.released).isFalse()
        assertThat(host.events).containsExactly("claim")
        host.becomeSafe()
        share.onRouteState()
        assertThat(host.events).doesNotContain("resume")   // taken back, nothing played
    }

    @Test
    fun `B68 - the product mode is asked only when it decides - the switch on`() {
        host.enabled = false
        host.purityInForce = true
        share.onPaused()
        host.purityInForce = false
        share.onPaused()
        assertThat(host.modeReads).isEqualTo(0)
        host.enabled = true
        share.onPaused()
        assertThat(host.modeReads).isAtLeast(1)
    }
}
