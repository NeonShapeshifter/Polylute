package com.lostf1sh.pixelplayeross.dap.admin

import android.app.ActivityManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import org.junit.jupiter.api.Test

/**
 * B34: the Pinned level from request to Android's answer.
 *
 * [Phone] stands for a device with no Device Owner: Android's pin state, who is home, and the request the listener made. The mode is read by
 * the real [ProductModeResolver] (and the stored request reset by its real rule), so what is under test is that the page follows what
 * Android reports and never what was asked for.
 */
class AppPinningPolicyTest {

    private class Phone(var home: Boolean = true, var pin: Int = ActivityManager.LOCK_TASK_MODE_NONE, var requested: ProductModeRequest? = null) {
        private fun evidence() = DedicatedProvisioningEvidence(false, home, false, false, pin)
        fun mode() = ProductModeResolver.resolve(requested, evidence())

        /** `ProductModeController.currentState()`: the request does not outlive the home role. */
        fun read() = mode().also { ProductModeResolver.requestAfterLosingHome(requested, evidence())?.let { requested = it } }.let { mode() }
    }

    @Test
    fun `the setting maps to a readiness, and an unreadable one is tried anyway`() {
        assertThat(AppPinningPolicy.readiness(1)).isEqualTo(PinningReadiness.READY)
        assertThat(AppPinningPolicy.readiness(0)).isEqualTo(PinningReadiness.DISABLED_IN_SETTINGS)
        assertThat(AppPinningPolicy.readiness(null)).isEqualTo(PinningReadiness.UNKNOWN)
        assertThat(AppPinningPolicy.readiness(-1)).isEqualTo(PinningReadiness.UNKNOWN)
        assertThat(AppPinningPolicy.SETTING_KEY).isEqualTo("lock_to_app_enabled")
    }

    @Test
    fun `Fijar is offered at home on a phone that is not a Device Owner, unless Android's switch is known to be off`() {
        val home = Phone(requested = ProductModeRequest.PINNED).read()
        assertThat(AppPinningPolicy.canRequest(PinningReadiness.READY, home)).isTrue()
        assertThat(AppPinningPolicy.canRequest(PinningReadiness.UNKNOWN, home)).isTrue()
        assertThat(AppPinningPolicy.canRequest(PinningReadiness.DISABLED_IN_SETTINGS, home)).isFalse()
        // not home: there is no level to pin
        assertThat(AppPinningPolicy.canRequest(PinningReadiness.READY, Phone(home = false).read())).isFalse()
        // already pinned: nothing to ask
        assertThat(AppPinningPolicy.canRequest(PinningReadiness.READY, Phone(pin = ActivityManager.LOCK_TASK_MODE_PINNED).read())).isFalse()
        // a Device Owner is Dedicated, whatever is asked
        val owner = ProductModeResolver.resolve(null, DedicatedProvisioningEvidence(true, true, true, true, ActivityManager.LOCK_TASK_MODE_LOCKED))
        assertThat(AppPinningPolicy.canRequest(PinningReadiness.READY, owner)).isFalse()
    }

    @Test
    fun `request then confirmation then the listener unpinning, and the state follows Android`() {
        val phone = Phone(requested = ProductModeRequest.PINNED)
        // asked for, not yet confirmed: the page says Launcher is what is in force
        assertThat(phone.read().requestedLevel).isEqualTo(ProductLevel.PINNED)
        assertThat(phone.read().level).isEqualTo(ProductLevel.LAUNCHER)
        // Android's own dialog is confirmed
        phone.pin = ActivityManager.LOCK_TASK_MODE_PINNED
        assertThat(phone.read().level).isEqualTo(ProductLevel.PINNED)
        // the listener unpins with the system gesture: Pinned ends, the request stays what it was
        phone.pin = ActivityManager.LOCK_TASK_MODE_NONE
        assertThat(phone.read().level).isEqualTo(ProductLevel.LAUNCHER)
        assertThat(phone.read().requestedLevel).isEqualTo(ProductLevel.PINNED)
    }

    @Test
    fun `losing home ends Pinned cleanly and the next time Polylute is home it starts at Launcher`() {
        val phone = Phone(requested = ProductModeRequest.PINNED, pin = ActivityManager.LOCK_TASK_MODE_PINNED)
        assertThat(phone.read().level).isEqualTo(ProductLevel.PINNED)

        phone.home = false
        assertThat(ProductModeResolver.shouldReleasePin(DedicatedProvisioningEvidence(false, false, false, false, phone.pin))).isTrue()
        phone.pin = ActivityManager.LOCK_TASK_MODE_NONE // the activity released it
        val after = phone.read()
        assertThat(after.level).isNull()
        assertThat(phone.requested).isEqualTo(ProductModeRequest.APP)

        // chosen as home again: nothing surprising happens
        phone.home = true
        assertThat(phone.read().level).isEqualTo(ProductLevel.LAUNCHER)
        assertThat(phone.read().requestedLevel).isEqualTo(ProductLevel.LAUNCHER)
    }
}
