package com.lostf1sh.pixelplayeross.data.image

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where Coil fetches and decodes artwork.
 *
 * It used to be `Dispatchers.Default`, which has one thread per core: a fling over songs whose covers
 * were not yet cached put an extraction on every core of the phone at once (8 on the HONOR), and the
 * threads the UI needs, the main thread, the render thread and the garbage collector, had to share
 * what was left (B13 profile: the pool took ~55 % of all CPU during a fling, the main thread 16 %).
 *
 * Artwork is never urgent enough to starve the screen: a few threads are plenty, and they run
 * below the UI so that when the CPU is short it is the cover that waits.
 */
object ArtworkDispatcher {
    const val THREADS = 3

    /** Four nice levels below the default: behind the UI threads, but not in the background cgroup. */
    private const val PRIORITY = Process.THREAD_PRIORITY_DEFAULT + 4

    val instance: CoroutineDispatcher by lazy {
        val counter = AtomicInteger()
        Executors.newFixedThreadPool(THREADS, ThreadFactory { runnable ->
            Thread(
                {
                    Process.setThreadPriority(PRIORITY)
                    runnable.run()
                },
                "artwork-${counter.incrementAndGet()}"
            ).apply { isDaemon = true }
        }).asCoroutineDispatcher()
    }
}
