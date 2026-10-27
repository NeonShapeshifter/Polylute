package com.lostf1sh.pixelplayeross

/** Engineering tools are available in development builds, never in the public release. */
object DevelopmentToolsPolicy {
    fun enabled(buildType: String = BuildConfig.BUILD_TYPE): Boolean =
        buildType == "debug" || buildType == "benchmark"
}
