package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileCatalog
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import org.junit.jupiter.api.Test

class HeadphoneProfilesUiResolverTest {
    private val dacA = UsbDacDescriptor(1, 2, "Maker", "DAC A", "A", "path-a")
    private val dacB = UsbDacDescriptor(1, 3, "Maker", "DAC B", "B", "path-b")
    private val dacProfiles = listOf(
        DacProfileCatalog.register(emptyList(), dacA, 1L).profile,
        DacProfileCatalog.register(emptyList(), dacB, 2L).profile,
    )

    private fun headphones(): HeadphoneProfileState {
        val a = HeadphoneProfileCatalog.createAndSelect(
            HeadphoneProfileState(), "A", HeadphoneEqState(), 1L, "headphone-a", dacProfiles[0].profileId
        )
        return HeadphoneProfileCatalog.createAndSelect(
            a, "B", HeadphoneEqState(), 2L, "headphone-b", dacProfiles[1].profileId
        )
    }

    @Test
    fun `selected headphone sorts first and reports remembered DAC name`() {
        val state = HeadphoneProfilesUiResolver.resolve(
            headphones(), dacProfiles, dacProfiles[1].profileId
        )

        assertThat(state.selected?.profile?.profileId).isEqualTo("headphone-b")
        assertThat(state.profiles.first().profile.profileId).isEqualTo("headphone-b")
        assertThat(state.selected?.associatedDacName).isEqualTo("DAC B")
    }

    @Test
    fun `manual selection without DAC remains selected without detection claim`() {
        val selected = HeadphoneProfileCatalog.select(headphones(), "headphone-a", 3L)
        val state = HeadphoneProfilesUiResolver.resolve(selected, dacProfiles, null)

        assertThat(state.selected?.isSelected).isTrue()
        assertThat(state.selected?.associatedDacName).isEqualTo("DAC A")
        assertThat(state.currentDacProfileId).isNull()
    }

    @Test
    fun `one connected saved DAC supplies unambiguous association context`() {
        val resolved = DacHeadphoneContextResolver.resolve(
            dacProfiles, listOf(dacA), UsbDirectSinkState.USB_DIRECT_AVAILABLE, "DAC A"
        )

        assertThat(resolved).isEqualTo(dacProfiles[0].profileId)
    }

    @Test
    fun `active DAC wins over other merely connected DAC`() {
        val resolved = DacHeadphoneContextResolver.resolve(
            dacProfiles, listOf(dacA, dacB), UsbDirectSinkState.USB_DIRECT_ACTIVE, "path-b"
        )

        assertThat(resolved).isEqualTo(dacProfiles[1].profileId)
    }

    @Test
    fun `ambiguous connected DACs do not invent association context`() {
        val resolved = DacHeadphoneContextResolver.resolve(
            dacProfiles, listOf(dacA, dacB), UsbDirectSinkState.USB_DIRECT_AVAILABLE, null
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun `no connected DAC does not block manual headphone profiles`() {
        val state = HeadphoneProfilesUiResolver.resolve(headphones(), dacProfiles, null)

        assertThat(state.profiles).hasSize(2)
        assertThat(state.selected).isNotNull()
        assertThat(DacHeadphoneContextResolver.resolve(
            dacProfiles, emptyList(), UsbDirectSinkState.ANDROID_AUDIO, null
        )).isNull()
    }
}
