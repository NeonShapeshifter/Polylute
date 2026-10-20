package com.lostf1sh.pixelplayeross.data.preferences

/** AUD-113 / AUD-121: where the periodic position checkpoint lives. */
interface PlaybackPositionStore {
    fun write(mark: PlaybackPositionMark)
    fun read(): PlaybackPositionMark?
    fun clear()
}

class InMemoryPlaybackPositionStore : PlaybackPositionStore {
    @Volatile
    private var mark: PlaybackPositionMark? = null
    override fun write(mark: PlaybackPositionMark) { this.mark = mark }
    override fun read(): PlaybackPositionMark? = mark
    override fun clear() { mark = null }
}
