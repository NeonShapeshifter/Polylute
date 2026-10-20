package com.lostf1sh.pixelplayeross.ui.ds

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DapKeyboardRegistryTest {
    @Test
    fun `lock dismissal reaches focused editors in each window even as focus is cleared`() {
        val registry = DapKeyboardRegistry()
        val root = Any()
        val dialog = Any()
        val unfocused = Any()
        val dismissed = mutableListOf<Any>()
        listOf(root, dialog, unfocused).forEach { token ->
            registry.registerDismiss(token) {
                dismissed += token
                registry.focus(token, false)
            }
        }
        registry.focus(root, true)
        registry.focus(dialog, true)
        registry.dismissFocusedFields()
        assertThat(dismissed).containsExactly(root, dialog)
        registry.dismissFocusedFields()
        assertThat(dismissed).hasSize(2)
    }

    @Test
    fun `disposed editor cannot receive lock dismissal`() {
        val registry = DapKeyboardRegistry()
        val editor = Any()
        var called = false
        registry.registerDismiss(editor) { called = true }
        registry.focus(editor, true)
        registry.remove(editor)
        registry.dismissFocusedFields()
        assertThat(called).isFalse()
    }
}
