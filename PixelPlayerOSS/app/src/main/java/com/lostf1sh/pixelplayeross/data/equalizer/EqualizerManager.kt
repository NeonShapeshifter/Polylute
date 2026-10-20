@file:Suppress("DEPRECATION")
package com.lostf1sh.pixelplayeross.data.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Android AudioEffect *extras* for the equalizer screen: BassBoost, Virtualizer and
 * LoudnessEnhancer, attached to the player's audio session.
 *
 * ## What this class deliberately does not do
 *
 * It does not create `android.media.audiofx.Equalizer`. Phase 8.5 moved the Graphic EQ to a real
 * software DSP stage ([com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqProcessor])
 * because the device effect could not represent ten (or fifteen) independent filters — it exposed a
 * fixed, small band count and the old code averaged ten UI bands onto it, so moving one slider
 * silently changed several.
 *
 * Keeping both would have meant two equalizers processing the same PCM, which is exactly the
 * double-processing this phase forbids. So the platform equalizer is gone from the product path,
 * not merely disabled, and nothing here can reintroduce it by accident: there is no band state, no
 * band count and no level mapping left in this class.
 *
 * Three effects therefore remain, and they remain **explicit and user-visible**: each is a separate
 * toggle with its own strength in the equalizer screen, owned by the headphone profile. None of them
 * is enabled implicitly, and none is switched on by Graphic EQ being switched on.
 *
 * USB Direct is unaffected either way: it never reaches the Android audio sink these effects attach
 * to.
 *
 * Thread-safe: all effect operations are expected on the main thread.
 * Crossfade compatible: effects attach to the audio session, not to a player instance.
 */
@Suppress("DEPRECATION")
@Singleton
class EqualizerManager @Inject constructor() {

    companion object {
        private const val TAG = "EqualizerManager"
        private const val MAX_LOUDNESS_GAIN_MB = 1000
        private const val MAX_STRENGTH = 1000
    }

    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var currentAudioSessionId: Int = 0

    /** True once at least one Android effect object is attached to a live session. */
    val isAttached: Boolean
        get() = currentAudioSessionId != 0 &&
            (bassBoost != null || virtualizer != null || loudnessEnhancer != null)

    /**
     * Whether the Android effect layer has any reason to hold a session at all.
     *
     * The Graphic EQ is intentionally absent from this expression: software DSP needs no audio
     * session, so an EQ-only configuration leaves the Android effect layer completely detached
     * rather than holding an idle session open.
     */
    val hasAnyEnabledEffects: Boolean
        get() = _bassBoostEnabled.value ||
            _virtualizerEnabled.value ||
            _loudnessEnhancerEnabled.value

    private val _bassBoostEnabled = MutableStateFlow(false)
    val bassBoostEnabled: StateFlow<Boolean> = _bassBoostEnabled.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _virtualizerEnabled = MutableStateFlow(false)
    val virtualizerEnabled: StateFlow<Boolean> = _virtualizerEnabled.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    private val _loudnessEnhancerEnabled = MutableStateFlow(false)
    val loudnessEnhancerEnabled: StateFlow<Boolean> = _loudnessEnhancerEnabled.asStateFlow()

    private val _loudnessEnhancerStrength = MutableStateFlow(0)
    val loudnessEnhancerStrength: StateFlow<Int> = _loudnessEnhancerStrength.asStateFlow()

    private var isBassBoostSupportedGlobal = false
    private var isVirtualizerSupportedGlobal = false
    private var effectsDisabledForProcess = false
    private var effectsDisableReason: String? = null

    init {
        checkDeviceSupport()
    }

    private fun checkDeviceSupport() {
        try {
            val effects = android.media.audiofx.AudioEffect.queryEffects()
            isBassBoostSupportedGlobal = effects.any { it.type == android.media.audiofx.AudioEffect.EFFECT_TYPE_BASS_BOOST }
            isVirtualizerSupportedGlobal = effects.any { it.type == android.media.audiofx.AudioEffect.EFFECT_TYPE_VIRTUALIZER }
            Timber.tag(TAG).d("Global Support Check - BassBoost: $isBassBoostSupportedGlobal, Virtualizer: $isVirtualizerSupportedGlobal")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query global audio effects")
        }
    }

    private fun markBassBoostUnavailable(reason: String) {
        if (!isBassBoostSupportedGlobal && bassBoost == null) return

        isBassBoostSupportedGlobal = false
        _bassBoostEnabled.value = false
        bassBoost?.runCatching { release() }
        bassBoost = null
        Timber.tag(TAG).w("BassBoost disabled for this process: %s", reason)
    }

    private fun markVirtualizerUnavailable(reason: String) {
        if (!isVirtualizerSupportedGlobal && virtualizer == null) return

        isVirtualizerSupportedGlobal = false
        _virtualizerEnabled.value = false
        virtualizer?.runCatching { release() }
        virtualizer = null
        Timber.tag(TAG).w("Virtualizer disabled for this process: %s", reason)
    }

    /**
     * Attaches the Android effects to an audio session ID.
     * Call this when the player is created or swapped during crossfade.
     */
    suspend fun attachToAudioSession(audioSessionId: Int) {
        if (effectsDisabledForProcess) {
            Timber.tag(TAG).d(
                "Skipping attachToAudioSession($audioSessionId): audio effects disabled (%s)",
                effectsDisableReason ?: "unknown reason"
            )
            return
        }

        if (audioSessionId == 0) {
            Timber.tag(TAG).w("Invalid audio session ID: 0")
            return
        }

        if (currentAudioSessionId == audioSessionId && isAttached) {
            Timber.tag(TAG).d("Already attached to session $audioSessionId")
            return
        }

        Timber.tag(TAG).d("Attaching to audio session: $audioSessionId")
        release()

        try {
            val maxRetries = 3
            var retryCount = 0

            while (bassBoost == null && retryCount < maxRetries) {
                if (!isBassBoostSupportedGlobal) break
                try {
                    bassBoost = BassBoost(0, audioSessionId).apply {
                        enabled = _bassBoostEnabled.value
                        if (strengthSupported) {
                            setStrength(_bassBoostStrength.value.coerceIn(0, MAX_STRENGTH).toShort())
                        }
                    }
                    if (bassBoost != null) Timber.tag(TAG).d("BassBoost initialized on attempt ${retryCount + 1}")
                } catch (e: Exception) {
                    Timber.tag(TAG).w("BassBoost init failed (attempt ${retryCount + 1}): ${e.message}")
                    if (retryCount < maxRetries - 1) kotlinx.coroutines.delay(300)
                }
                retryCount++
            }
            if (bassBoost == null && isBassBoostSupportedGlobal) {
                markBassBoostUnavailable("No effect engine was created for audio session $audioSessionId after $maxRetries attempts")
            }

            retryCount = 0
            while (virtualizer == null && retryCount < maxRetries) {
                if (!isVirtualizerSupportedGlobal) break
                try {
                    virtualizer = Virtualizer(0, audioSessionId).apply {
                        enabled = _virtualizerEnabled.value
                        if (strengthSupported) {
                            setStrength(_virtualizerStrength.value.coerceIn(0, MAX_STRENGTH).toShort())
                        }
                    }
                    if (virtualizer != null) Timber.tag(TAG).d("Virtualizer initialized on attempt ${retryCount + 1}")
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Virtualizer init failed (attempt ${retryCount + 1}): ${e.message}")
                    if (retryCount < maxRetries - 1) kotlinx.coroutines.delay(300)
                }
                retryCount++
            }
            if (virtualizer == null && isVirtualizerSupportedGlobal) {
                markVirtualizerUnavailable("No effect engine was created for audio session $audioSessionId after $maxRetries attempts")
            }

            loudnessEnhancer = try {
                android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                    enabled = _loudnessEnhancerEnabled.value
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "LoudnessEnhancer not supported on this device")
                null
            }

            currentAudioSessionId = audioSessionId
            applyCurrentEffectStateToAttachedEffects()

            Timber.tag(TAG).d(
                "Audio effects attached. BassBoost: ${bassBoost != null}, Virtualizer: ${virtualizer != null}, Loudness: ${loudnessEnhancer != null}"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize audio effects")
            release()
        }
    }

    suspend fun attachToAudioSessionIfNeeded(audioSessionId: Int) {
        if (!hasAnyEnabledEffects) {
            Timber.tag(TAG).d(
                "Skipping attachToAudioSession($audioSessionId): all audio effects are disabled"
            )
            releaseIfUnused()
            return
        }

        attachToAudioSession(audioSessionId)
    }

    /**
     * Sets bass boost enabled state.
     */
    fun setBassBoostEnabled(enabled: Boolean) {
        if (!isBassBoostSupportedGlobal) {
            _bassBoostEnabled.value = false
            releaseIfUnused()
            return
        }
        _bassBoostEnabled.value = enabled
        try {
            bassBoost?.enabled = enabled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set bass boost enabled")
        }
        releaseIfUnused()
    }

    /**
     * Sets bass boost strength (0-1000).
     */
    fun setBassBoostStrength(strength: Int) {
        if (!isBassBoostSupportedGlobal) return

        val clampedStrength = strength.coerceIn(0, MAX_STRENGTH)
        _bassBoostStrength.value = clampedStrength

        try {
            bassBoost?.apply {
                if (strengthSupported) {
                    setStrength(clampedStrength.toShort())
                }
            }
            Timber.tag(TAG).d("Bass boost strength: $clampedStrength")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set bass boost")
        }
    }

    /**
     * Sets virtualizer enabled state.
     */
    fun setVirtualizerEnabled(enabled: Boolean) {
        if (!isVirtualizerSupportedGlobal) {
            _virtualizerEnabled.value = false
            releaseIfUnused()
            return
        }
        _virtualizerEnabled.value = enabled
        try {
            virtualizer?.enabled = enabled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set virtualizer enabled")
        }
        releaseIfUnused()
    }

    /**
     * Sets virtualizer (surround) strength (0-1000).
     */
    fun setVirtualizerStrength(strength: Int) {
        if (!isVirtualizerSupportedGlobal) return

        val clampedStrength = strength.coerceIn(0, MAX_STRENGTH)
        _virtualizerStrength.value = clampedStrength

        try {
            virtualizer?.apply {
                if (strengthSupported) {
                    setStrength(clampedStrength.toShort())
                }
            }
            Timber.tag(TAG).d("Virtualizer strength: $clampedStrength")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set virtualizer")
        }
    }

    /**
     * Sets loudness enhancer enabled state.
     */
    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        _loudnessEnhancerEnabled.value = enabled
        try {
            loudnessEnhancer?.enabled = enabled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set loudness enhancer enabled")
        }
        releaseIfUnused()
    }

    /**
     * Sets loudness enhancer strength (gain in mB).
     * 0 to 1000mB (10dB) is used as a stable cross-device range.
     */
    fun setLoudnessEnhancerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, MAX_LOUDNESS_GAIN_MB)
        _loudnessEnhancerStrength.value = clampedStrength

        try {
            loudnessEnhancer?.setTargetGain(clampedStrength)
            Timber.tag(TAG).d("Loudness enhancer strength: $clampedStrength")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set loudness enhancer")
        }
    }

    /**
     * Restores the Android effect extras from saved preferences.
     *
     * The Graphic EQ is absent from this signature by design — it is owned by the software DSP and
     * follows the persisted headphone EQ state directly.
     */
    fun restoreState(
        bassBoostEnabled: Boolean,
        bassBoostStrength: Int,
        virtualizerEnabled: Boolean,
        virtualizerStrength: Int,
        loudnessEnabled: Boolean,
        loudnessStrength: Int
    ) {
        _bassBoostEnabled.value = bassBoostEnabled
        _bassBoostStrength.value = bassBoostStrength.coerceIn(0, MAX_STRENGTH)
        _virtualizerEnabled.value = virtualizerEnabled
        _virtualizerStrength.value = virtualizerStrength.coerceIn(0, MAX_STRENGTH)
        _loudnessEnhancerEnabled.value = loudnessEnabled
        _loudnessEnhancerStrength.value = loudnessStrength.coerceIn(0, MAX_LOUDNESS_GAIN_MB)

        if (!hasAnyEnabledEffects) {
            releaseIfUnused()
            return
        }
        if (currentAudioSessionId != 0) {
            applyCurrentEffectStateToAttachedEffects()
        }
    }

    private fun releaseIfUnused() {
        if (!hasAnyEnabledEffects && isAttached) {
            Timber.tag(TAG).d("Releasing audio effects because all effect toggles are disabled")
            release()
        }
    }

    private fun applyCurrentEffectStateToAttachedEffects() {
        try {
            bassBoost?.apply {
                enabled = _bassBoostEnabled.value
                if (strengthSupported) {
                    setStrength(_bassBoostStrength.value.coerceIn(0, MAX_STRENGTH).toShort())
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying bass boost state")
        }

        try {
            virtualizer?.apply {
                enabled = _virtualizerEnabled.value
                if (strengthSupported) {
                    setStrength(_virtualizerStrength.value.coerceIn(0, MAX_STRENGTH).toShort())
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying virtualizer state")
        }

        try {
            loudnessEnhancer?.apply {
                setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                enabled = _loudnessEnhancerEnabled.value
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying loudness state")
        }
    }

    /**
     * Checks if bass boost is supported on this device.
     */
    fun isBassBoostSupported(): Boolean = isBassBoostSupportedGlobal

    /**
     * Checks if virtualizer is supported on this device.
     */
    fun isVirtualizerSupported(): Boolean = isVirtualizerSupportedGlobal

    /**
     * Checks if loudness enhancer is supported on this device.
     */
    fun isLoudnessEnhancerSupported(): Boolean = loudnessEnhancer != null || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT

    /**
     * Releases all audio effect resources.
     */
    fun release() {
        try {
            bassBoost?.release()
            virtualizer?.release()
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error releasing audio effects")
        }
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        currentAudioSessionId = 0
        Timber.tag(TAG).d("Audio effects released")
    }
}
