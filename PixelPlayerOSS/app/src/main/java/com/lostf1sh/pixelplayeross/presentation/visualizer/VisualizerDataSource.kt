package com.lostf1sh.pixelplayeross.presentation.visualizer

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes the Android audio session. Read-only, and structurally incapable of the opposite.
 *
 * The direction of information is the whole design: audio flows *into* this class and nothing flows
 * back. There is no player reference here, no command method, and no route decision — it cannot
 * change what is playing or how, because it holds nothing that could.
 *
 * The platform effect is acquired only while something is actually looking at it and released
 * explicitly. A visualizer that kept a native effect attached for the lifetime of the playback
 * service would be a permanent resource cost for a decoration nobody may be looking at.
 */
class VisualizerDataSource {

    private val _availability =
        MutableStateFlow<VisualizerAvailability>(VisualizerAvailability.UnavailableSession)
    val availability: StateFlow<VisualizerAvailability> = _availability.asStateFlow()

    private val _bands = MutableStateFlow(FloatArray(VisualizerBands.BAND_COUNT))
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    private var effect: Visualizer? = null
    private var attachedSessionId: Int = 0

    /**
     * Begin observing [sessionId], or record truthfully why that is not possible.
     *
     * [bypassesAndroidAudio] is the USB Direct case. Direct audio never exists as an Android
     * session, so there is nothing to attach to and no truthful spectrum to draw — this reports
     * that rather than attaching to an unrelated session and drawing whatever it happens to carry,
     * which would be fabricated data wearing real numbers.
     */
    @Synchronized
    fun acquire(sessionId: Int, bypassesAndroidAudio: Boolean, playing: Boolean) {
        if (bypassesAndroidAudio) {
            releaseEffect()
            _availability.value = VisualizerAvailability.UnavailableDirect
            return
        }
        if (!playing) {
            // Idle, not broken: keep nothing attached and say so.
            releaseEffect()
            _availability.value = VisualizerAvailability.Stopped
            return
        }
        if (sessionId == 0) {
            releaseEffect()
            _availability.value = VisualizerAvailability.UnavailableSession
            return
        }
        if (effect != null && attachedSessionId == sessionId) return

        releaseEffect()
        attachedSessionId = sessionId
        try {
            val visualizer = Visualizer(sessionId)
            visualizer.captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, rate: Int,
                    ) = Unit

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft == null) return
                        val next = VisualizerBands.normalise(fft)
                        val smoothed = VisualizerBands.smooth(_bands.value, next)
                        // Skip a redraw when the frame is visually the same as the last one. A
                        // held note should not keep the screen repainting.
                        if (!VisualizerBands.isVisuallyIdentical(_bands.value, smoothed)) {
                            _bands.value = smoothed
                        }
                    }
                },
                Visualizer.getMaxCaptureRate() / 4,
                false,
                true,
            )
            visualizer.enabled = true
            effect = visualizer
            _availability.value = VisualizerAvailability.Available(sessionId)
        } catch (permissionOrPlatformRefusal: Exception) {
            // The platform refuses observation without the record-audio permission, and throws
            // either UnsupportedOperationException or a RuntimeException depending on version.
            // Both mean the same thing here, and neither is a crash.
            releaseEffect()
            _availability.value = VisualizerAvailability.UnavailablePermission
        }
    }

    /** Stop observing and drop the native effect. Safe to call repeatedly. */
    @Synchronized
    fun release() {
        releaseEffect()
        attachedSessionId = 0
        _bands.value = FloatArray(VisualizerBands.BAND_COUNT)
        _availability.value = VisualizerAvailability.Stopped
    }

    /** Short decay after playback stops, so the bars fall rather than vanish. */
    @Synchronized
    fun decay() {
        val next = VisualizerBands.decay(_bands.value)
        _bands.value = next
    }

    @Synchronized
    private fun releaseEffect() {
        runCatching {
            effect?.enabled = false
            effect?.release()
        }
        effect = null
    }
}
