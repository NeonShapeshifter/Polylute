package com.lostf1sh.pixelplayeross.data.service.player

import android.media.AudioFormat
import android.media.AudioTrack
import timber.log.Timber

/** Maintenance-only probe for the platform AudioTrack PCM_FLOAT capability. */
object PcmFloatCapabilityProbe {

    @Volatile private var cachedResult: Boolean? = null

    fun isSupported(): Boolean {
        cachedResult?.let { return it }
        return runCheck().also {
            cachedResult = it
            Timber.tag("PcmFloatCapability").i("PCM_FLOAT AudioTrack supported: $it")
        }
    }

    private fun runCheck(): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(
            44_100,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0 || minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
            Timber.tag("PcmFloatCapability").w("getMinBufferSize rejected PCM_FLOAT (result=$minBuf)")
            return false
        }

        return try {
            val track = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(44_100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            if (!initialized) {
                Timber.tag("PcmFloatCapability").w("AudioTrack created but state != INITIALIZED")
            }
            initialized
        } catch (e: Exception) {
            Timber.tag("PcmFloatCapability").w(e, "AudioTrack PCM_FLOAT creation threw")
            false
        }
    }
}
