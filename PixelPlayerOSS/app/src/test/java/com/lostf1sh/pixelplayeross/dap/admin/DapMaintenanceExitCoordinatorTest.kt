package com.lostf1sh.pixelplayeross.dap.admin

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DapMaintenanceExitCoordinatorTest {
    @Test
    fun `normal exit ends Maintenance and requests relock before completing`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events = events)

        assertTrue(coordinator.exit())
        assertEquals(
            listOf("policy", "end", "prepare", "request", "await"),
            events,
        )
    }

    @Test
    fun `repeated cycles do not retain jobs or listeners`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events = events)

        repeat(10) { assertTrue(coordinator.exit()) }

        assertEquals(10, events.count { it == "request" })
        assertEquals(10, events.count { it == "await" })
        assertFalse(events.contains("recover"))
    }

    @Test
    fun `already locked remains an idempotent successful exit`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events = events, locked = true)

        assertTrue(coordinator.exit())
        assertEquals(1, events.count { it == "request" })
        assertFalse(events.contains("recover"))
    }

    @Test
    fun `failed immediate observation invokes generic recovery fallback`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events = events, locked = false)

        assertFalse(coordinator.exit())
        assertEquals(
            listOf("policy", "end", "prepare", "request", "await", "recover"),
            events,
        )
    }

    @Test
    fun `policy failure keeps Maintenance active and does not request Lock Task`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events = events, policyReady = false)

        assertFalse(coordinator.exit())
        assertEquals(listOf("policy"), events)
    }

    private fun coordinator(
        events: MutableList<String>,
        policyReady: Boolean = true,
        locked: Boolean = true,
    ) = DapMaintenanceExitCoordinator(
        ensurePolicyReady = {
            events += "policy"
            policyReady
        },
        endMaintenanceSession = { events += "end" },
        prepareLockTaskTransition = { events += "prepare" },
        requestLockTask = { events += "request" },
        awaitLocked = {
            events += "await"
            locked
        },
        recoverContainment = { events += "recover" },
    )
}
