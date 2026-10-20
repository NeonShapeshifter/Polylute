package com.lostf1sh.pixelplayeross.data.preferences

data class FullPlayerLoadingTweaks(
    val delayAll: Boolean = false,
    val delayAlbumCarousel: Boolean = false,
    val delaySongMetadata: Boolean = false,
    val delayProgressBar: Boolean = false,
    val delayControls: Boolean = false,
    val showPlaceholders: Boolean = false,
    val transparentPlaceholders: Boolean = false,
    val applyPlaceholdersOnClose: Boolean = false,
    val switchOnDragRelease: Boolean = false,
    val contentAppearThresholdPercent: Int = 0,
    val contentCloseThresholdPercent: Int = 0
)
