package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OfflineNetworkConstraintsTest {
    @Test fun `offline jobs require unmetered WiFi including on phones with unmetered mobile data`() {
        val constraints = offlineNetworkConstraints()
        // WorkManager uses the explicit request on API 28+ and clears its legacy network-type enum.
        val request = constraints.requiredNetworkRequest!!
        assertThat(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isTrue()
        assertThat(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isFalse()
        assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
        assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue()
    }
}
