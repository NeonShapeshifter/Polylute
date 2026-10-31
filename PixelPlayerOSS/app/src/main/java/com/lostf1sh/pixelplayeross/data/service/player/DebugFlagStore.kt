package com.lostf1sh.pixelplayeross.data.service.player

/** Persistence seam shared by debug flags such as DAC EQ write consent. */
interface DebugFlagStore {
    fun read(): Boolean
    fun write(value: Boolean)
}
