package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MaintenanceSessionControllerTest {
    private val idle = 5 * 60 * 1_000L
    private val absolute = 15 * 60 * 1_000L

    @Test
    fun `successful authentication creates an active in-memory session`() {
        val controller = controller()

        controller.begin(100L)

        assertThat(controller.snapshot.value.state)
            .isEqualTo(MaintenanceSessionState.AUTHENTICATED_ACTIVE)
        assertThat(controller.snapshot.value.isAuthenticated).isTrue()
        assertThat(controller.snapshot.value.authenticatedAtElapsedMs).isEqualTo(100L)
    }

    @Test
    fun `explicit and Back exit invalidate the same session state`() {
        val controller = controller()
        controller.begin(0L)

        controller.invalidate()

        assertThat(controller.snapshot.value.state)
            .isEqualTo(MaintenanceSessionState.NOT_AUTHENTICATED)
    }

    @Test
    fun `new controller after process death or reboot holds no window`() {
        val oldProcess = controller().apply { begin(0L) }
        assertThat(oldProcess.snapshot.value.isAuthenticated).isTrue()

        val newProcess = controller()

        assertThat(newProcess.snapshot.value.state)
            .isEqualTo(MaintenanceSessionState.NOT_AUTHENTICATED)
    }

    @Test
    fun `idle timeout expires active session`() {
        val controller = controller()
        controller.begin(0L)

        assertThat(controller.expireIfNeeded(idle - 1L)).isFalse()
        assertThat(controller.expireIfNeeded(idle)).isTrue()
        assertThat(controller.snapshot.value.state).isEqualTo(MaintenanceSessionState.EXPIRED)
    }

    @Test
    fun `owner interaction refreshes idle timeout but not absolute lifetime`() {
        val controller = MaintenanceSessionController(
            idleTimeoutMs = 100L,
            absoluteTimeoutMs = 250L,
        )
        controller.begin(0L)

        assertThat(controller.recordInteraction(90L)).isTrue()
        assertThat(controller.recordInteraction(180L)).isTrue()
        assertThat(controller.recordInteraction(249L)).isTrue()
        assertThat(controller.expireIfNeeded(250L)).isTrue()
    }

    @Test
    fun `external system flow can return after idle window but before absolute timeout`() {
        val controller = controller()
        controller.begin(0L)

        assertThat(controller.beginExternalFlow(1_000L)).isTrue()
        assertThat(controller.returnFromExternalFlow(idle + 1_000L)).isTrue()
        assertThat(controller.snapshot.value.state)
            .isEqualTo(MaintenanceSessionState.AUTHENTICATED_ACTIVE)
    }

    @Test
    fun `external flow return after absolute timeout expires session`() {
        val controller = controller()
        controller.begin(0L)
        assertThat(controller.beginExternalFlow(1_000L)).isTrue()

        assertThat(controller.returnFromExternalFlow(absolute)).isFalse()
        assertThat(controller.snapshot.value.state).isEqualTo(MaintenanceSessionState.EXPIRED)
    }

    @Test
    fun `external flow cannot begin without an open window`() {
        val controller = controller()

        assertThat(controller.beginExternalFlow(0L)).isFalse()
        assertThat(controller.snapshot.value.isAuthenticated).isFalse()
    }

    @Test
    fun `repeated lifecycle reconciliation is idempotent`() {
        val controller = controller()
        controller.begin(0L)

        repeat(20) {
            assertThat(controller.expireIfNeeded(1_000L)).isFalse()
        }

        assertThat(controller.snapshot.value.state)
            .isEqualTo(MaintenanceSessionState.AUTHENTICATED_ACTIVE)
    }

    @Test
    fun `twenty authenticate external return and exit cycles retain no session`() {
        val controller = controller()

        repeat(20) { cycle ->
            val start = cycle * 10_000L
            controller.begin(start)
            assertThat(controller.beginExternalFlow(start + 100L)).isTrue()
            assertThat(controller.returnFromExternalFlow(start + 200L)).isTrue()
            controller.invalidate()
            assertThat(controller.snapshot.value.state)
                .isEqualTo(MaintenanceSessionState.NOT_AUTHENTICATED)
        }
    }

    @Test
    fun `expiry deadline uses idle while active and absolute while external`() {
        val controller = controller()
        controller.begin(100L)

        assertThat(controller.millisUntilExpiry(100L)).isEqualTo(idle)
        assertThat(controller.beginExternalFlow(200L)).isTrue()
        assertThat(controller.millisUntilExpiry(200L)).isEqualTo(absolute - 100L)
    }

    private fun controller() = MaintenanceSessionController(
        idleTimeoutMs = idle,
        absoluteTimeoutMs = absolute,
    )
}
