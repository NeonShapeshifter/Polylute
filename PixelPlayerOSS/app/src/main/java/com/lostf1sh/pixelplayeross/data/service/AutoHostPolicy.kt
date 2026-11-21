package com.lostf1sh.pixelplayeross.data.service

/** DHU uses the phone's gearhead host. AAOS's stock Media Center is a trusted system controller. */
internal object AutoHostPolicy {
    fun isAuto(packageName: String, trusted: Boolean): Boolean =
        packageName == "com.google.android.projection.gearhead" || (trusted && packageName == "com.android.car.media")
    val commands = setOf(MusicNotificationProvider.CUSTOM_COMMAND_LIKE,
        MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE, MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE)
}
