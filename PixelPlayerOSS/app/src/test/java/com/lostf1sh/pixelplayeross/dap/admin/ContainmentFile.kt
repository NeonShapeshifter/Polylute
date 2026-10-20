package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

/**
 * The real [DapContainmentStore] over an in-memory preference file (B35-fix1 R2). A fresh install is a file with no keys at all, which is the
 * one thing a model that keeps the record in a `var` cannot show: what the store answers before anything has been written.
 */
internal class ContainmentFile(initial: Map<String, Boolean> = emptyMap()) {
    val values = initial.toMutableMap()

    private val prefs: SharedPreferences = mockk(relaxed = true) {
        every { getBoolean(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { contains(any()) } answers { firstArg<String>() in values }
        every { edit() } answers {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { editor.putBoolean(any(), any()) } answers {
                values[firstArg()] = secondArg()
                editor
            }
            every { editor.commit() } returns true
            editor
        }
    }

    val store = DapContainmentStore(
        mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { getSharedPreferences(any(), any()) } returns prefs
        },
    )
}
