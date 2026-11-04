package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UsbDeviceOwner
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class B50PreviousStreamRateTest {
    @Test
    fun `previous rate belongs to the attachment and survives release but not unplug`() {
        val owner = UsbDeviceOwner()
        assertThat(owner.previousStreamRateHz).isEqualTo(0)
        owner.exclusive("first start") { owner.publishStream(44_100, 1) }
        owner.exclusive("release") { owner.clearStream() }
        assertThat(owner.previousStreamRateHz).isEqualTo(44_100)
        owner.exclusive("failed configure") { owner.clearStream() }
        assertThat(owner.previousStreamRateHz).isEqualTo(44_100)
        owner.exclusive("changed rate start") {
            assertThat(owner.previousStreamRateHz).isEqualTo(44_100)
            owner.publishStream(48_000, 1)
        }
        val generation = owner.facts!!.generation
        owner.claimForRecovery(generation, 1)!!.use {
            assertThat(it.commit("restart interface")).isTrue()
            assertThat(it.facts.sampleRateHz).isEqualTo(48_000)
        }
        assertThat(owner.previousStreamRateHz).isEqualTo(48_000)
        owner.exclusive("release before unplug") { owner.clearStream() }
        owner.deviceDetached()  // no live facts remain, but attachment history must still be forgotten
        assertThat(owner.previousStreamRateHz).isEqualTo(0)
        owner.exclusive("first start after replug") { owner.publishStream(96_000, 1) }
        owner.deviceDetached()
        assertThat(owner.previousStreamRateHz).isEqualTo(0)
    }
}
