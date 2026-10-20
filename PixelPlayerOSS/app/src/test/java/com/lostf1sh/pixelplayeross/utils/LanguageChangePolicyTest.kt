package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B16-G3: a language change is exactly one recreation, whatever order the signals arrive in. */
class LanguageChangePolicyTest {

    @Test
    fun `the platform reports a per-app locale change as a configuration event from Android 13`() {
        assertThat(LanguageChangePolicy.platformReportsChange(30)).isFalse()
        assertThat(LanguageChangePolicy.platformReportsChange(32)).isFalse()
        assertThat(LanguageChangePolicy.platformReportsChange(33)).isTrue()
        assertThat(LanguageChangePolicy.platformReportsChange(35)).isTrue()
        assertThat(LanguageChangePolicy.platformReportsChange(37)).isTrue()
    }

    @Test
    fun `the language in force is the app's own choice, or the configuration's when it follows the system`() {
        assertThat(LanguageChangePolicy.effective("sv", "es-ES")).isEqualTo("sv")
        assertThat(LanguageChangePolicy.effective("", "es-ES")).isEqualTo("es-ES")
        assertThat(LanguageChangePolicy.effective("  ", "en-US")).isEqualTo("en-US")
    }

    @Test
    fun `the gate says nothing while the language is the one the activity was created in`() {
        val gate = LanguageRecreateGate(createdWith = "sv")
        assertThat(gate.shouldRecreate("sv")).isFalse()
        assertThat(gate.shouldRecreate("sv")).isFalse()
    }

    @Test
    fun `the gate says yes once when the language differs, and never again`() {
        val gate = LanguageRecreateGate(createdWith = "es")
        assertThat(gate.shouldRecreate("sv")).isTrue()
        assertThat(gate.shouldRecreate("sv")).isFalse()
        assertThat(gate.shouldRecreate("en")).isFalse()          // even a further change: this instance is already on its way out
    }

    @Test
    fun `the three signals in any order make exactly one recreation`() {
        // The stored preference, the platform's configuration event and the grace timer all ask. Every order of any subset.
        val signals = listOf("preference", "configuration", "grace")
        val orders = signals.permutations()
        assertThat(orders).hasSize(6)
        for (order in orders) {
            val gate = LanguageRecreateGate(createdWith = "es")
            val recreations = order.count { gate.shouldRecreate("sv") }
            assertThat(recreations).isEqualTo(1)
        }
    }

    @Test
    fun `a new activity instance is created in the new language and has nothing to do`() {
        val old = LanguageRecreateGate(createdWith = "es")
        assertThat(old.shouldRecreate("sv")).isTrue()
        val recreated = LanguageRecreateGate(createdWith = "sv")      // what the new instance builds from the language in force
        assertThat(recreated.shouldRecreate("sv")).isFalse()
    }

    @Test
    fun `a change back to the language it was created in before any signal is still no recreation`() {
        val gate = LanguageRecreateGate(createdWith = "es")
        assertThat(gate.shouldRecreate("es")).isFalse()
        assertThat(gate.shouldRecreate("sv")).isTrue()
    }

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) listOf(this) else indices.flatMap { i -> (take(i) + drop(i + 1)).permutations().map { listOf(this[i]) + it } }
}
