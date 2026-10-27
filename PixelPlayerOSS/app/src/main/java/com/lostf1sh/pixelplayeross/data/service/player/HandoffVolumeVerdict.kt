package com.lostf1sh.pixelplayeross.data.service.player

enum class HandoffVolumeVerdict { WAIT, RESUME, HOLD }

/**
 * B40-P1: whether the DAC's level has been read back (and is inside an armed ceiling), so a play the **listener** asked for may start. It
 * is not [HandoffVolumeVerdict]: that one judges what the app may do on its own and holds a loud level without a ceiling; a listener who
 * pressed play has chosen, and only a level nobody read waits.
 */
enum class UserPlayLevel { UNCONFIRMED, CONFIRMED }
