package com.lostf1sh.pixelplayeross.ui.glass

import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import org.junit.rules.ExternalResource

/**
 * B42: every sheet and dialog of the app mounts `ModalInputBridge()` first, which asks Hilt for the volume authority and the Back mode. A JVM
 * test has no Hilt component, so the entry point is answered here (the same way `FixedOutputQuestionDialogTest` does it): the real sheets can
 * be composed, in their own windows, as they are in the app.
 */
class B42HiltShim : ExternalResource() {
    override fun before() {
        val repository = mockk<UserPreferencesRepository>(relaxed = true)
        every { repository.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val entryPoint = object : ModalInputEntryPoint {
            override fun safetyVolumeController(): SafetyVolumeController = mockk(relaxed = true)
            override fun userPreferencesRepository(): UserPreferencesRepository = repository
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), ModalInputEntryPoint::class.java)
        } returns entryPoint
    }

    override fun after() {
        unmockkStatic(EntryPointAccessors::class)
    }
}
