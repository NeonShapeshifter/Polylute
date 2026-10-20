package com.lostf1sh.pixelplayeross.dap.admin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.lostf1sh.pixelplayeross.data.diagnostics.DebugHooks

/**
 * Debug builds only (B27-A5): a provider that is nothing but its `onCreate`, which runs when the process starts and installs the
 * debug side of [DebugHooks] so the main source can offer the "mark noise" row without knowing this class exists. Release builds
 * have no provider and no hook, and the row is never shown.
 */
class DebugHooksProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        DebugHooks.markNoise = { context -> NoiseMark.mark(context)?.name }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
