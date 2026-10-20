package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteFacts
import com.lostf1sh.pixelplayeross.data.service.player.AudioRoutePolicy
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqConflict
import com.lostf1sh.pixelplayeross.presentation.viewmodel.dacEqConflict
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B30-P4: the DAC's EQ needs no app DSP, so using it never moves playback to Android. Where another setting forces the Android route
 * today (crossfade, a speed, an Android effect), B30 **says so** and does not change the policy that decides it.
 */
class DacEqRouteConflictTest {
    private fun facts(processing: AudioProcessingRequirements) =
        AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true, processing = processing)

    @Test
    fun `the DAC's EQ is not a processing requirement, so it never takes a Direct stream off Direct`() {
        // Whatever the DAC's EQ is, the route is decided from the listening state alone.
        val none = AudioRoutePolicy.decide(facts(AudioProcessingRequirements()))
        assertThat(none.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(none.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
        val fields = AudioProcessingRequirements::class.java.declaredFields.map { it.name }
        assertThat(fields.none { it.contains("dac", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `crossfade still forces the Android route, with the same reason as before B30`() {
        val decision = AudioRoutePolicy.decide(facts(AudioProcessingRequirements(crossfade = true)))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID)
        assertThat(AudioRoutePolicy.decide(facts(AudioProcessingRequirements(nonDefaultSpeed = true))).reason)
            .isEqualTo(AudioRouteReason.SPEED_REQUIRES_ANDROID)
        assertThat(AudioRoutePolicy.decide(facts(AudioProcessingRequirements(androidSessionEffect = true))).reason)
            .isEqualTo(AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID)
    }

    @Test
    fun `no part of the route, the signal controller or the service reads the DAC's EQ`() {
        val base = "src/main/java/com/lostf1sh/pixelplayeross"
        for (file in listOf(
            "data/service/player/AudioRouteModel.kt", "data/service/player/DirectSignalController.kt",
            "data/service/player/DualPlayerEngine.kt", "data/service/MusicService.kt",
        )) {
            val text = File("$base/$file").readText()
            assertThat(text).doesNotContain("DacEq")
            assertThat(text).doesNotContain("dacEq")
        }
    }

    private fun androidRoute(reason: AudioRouteReason) = AudioRouteState(
        actualBackend = AudioBackend.ANDROID_AUDIO, transition = AudioRouteTransition.ANDROID_ACTIVE, reason = reason, usbDacPresent = true,
    )

    @Test
    fun `crossfade on with a DAC shows the conflict text, whatever the DAC's EQ is`() {
        for (eq in DacEqLabel.entries) {
            val status = AudioStatus(route = androidRoute(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID), dacEq = eq)
            assertThat(status.dacEqConflict()).isEqualTo(DacEqConflict.CROSSFADE)
        }
        assertThat(AudioStatus(route = androidRoute(AudioRouteReason.SPEED_REQUIRES_ANDROID)).dacEqConflict()).isEqualTo(DacEqConflict.SPEED)
        assertThat(AudioStatus(route = androidRoute(AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID)).dacEqConflict())
            .isEqualTo(DacEqConflict.ANDROID_EFFECT)
    }

    @Test
    fun `no conflict is said when nothing is in the way, or the reason is not a listening choice`() {
        assertThat(AudioStatus(route = androidRoute(AudioRouteReason.NO_USB_DAC).copy(usbDacPresent = false)).dacEqConflict()).isNull()
        assertThat(AudioStatus(route = androidRoute(AudioRouteReason.MAINTENANCE_FORCE_ANDROID)).dacEqConflict()).isNull()
        assertThat(AudioStatus(route = androidRoute(AudioRouteReason.USB_PERMISSION_REQUIRED)).dacEqConflict()).isNull()
        val direct = AudioRouteState(
            actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
            reason = AudioRouteReason.DIRECT_PRISTINE, usbDacPresent = true,
        )
        assertThat(AudioStatus(route = direct).dacEqConflict()).isNull()
    }

    @Test
    fun `the DAC's EQ is never called applied by the screen when the DAC does not report it on`() {
        // The status line's "applied" comes only from the DAC's own verdict (see DacEqScreenControllerTest); here: the label that
        // feeds every other surface is withheld for an EQ the DAC did not report on.
        for (verdict in listOf(DacEqVerdict.OFF, DacEqVerdict.FLAT, DacEqVerdict.UNKNOWN)) {
            assertThat(DacEqLabel.of(verdict)).isNotEqualTo(DacEqLabel.ON)
        }
        assertThat(DacEqLabel.of(DacEqVerdict.ON)).isEqualTo(DacEqLabel.ON)
    }
}
