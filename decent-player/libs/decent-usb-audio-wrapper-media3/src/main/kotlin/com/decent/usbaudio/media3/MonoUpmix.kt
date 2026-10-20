package com.decent.usbaudio.media3

/**
 * Duplicates a mono source into both channels of the stereo transport (AUD-012).
 *
 * Mono used to be framed with one channel over a two-channel alt, so the DAC read half the data per
 * microframe as L/R. Duplication is exact — each sample's bytes are copied, never recomputed — so a
 * pristine stream stays unmodified in everything but its channel count. A trailing partial sample
 * is dropped rather than invented.
 */
object MonoUpmix {
    fun duplicate(mono: ByteArray, bytesPerSample: Int): ByteArray {
        if (bytesPerSample <= 0) return ByteArray(0)
        val samples = mono.size / bytesPerSample
        val stereo = ByteArray(samples * bytesPerSample * 2)
        for (s in 0 until samples) {
            val from = s * bytesPerSample
            val to = from * 2
            System.arraycopy(mono, from, stereo, to, bytesPerSample)
            System.arraycopy(mono, from, stereo, to + bytesPerSample, bytesPerSample)
        }
        return stereo
    }
}
