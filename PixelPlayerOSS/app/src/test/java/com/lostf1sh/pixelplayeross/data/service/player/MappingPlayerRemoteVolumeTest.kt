package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * AUD-027: with the screen off, the volume keys go to the media session, not to an activity. While
 * the DAC's Feature Unit is the output level, the session must present it as remote playback, so
 * the keys move the DAC instead of `STREAM_MUSIC` — which in Direct feeds a muted delegate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MappingPlayerRemoteVolumeTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val remoteState = MutableStateFlow<RemoteVolumeState?>(null)
    private val requested = mutableListOf<Int>()

    private val remote = object : RemoteDeviceVolume {
        override val state = remoteState
        override fun setLevel(level: Int) { requested += level }
    }

    private val inner = mockk<Player>(relaxed = true).also {
        every { it.deviceInfo } returns DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()
        every { it.availableCommands } returns Player.Commands.EMPTY
        every { it.isCommandAvailable(any()) } returns false
    }

    private fun player() = MappingPlayer(inner, mockk<Context>(relaxed = true), remote, scope)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `without a hardware level the session stays local`() {
        val player = player()
        player.addListener(mockk(relaxed = true))
        scope.runCurrent()

        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
    }

    @Test
    fun `a hardware level is presented as remote playback with its range and level`() {
        val player = player()
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)
        remoteState.value = RemoteVolumeState(min = 0, max = 60, level = 20)
        scope.runCurrent()

        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        assertThat(player.deviceInfo.maxVolume).isEqualTo(60)
        assertThat(player.deviceVolume).isEqualTo(20)
        assertThat(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)).isTrue()
        val info = slot<DeviceInfo>()
        verify { listener.onDeviceInfoChanged(capture(info)) }
        assertThat(info.captured.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        verify { listener.onDeviceVolumeChanged(20, false) }
    }

    @Test
    fun `volume keys routed to the session step the hardware level`() {
        val player = player()
        player.addListener(mockk(relaxed = true))
        remoteState.value = RemoteVolumeState(min = 0, max = 60, level = 20)
        scope.runCurrent()

        player.increaseDeviceVolume(C.VOLUME_FLAG_SHOW_UI)
        player.decreaseDeviceVolume(C.VOLUME_FLAG_SHOW_UI)
        player.setDeviceVolume(35, 0)

        assertThat(requested).containsExactly(21, 19, 35).inOrder()
        verify(exactly = 0) { inner.increaseDeviceVolume(any()) }
        verify(exactly = 0) { inner.setDeviceVolume(any(), any()) }
    }
}
