package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.DirectDuckHost
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/** The app side of `DirectDuckController` (B14-3, audit A-05). */
@Singleton
class DefaultDirectDuckHost @Inject constructor(
    private val engine: DualPlayerEngine,
    private val safetyVolume: dagger.Lazy<SafetyVolumeController>,
) : DirectDuckHost {

    override fun directActive(): Boolean = engine.audioRouteState.value.let {
        it.actualBackend == AudioBackend.USB_DIRECT && it.isDirectActive
    }

    override fun signalMode(): DirectSignalMode = engine.audioRouteState.value.signalMode

    // DirectDuckController calls the host from its application IO coroutine. A duck waits there
    // for the same serial executor the listener uses to change the DAC's level.
    override fun hardwareVolume(): PristineModePolicy.HardwareVolume? = runBlocking { safetyVolume.get().pristineHardwareVolume() }

    override fun ceilingStep(): Int? = safetyVolume.get().pristineCeilingStep()

    override fun setHardwareStepTemporarily(step: Int, ticket: Long, expectedStep: Int): TemporaryWrite =
        runBlocking { safetyVolume.get().setHardwareStepTemporarily(step, ticket, expectedStep) }

    override fun setDigitalDuckGain(gain: Float) = engine.setDirectDuckGain(gain)

    override fun pauseForDuck(generation: Long) = engine.pauseForFocusDuck(generation)
}
