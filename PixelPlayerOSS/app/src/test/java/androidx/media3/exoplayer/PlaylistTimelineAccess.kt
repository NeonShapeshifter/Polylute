package androidx.media3.exoplayer

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * ExoPlayer's `PlaylistTimeline` is package-private, and it is the class every ExoPlayer queue publishes (B19-S4). This file sits in its
 * package, in the test sources only, so the host measurement can publish the very timeline class the app's session receives.
 */
@UnstableApi
class PlaylistTimelineHolder(private val uid: Any, private val timeline: Timeline) : MediaSourceInfoHolder {
    override fun getUid(): Any = uid
    override fun getTimeline(): Timeline = timeline
}

@UnstableApi
fun playlistTimeline(holders: List<PlaylistTimelineHolder>, shuffleOrder: ShuffleOrder): Timeline =
    PlaylistTimeline(holders, shuffleOrder)
