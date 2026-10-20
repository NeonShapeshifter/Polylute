package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import org.junit.jupiter.api.Test

class DacProfilesUiResolverTest {
    private val first = UsbDacDescriptor(1, 2, "Maker", "DAC One", "A", "path-a")
    private val second = UsbDacDescriptor(1, 2, "Maker", "DAC One", "B", "path-b")

    @Test
    fun `no attachment has no current DAC`() {
        val profile = DacProfileCatalog.register(emptyList(), first, 1L).profile
        val state = DacProfilesUiResolver.resolve(
            listOf(profile),
            emptyList(),
            DacActiveRouteEvidence(UsbDirectSinkState.ANDROID_AUDIO, null),
        )

        assertThat(state.current).isNull()
        assertThat(state.profiles.single().status).isEqualTo(DacLiveStatus.NOT_CONNECTED)
    }

    @Test
    fun `attached DAC is connected but not falsely active`() {
        val profile = DacProfileCatalog.register(emptyList(), first, 1L).profile
        val state = DacProfilesUiResolver.resolve(
            listOf(profile),
            listOf(first),
            DacActiveRouteEvidence(UsbDirectSinkState.USB_DIRECT_AVAILABLE, "DAC One"),
        )

        assertThat(state.current?.status).isEqualTo(DacLiveStatus.CONNECTED)
    }

    @Test
    fun `active evidence marks exactly one matching DAC active`() {
        val firstProfile = DacProfileCatalog.register(emptyList(), first, 1L).profile
        val state = DacProfilesUiResolver.resolve(
            listOf(firstProfile),
            listOf(first),
            DacActiveRouteEvidence(UsbDirectSinkState.USB_DIRECT_ACTIVE, "DAC One"),
        )

        assertThat(state.current?.status).isEqualTo(DacLiveStatus.ACTIVE)
    }

    @Test
    fun `ambiguous identical attached DACs are not all active`() {
        val profiles = DacProfileCatalog.register(
            DacProfileCatalog.register(emptyList(), first, 1L).profiles,
            second,
            2L,
        ).profiles
        val state = DacProfilesUiResolver.resolve(
            profiles,
            listOf(first, second),
            DacActiveRouteEvidence(UsbDirectSinkState.USB_DIRECT_ACTIVE, "DAC One"),
        )

        assertThat(state.profiles.count { it.status == DacLiveStatus.ACTIVE }).isEqualTo(0)
        assertThat(state.profiles.count { it.status == DacLiveStatus.CONNECTED }).isEqualTo(2)
    }

    @Test
    fun `active sorts before connected and disconnected`() {
        val profiles = DacProfileCatalog.register(
            DacProfileCatalog.register(emptyList(), first, 1L).profiles,
            second,
            2L,
        ).profiles
        val state = DacProfilesUiResolver.resolve(
            profiles,
            listOf(first),
            DacActiveRouteEvidence(UsbDirectSinkState.USB_DIRECT_ACTIVE, "path-a"),
        )

        assertThat(state.profiles.first().status).isEqualTo(DacLiveStatus.ACTIVE)
        assertThat(state.profiles.last().status).isEqualTo(DacLiveStatus.NOT_CONNECTED)
    }
}
