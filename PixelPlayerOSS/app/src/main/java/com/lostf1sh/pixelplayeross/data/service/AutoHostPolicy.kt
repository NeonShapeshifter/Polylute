package com.lostf1sh.pixelplayeross.data.service

/**
 * DHU uses the phone's gearhead host. AAOS's stock Media Center is a trusted system controller.
 *
 * B69: who is the car is [MediaBrowseCallerPolicy]'s answer, the same one that decides who may browse: Android Auto only when its
 * signing certificate is Google's (a sideloaded app with its package name is not the car), the AAOS media center only when trusted.
 */
internal object AutoHostPolicy {
    fun isAuto(caller: MediaBrowseCallerPolicy.Caller): Boolean = caller.isAutoHost
    val commands = setOf(MusicNotificationProvider.CUSTOM_COMMAND_LIKE,
        MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE, MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE)
}
