package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B35-fix1 R2: the persistent HOME record means "this app wrote the entry", so a key nobody wrote is "not written". It used to default to true,
 * and a fresh install claimed an entry nobody had added: the restore and "Volver al DAP" both read it as ready and wrote nothing.
 */
class DapContainmentStoreTest {

    @Test
    fun `a missing key is not written`() {
        val file = ContainmentFile()
        assertThat(file.store.isPersistentHomeEnabled()).isFalse()
        assertThat(file.values).isEmpty()
    }

    @Test
    fun `an install that wrote the key reads it back, on and off`() {
        val file = ContainmentFile()
        file.store.setPersistentHomeEnabled(true)
        assertThat(file.store.isPersistentHomeEnabled()).isTrue()
        file.store.setPersistentHomeEnabled(false)
        assertThat(file.store.isPersistentHomeEnabled()).isFalse()
    }

    @Test
    fun `an install that already holds the key true keeps reading true`() {
        val file = ContainmentFile(mapOf("persistent_home_enabled" to true))
        assertThat(file.store.isPersistentHomeEnabled()).isTrue()
    }

    @Test
    fun `the other records keep their defaults`() {
        val store = ContainmentFile().store
        assertThat(store.isDedicatedModeEnabled()).isFalse()
        assertThat(store.isDapLockScreenEnabled()).isFalse()
        assertThat(store.isHomeExitChosen()).isFalse()
    }
}
