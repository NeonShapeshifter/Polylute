package com.lostf1sh.pixelplayeross.data.service.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import com.lostf1sh.pixelplayeross.data.stats.AndroidOutput
import com.lostf1sh.pixelplayeross.data.stats.AndroidOutputClassifier
import com.lostf1sh.pixelplayeross.data.stats.AndroidOutputDevice

/**
 * Reads which Android output the music is going to (B28a), for the history's route fact.
 *
 * The platform call is made when the outputs change (the service refreshes this from its audio device callback) and
 * once on first use, never per song or per buffer: [current] is a field read. It is the same list, and the same
 * routed-outputs rule on API 33+, the Details sheet reads for its "Output" group.
 */
class AndroidOutputReader(private val audioManager: AudioManager) {
    private var cached: AndroidOutput? = null

    fun current(): AndroidOutput = cached ?: refresh()

    fun refresh(): AndroidOutput = AndroidOutputClassifier.classify(routedOutputs()).also { cached = it }

    private fun routedOutputs(): List<AndroidOutputDevice> {
        val devices = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES) }.getOrDefault(emptyList())
        } else {
            runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        }
        return devices.map { AndroidOutputDevice(it.type, it.productName?.toString()?.takeIf(String::isNotBlank)) }
    }

    private companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
