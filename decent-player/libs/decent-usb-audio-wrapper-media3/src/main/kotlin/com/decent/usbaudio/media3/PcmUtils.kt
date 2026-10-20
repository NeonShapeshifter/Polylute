package com.decent.usbaudio.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility functions for reading and writing PCM audio samples as normalized floats,
 * covering all four common PCM encodings used by the Media3 audio pipeline.
 *
 * Supported encodings and their byte widths:
 *  - [androidx.media3.common.C.ENCODING_PCM_16BIT]  2 bytes  16-bit signed integer, little-endian.
 *  - [androidx.media3.common.C.ENCODING_PCM_24BIT]  3 bytes  24-bit signed integer, little-endian.
 *  - [androidx.media3.common.C.ENCODING_PCM_32BIT]  4 bytes  32-bit signed integer, little-endian.
 *  - [androidx.media3.common.C.ENCODING_PCM_FLOAT]  4 bytes  IEEE 754 single-precision float.
 *
 * Integer encodings are normalized to the [-1.0, 1.0] range on read and scaled back on write.
 * Float samples are passed through as-is (may legitimately exceed [-1, 1] with headroom).
 *
 * @author Hamza417
 */
@Suppress("unused") // Used by BalanceAudioProcessor, DownmixAudioProcessor, StereoWideningAudioProcessor, TapeSaturationProcessor
@OptIn(UnstableApi::class)
object PcmUtils {

    /**
     * Returns true if [encoding] is one of the four PCM formats handled by this utility
     * and recognized by the custom audio processors in this package.
     */
    fun isEncodingSupported(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT ||
                encoding == C.ENCODING_PCM_24BIT ||
                encoding == C.ENCODING_PCM_32BIT ||
                encoding == C.ENCODING_PCM_FLOAT

    /**
     * Returns the number of bytes occupied by a single sample in [encoding].
     * Defaults to 2 (16-bit) for any unrecognized encoding.
     */
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    /**
     * Reads one sample from [buffer] in the given [encoding] and returns it as a float.
     *
     * Integer encodings are normalized to [-1.0, 1.0]:
     *  - 16-bit → divide by 32768
     *  - 24-bit → divide by 8388608 (2^23)
     *  - 32-bit → divide by 2147483648 (2^31)
     *
     * [C.ENCODING_PCM_FLOAT] samples are returned as-is.
     */
    fun readFloat(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buffer.getShort().toFloat() / 32768f
        C.ENCODING_PCM_FLOAT -> buffer.getFloat()
        C.ENCODING_PCM_24BIT -> readInt24(buffer).toFloat() / 8388608f
        C.ENCODING_PCM_32BIT -> buffer.getInt().toFloat() / 2.1474836E9f
        else -> buffer.getShort().toFloat() / 32768f
    }

    /**
     * Writes [sample] to [buffer] in the given [encoding].
     *
     * Integer encodings are scaled from the float and clamped to prevent hard overflow:
     *  - 16-bit → multiply by 32767 and clamp to [-32768, 32767]
     *  - 24-bit → multiply by 8388607 and clamp to [-8388608, 8388607]
     *  - 32-bit → multiply by 2147483647 and clamp to [Int.MIN_VALUE, Int.MAX_VALUE]
     *
     * [C.ENCODING_PCM_FLOAT] is written as-is without clamping.
     */
    fun writeFloat(buffer: ByteBuffer, sample: Float, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> buffer.putShort(
                    (sample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            )
            C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample)
            C.ENCODING_PCM_24BIT -> writeInt24(
                    buffer,
                    (sample * 8388607f).coerceIn(-8388608f, 8388607f).toInt()
            )
            C.ENCODING_PCM_32BIT -> buffer.putInt(
                    (sample.toDouble() * 2147483647.0)
                        .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
            )
        }
    }

    /**
     * Reads [sampleCount] samples of [encoding] from [source] into [destination] as floats.
     *
     * Bulk form of [readFloat], for the Direct processing path where a whole decoded buffer is
     * converted at once. Every integer scale is a power of two, so the conversion is exact for
     * 16- and 24-bit input: neither 2^15 nor 2^23 has a significand wider than the 24 bits a
     * float32 carries, and multiplying by a power of two cannot round.
     *
     * [source] is read from its current position and left advanced by the number of bytes consumed,
     * matching how `AudioProcessor` implementations are expected to consume their input.
     */
    fun readFloats(
        source: ByteBuffer,
        encoding: Int,
        destination: FloatArray,
        sampleCount: Int
    ) {
        val ordered = source.duplicate().order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> ordered.asFloatBuffer().get(destination, 0, sampleCount)
            C.ENCODING_PCM_16BIT -> {
                val shorts = ordered.asShortBuffer()
                for (i in 0 until sampleCount) destination[i] = shorts.get(i) / 32768f
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) destination[i] = readInt24(ordered).toFloat() / 8388608f
            }
            C.ENCODING_PCM_32BIT -> {
                val ints = ordered.asIntBuffer()
                for (i in 0 until sampleCount) destination[i] = ints.get(i) / 2147483648f
            }
            else -> {
                // Unrecognised encoding: leave the destination at silence rather than reinterpreting
                // bytes at the wrong width, which would produce noise instead of an obvious fault.
                java.util.Arrays.fill(destination, 0, sampleCount, 0f)
                return
            }
        }
        source.position(source.position() + sampleCount * bytesPerSample(encoding))
    }

    /**
     * Reads a little-endian 24-bit signed integer from three consecutive bytes in [buffer].
     * The sign bit is extended from bit 23 to produce a valid Kotlin [Int].
     */
    private fun readInt24(buffer: ByteBuffer): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        val unsigned = b0 or (b1 shl 8) or (b2 shl 16)
        return if (unsigned and 0x800000 != 0) unsigned or (-0x1000000) else unsigned
    }

    /**
     * Writes [value] as a little-endian 24-bit signed integer (3 bytes) to [buffer].
     * Only the 24 least-significant bits of [value] are written.
     */
    private fun writeInt24(buffer: ByteBuffer, value: Int) {
        buffer.put((value and 0xFF).toByte())
        buffer.put((value shr 8 and 0xFF).toByte())
        buffer.put((value shr 16 and 0xFF).toByte())
    }
}