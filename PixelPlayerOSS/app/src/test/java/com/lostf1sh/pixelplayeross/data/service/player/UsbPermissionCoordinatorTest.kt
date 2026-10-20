package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-031 / AUD-030 / AUD-101: one owner for USB permission requests.
 *
 * Two paths used to ask for the same device (the activity's attach handler and the engine), each
 * leaving a receiver registered until a result arrived — which under Lock Task may be never — and any
 * receiver took the first answer for any device. A request that was never shown (activity destroyed,
 * app in the background, Lock Task suspension refused) also marked the device "already asked" until a
 * replug. The coordinator dedupes per device, matches a result to its device, expires unanswered
 * requests so they can be asked again, and forgets a request that was abandoned before it was shown.
 */
class UsbPermissionCoordinatorTest {

    private val a = "/dev/bus/usb/001/002"
    private val b = "/dev/bus/usb/001/003"

    @Test
    fun `one request per device while it is pending`() {
        val coordinator = UsbPermissionCoordinator(timeoutMs = 30_000L)
        assertThat(coordinator.tryBegin(a, nowMs = 0)).isTrue()
        assertThat(coordinator.tryBegin(a, nowMs = 10)).isFalse()
        assertThat(coordinator.tryBegin(b, nowMs = 10)).isTrue()
    }

    @Test
    fun `a result is matched to its own device only`() {
        val coordinator = UsbPermissionCoordinator()
        coordinator.tryBegin(a, nowMs = 0)
        assertThat(coordinator.onResult(b, granted = true)).isFalse()
        assertThat(coordinator.onResult(a, granted = true)).isTrue()
    }

    @Test
    fun `a denial is not asked again until the device is detached`() {
        val coordinator = UsbPermissionCoordinator()
        coordinator.tryBegin(a, nowMs = 0)
        coordinator.onResult(a, granted = false)
        assertThat(coordinator.tryBegin(a, nowMs = 100_000)).isFalse()

        coordinator.onAllDetached()
        assertThat(coordinator.tryBegin(a, nowMs = 100_001)).isTrue()
    }

    @Test
    fun `an unanswered request expires and may be asked again`() {
        val coordinator = UsbPermissionCoordinator(timeoutMs = 30_000L)
        coordinator.tryBegin(a, nowMs = 0)
        assertThat(coordinator.expire(nowMs = 29_999)).isEmpty()
        assertThat(coordinator.expire(nowMs = 30_000)).containsExactly(a)
        assertThat(coordinator.tryBegin(a, nowMs = 30_001)).isTrue()
    }

    @Test
    fun `a request abandoned before it was shown does not count as asked`() {
        val coordinator = UsbPermissionCoordinator()
        coordinator.tryBegin(a, nowMs = 0)
        coordinator.abandon(a)
        assertThat(coordinator.tryBegin(a, nowMs = 1)).isTrue()
    }

    @Test
    fun `the dialog is only raised from a resumed activity`() {
        assertThat(UsbPermissionCoordinator.requesterAdmits(activityResumed = true)).isTrue()
        // Destroyed, stopped or screen off: suspending Lock Task from there is exactly AUD-101.
        assertThat(UsbPermissionCoordinator.requesterAdmits(activityResumed = false)).isFalse()
    }
}
