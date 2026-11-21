package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B625RouteReplayTest {
    @Test fun everyReleasedPlayerIsAnnouncedBeforeItsPlaylistIsCleared() {
        val scope = CoroutineScope(Job().apply { cancel() })
        val profiles = mockk<DacProfileRepository>(relaxed = true)
        every { profiles.connected } returns MutableStateFlow(emptyList())
        val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)
        every { overrides.state } returns MutableStateFlow(MaintenanceOverrides())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DualPlayerEngine(context, mockk<HeadphoneGraphicEqController>(relaxed = true), profiles, overrides, scope)
        val master = ExoPlayer.Builder(context).build()
        val incoming = ExoPlayer.Builder(context).build()
        master.setMediaItem(MediaItem.Builder().setMediaId("master").setUri("file:///synthetic-master.flac").build())
        incoming.setMediaItem(MediaItem.Builder().setMediaId("incoming").setUri("file:///synthetic-incoming.flac").build())
        val seen = mutableListOf<androidx.media3.common.Player>()
        engine.setOnPlayerAboutToBeReleasedListener { player ->
            assertThat(player.mediaItemCount).isEqualTo(1)
            seen += player
        }
        fun set(name: String, value: Any) = DualPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
        set("playerA", master)
        set("playerB", incoming)
        // The transition controller cancels its prepared incoming queue on a master timeline
        // clear. Retire the incoming player first, before that callback can clear it indirectly.
        master.addListener(object : androidx.media3.common.Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (timeline.isEmpty) engine.cancelNext()
            }
        })
        engine.release()
        assertThat(seen).containsExactly(incoming, master).inOrder()
        assertThat(master.mediaItemCount).isEqualTo(0)
        assertThat(incoming.mediaItemCount).isEqualTo(0)
    }

    @Test fun releasedMasterDropsItsPlaylistBeforeAReplacementIsAllocated() {
        val scope = CoroutineScope(Job().apply { cancel() })
        val profiles = mockk<DacProfileRepository>(relaxed = true)
        every { profiles.connected } returns MutableStateFlow(emptyList())
        val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)
        every { overrides.state } returns MutableStateFlow(MaintenanceOverrides())
        val engine = DualPlayerEngine(ApplicationProvider.getApplicationContext<Context>(), mockk<HeadphoneGraphicEqController>(relaxed = true), profiles, overrides, scope)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val items = List(5000) { MediaItem.Builder().setMediaId("synthetic-$it").setUri("file:///synthetic-$it.flac").build() }
        fun holders(player: ExoPlayer): Int = (player.javaClass.getDeclaredField("mediaSourceHolderSnapshots").apply { isAccessible = true }.get(player) as List<*>).size
        val untreated = ExoPlayer.Builder(context).build()
        untreated.setMediaItems(items)
        untreated.release()
        println("B625 Media3 release alone holders=${holders(untreated)} timelineWindows=${untreated.currentTimeline.windowCount}")
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(items)
        DualPlayerEngine::class.java.getDeclaredMethod("releasePlayer", ExoPlayer::class.java).apply { isAccessible = true }.invoke(engine, player)
        println("B625 engine retirement holders=${holders(player)} timelineWindows=${player.currentTimeline.windowCount}")
        assertThat(player.mediaItemCount).isEqualTo(0)
        assertThat(holders(player)).isEqualTo(0)
    }

    @Test fun replayAfterCapabilityRebuildKeepsTheKnownSourceAndDoesNotRebuild() {
        val scope = CoroutineScope(Job().apply { cancel() })
        val profiles = mockk<DacProfileRepository>(relaxed = true)
        every { profiles.connected } returns MutableStateFlow(emptyList())
        val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)
        every { overrides.state } returns MutableStateFlow(MaintenanceOverrides())
        val engine = DualPlayerEngine(ApplicationProvider.getApplicationContext<Context>(), mockk<HeadphoneGraphicEqController>(relaxed = true), profiles, overrides, scope)
        fun set(name: String, value: Any?) = DualPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
        fun get(name: String): Any? = DualPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)
        val item = MediaItem.Builder().setMediaId("synthetic-track").setUri("file:///synthetic.flac").build()
        val player = mockk<ExoPlayer>(relaxed = true)
        var source: Format? = Format.Builder().setSampleMimeType("audio/flac").setSampleRate(48000).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).build()
        every { player.currentMediaItem } returns item
        every { player.mediaItemCount } returns 5000
        every { player.audioFormat } answers { source }
        set("playerA", player)
        set("processingRequirementsKnown", true)
        val dac = mockk<UsbDevice>(relaxed = true)
        every { dac.deviceName } returns "synthetic-dac"
        (get("directFailureLatch") as DirectFailureLatch).onFailure("synthetic-dac", 44100, C.ENCODING_PCM_16BIT, 2)
        // The decoded source allows the retry which triggered the real crash.
        (get("masterPlayerListener") as AnalyticsListener).onAudioInputFormatChanged(mockk(relaxed = true), source!!, null)
        fun evaluate(): MasterRebuildFacts {
            val method = DualPlayerEngine::class.java.declaredMethods.single { it.name == "evaluateRoute" }.apply { isAccessible = true }
            val result = method.invoke(engine, UsbRouteFacts(dac, true), AudioRouteState(desiredBackend = AudioBackend.USB_DIRECT))
            return result.javaClass.getDeclaredField("rebuild").apply { isAccessible = true }.get(result) as MasterRebuildFacts
        }
        assertThat(evaluate().needsCapabilityRebuild).isTrue()
        set("playerBuiltWithDirectAccess", true)
        set("directClaimAttempted", true)
        source = null // New ExoPlayer has not decoded the restored item yet.
        val replay = evaluate()
        assertThat(replay.decidedBackend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(replay.shouldRebuild).isFalse()
        // A new item must never inherit the previous item's format.
        every { player.currentMediaItem } returns item.buildUpon().setMediaId("another-track").build()
        assertThat(evaluate().decidedBackend).isEqualTo(AudioBackend.ANDROID_AUDIO)
    }
}
