package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import androidx.media3.common.Player
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class B625WrapperLifetimeTest {
    @Test fun aPausedReplacementCancelsTheSchedulerHoldingThePreviousPlayer() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val heldJob = scope.launch { kotlinx.coroutines.awaitCancellation() }
            val controller = TransitionController(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
            TransitionController::class.java.getDeclaredField("transitionSchedulerJob").apply { isAccessible = true }.set(controller, heldJob)
            @Suppress("UNCHECKED_CAST")
            val swap = TransitionController::class.java.getDeclaredField("swapListener").apply { isAccessible = true }.get(controller) as (Player) -> Unit
            swap(mockk<Player>(relaxed = true))
            org.junit.jupiter.api.Assertions.assertTrue(heldJob.isCancelled)
            controller.release()
        } finally { scope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun duplicateListenerRegistrationDoesNotKeepTheRemoteCollectorAlive() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        try {
            val source = object : RemoteDeviceVolume {
                override val state = MutableStateFlow<RemoteVolumeState?>(null)
                override fun setLevel(level: Int) = Unit
            }
            val player = MappingPlayer(mockk<Player>(relaxed = true), mockk<Context>(relaxed = true), source, scope)
            val listener = mockk<Player.Listener>(relaxed = true)
            player.addListener(listener)
            player.addListener(listener)
            TestScope(dispatcher).runCurrent()
            player.removeListener(listener)
            TestScope(dispatcher).runCurrent()
            assertEquals(0, job.children.count(), "A removed listener must not leave an appScope collector holding the entire old playlist")
        } finally { scope.cancel(); Dispatchers.resetMain() }
    }
}
