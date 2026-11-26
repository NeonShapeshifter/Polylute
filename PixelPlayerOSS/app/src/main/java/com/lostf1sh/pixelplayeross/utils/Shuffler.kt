package com.lostf1sh.pixelplayeross.utils

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlinx.coroutines.yield

/**
 * Single central shuffle dealer for all queue shuffling (B79 item 3).
 *
 * Owns one shared [SecureRandom] source (with Kotlin and Java adapters) so every queue shuffle
 * draw — turning shuffle on, any Shuffle button, picking a random start index, and repeat-all wrap —
 * draws a fresh, unpredictable order. Tests may pass a seeded [Random] for deterministic checks.
 */
object Shuffler {
    private const val YIELD_BATCH = 512

    private val secureKotlinRandom: Random = BufferedSecureRandom(SecureRandom())
    private val secureJavaRandom: java.util.Random = secureKotlinRandom.asJavaRandom()

    fun defaultJavaRandom(): java.util.Random = secureJavaRandom

    fun defaultKotlinRandom(): Random = secureKotlinRandom

    /** Picks a uniform index in `0 until size`. */
    fun nextIndex(size: Int, random: Random = secureKotlinRandom): Int {
        require(size > 0) { "size must be positive, was $size" }
        return random.nextInt(size)
    }

    /**
     * Deals a Fisher-Yates permutation of `0 until size`.
     *
     * When [anchorIndex] is non-null:
     * - with [startAtZero] = true, `anchorIndex` is placed at position 0 and the remaining indices are shuffled;
     * - with [startAtZero] = false, `anchorIndex` stays at `order[anchorIndex]` and the remaining indices are shuffled.
     */
    fun deal(
        size: Int,
        anchorIndex: Int? = null,
        startAtZero: Boolean = false,
        random: Random = secureKotlinRandom,
    ): IntArray {
        if (size <= 1) return IntArray(size) { it }
        if (anchorIndex == null) {
            val order = IntArray(size) { it }
            for (i in size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                if (i != j) {
                    val tmp = order[i]
                    order[i] = order[j]
                    order[j] = tmp
                }
            }
            return order
        }
        return dealAnchoredSync(size, anchorIndex, startAtZero, random)
    }

    /** Suspendable equivalent of [deal] that yields periodically for large queues. */
    suspend fun dealSuspending(
        size: Int,
        anchorIndex: Int? = null,
        startAtZero: Boolean = false,
        random: Random = secureKotlinRandom,
    ): IntArray {
        if (size <= 1) return IntArray(size) { it }
        if (anchorIndex == null) {
            val order = IntArray(size) { it }
            var work = 0
            for (i in size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                if (i != j) {
                    val tmp = order[i]
                    order[i] = order[j]
                    order[j] = tmp
                }
                if (++work >= YIELD_BATCH) {
                    work = 0
                    yield()
                }
            }
            return order
        }
        val clampedAnchor = anchorIndex.coerceIn(0, size - 1)
        val pool = IntArray(size - 1)
        var cursor = 0
        var work = 0
        for (i in 0 until size) {
            if (i != clampedAnchor) pool[cursor++] = i
            if (++work >= YIELD_BATCH) {
                work = 0
                yield()
            }
        }
        for (i in pool.lastIndex downTo 1) {
            val swapIndex = random.nextInt(i + 1)
            if (i != swapIndex) {
                val tmp = pool[i]
                pool[i] = pool[swapIndex]
                pool[swapIndex] = tmp
            }
            if (++work >= YIELD_BATCH) {
                work = 0
                yield()
            }
        }
        return assembleAnchoredOrder(size, clampedAnchor, pool, startAtZero)
    }

    /** Shuffles [items] in a fresh list using [deal]. */
    fun <T> shuffleList(items: List<T>, random: Random = secureKotlinRandom): List<T> {
        if (items.size <= 1) return items.toList()
        val order = deal(items.size, random = random)
        return List(order.size) { idx -> items[order[idx]] }
    }

    /** Deals a permutation of `0 until size` ensuring `order[0] != avoidFirst` when `size >= 2`. */
    fun dealAvoidingFirst(
        size: Int,
        avoidFirst: Int,
        random: Random = secureKotlinRandom,
    ): IntArray {
        val order = deal(size, random = random)
        if (size >= 2 && order[0] == avoidFirst) {
            val swapIdx = 1 + random.nextInt(size - 1)
            val tmp = order[0]
            order[0] = order[swapIdx]
            order[swapIdx] = tmp
        }
        return order
    }

    private fun dealAnchoredSync(
        size: Int,
        anchorIndex: Int,
        startAtZero: Boolean,
        random: Random,
    ): IntArray {
        val clampedAnchor = anchorIndex.coerceIn(0, size - 1)
        val pool = IntArray(size - 1)
        var cursor = 0
        for (i in 0 until size) {
            if (i != clampedAnchor) pool[cursor++] = i
        }
        for (i in pool.lastIndex downTo 1) {
            val swapIndex = random.nextInt(i + 1)
            if (i != swapIndex) {
                val tmp = pool[i]
                pool[i] = pool[swapIndex]
                pool[swapIndex] = tmp
            }
        }
        return assembleAnchoredOrder(size, clampedAnchor, pool, startAtZero)
    }

    private fun assembleAnchoredOrder(
        size: Int,
        clampedAnchor: Int,
        pool: IntArray,
        startAtZero: Boolean,
    ): IntArray {
        val order = IntArray(size)
        if (startAtZero) {
            order[0] = clampedAnchor
            for (i in pool.indices) order[i + 1] = pool[i]
        } else {
            var poolIndex = 0
            for (i in 0 until size) {
                order[i] = if (i == clampedAnchor) clampedAnchor else pool[poolIndex++]
            }
        }
        return order
    }
}

/**
 * B79 review: [SecureRandom] bytes taken in bulk. One SecureRandom call per 4 KiB instead of one per number: the draw
 * stays cryptographically random, but a 5,000-song deal no longer pays a native call (and, on a desktop JVM, a locked
 * `/dev/urandom` read) for every swap. Kotlin's [Random.nextInt] builds bounded values from [nextBits] by rejection,
 * so the bounds stay unbiased. Shared across threads, hence the lock (uncontended in practice).
 */
internal class BufferedSecureRandom(private val source: SecureRandom) : Random() {
    private val buffer = ByteArray(4096)
    private var position = buffer.size

    @Synchronized
    override fun nextBits(bitCount: Int): Int {
        if (position + 4 > buffer.size) {
            source.nextBytes(buffer)
            position = 0
        }
        val value = ((buffer[position].toInt() and 0xFF) shl 24) or
            ((buffer[position + 1].toInt() and 0xFF) shl 16) or
            ((buffer[position + 2].toInt() and 0xFF) shl 8) or
            (buffer[position + 3].toInt() and 0xFF)
        position += 4
        return value.ushr(32 - bitCount) and (-bitCount shr 31)
    }
}
