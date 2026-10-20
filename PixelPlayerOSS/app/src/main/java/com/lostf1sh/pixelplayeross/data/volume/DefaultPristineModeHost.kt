package com.lostf1sh.pixelplayeross.data.volume

import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The app side of [PristineModeController] (F-02). */
@Singleton
class DefaultPristineModeHost @Inject constructor(
    private val engine: DualPlayerEngine,
    private val safetyVolume: SafetyVolumeController,
    private val preferences: UserPreferencesRepository,
) : PristineModeHost {

    override fun gainContributors(): Triple<Float, Float?, Float> {
        val gain = engine.gainModel.value
        return Triple(gain.userDigitalGain, gain.replayGainFactor, gain.profileCompensation)
    }

    override fun isDirectActive(): Boolean = engine.audioRouteState.value.actualBackend == AudioBackend.USB_DIRECT

    override fun hardwareVolume(): PristineModePolicy.HardwareVolume? = safetyVolume.pristineHardwareVolume()

    override fun ceilingStep(): Int? = safetyVolume.pristineCeilingStep()

    override fun setHardwareStep(step: Int): Boolean = safetyVolume.setHardwareStepVerified(step)

    /**
     * The unlocked requirements reach the engine through the service's flow, and the sink then
     * realigns to PROCESSED. The DAC may only come back up once that has happened.
     */
    override suspend fun awaitContributorsApplied(): Boolean =
        withTimeoutOrNull(AWAIT_PROCESSED_MS) {
            while (!(engine.currentProcessingRequirements().requiresPcmProcessing &&
                    engine.audioRouteState.value.signalMode == DirectSignalMode.PROCESSED)
            ) {
                delay(POLL_MS)
            }
            true
        } ?: false

    override suspend fun lockState(): Pair<Boolean, Int> = preferences.pristineLockState()

    override suspend fun persist(locked: Boolean, loweredSteps: Int) =
        preferences.setPristineLock(locked, loweredSteps)

    private companion object {
        const val AWAIT_PROCESSED_MS = 3_000L
        const val POLL_MS = 50L
    }
}
