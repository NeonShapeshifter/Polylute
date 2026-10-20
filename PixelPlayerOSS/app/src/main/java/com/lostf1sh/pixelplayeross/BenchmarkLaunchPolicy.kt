package com.lostf1sh.pixelplayeross

/**
 * Whether a launch intent may drive the macrobenchmark hooks (AUD-009).
 *
 * `MainActivity` is exported (LAUNCHER/HOME), and explicit intents ignore filters, so the
 * `is_benchmark` / `benchmark_rebuild_database` extras reached it from any app or `adb`. The rebuild
 * they trigger is only meaningful for the `benchmark` build the macrobenchmarks install.
 */
object BenchmarkLaunchPolicy {
    const val BENCHMARK_BUILD_TYPE = "benchmark"

    fun shouldRebuildDatabase(buildType: String, isBenchmark: Boolean, rebuildRequested: Boolean): Boolean =
        buildType == BENCHMARK_BUILD_TYPE && isBenchmark && rebuildRequested
}
