package com.lostf1sh.pixelplayeross.dap.admin

import android.Manifest
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B15-F5: the Bluetooth toggle asks for the permission its Android version has. */
class BluetoothTogglePolicyTest {

    private val connect = Manifest.permission.BLUETOOTH_CONNECT
    private val admin = Manifest.permission.BLUETOOTH_ADMIN

    @Test
    fun `Android 11 needs BLUETOOTH_ADMIN, which BLUETOOTH_CONNECT cannot stand in for`() {
        assertThat(BluetoothTogglePolicy.requiredPermission(30)).isEqualTo(admin)
        // With the install-time permission and no BLUETOOTH_CONNECT (it does not exist yet): allowed. This is the case
        // that always refused before.
        assertThat(BluetoothTogglePolicy.mayToggle(30) { it == admin }).isTrue()
        assertThat(BluetoothTogglePolicy.mayToggle(30) { it == connect }).isFalse()
        assertThat(BluetoothTogglePolicy.mayToggle(30) { false }).isFalse()
    }

    @Test
    fun `Android 12 and later need BLUETOOTH_CONNECT`() {
        for (sdk in listOf(31, 33, 34, 37)) {
            assertThat(BluetoothTogglePolicy.requiredPermission(sdk)).isEqualTo(connect)
            assertThat(BluetoothTogglePolicy.mayToggle(sdk) { it == connect }).isTrue()
            // BLUETOOTH_ADMIN is not enough any more, and neither is nothing.
            assertThat(BluetoothTogglePolicy.mayToggle(sdk) { it == admin }).isFalse()
            assertThat(BluetoothTogglePolicy.mayToggle(sdk) { false }).isFalse()
        }
    }

    @Test
    fun `the switch is the API level where BLUETOOTH_CONNECT was introduced`() {
        assertThat(Build.VERSION_CODES.S).isEqualTo(31)
    }

    @Test
    fun `the manifest declares BLUETOOTH_ADMIN up to API 30 only, and the controller asks the policy`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains(
            """<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />""",
        )
        val controller = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/dap/admin/DapDeviceOwnerController.kt").readText()
        val toggle = controller.substringAfter("fun setBluetoothEnabled(").substringBefore("private val homeComponent")
        assertThat(toggle).contains("BluetoothTogglePolicy.mayToggle(Build.VERSION.SDK_INT)")
        assertThat(toggle).doesNotContain("Manifest.permission.BLUETOOTH_CONNECT")
        // The platform's refusal is still an answer, not a crash.
        assertThat(toggle).contains("catch (denied: SecurityException)")
    }
}
