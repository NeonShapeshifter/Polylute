package com.lostf1sh.pixelplayeross.dap.mode

import android.content.Context

internal class ProductModeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun requestedMode(): ProductModeRequest? =
        preferences.getString(KEY_REQUESTED_MODE, null)
            ?.let { stored -> ProductModeRequest.entries.firstOrNull { it.name == stored } }

    fun setRequestedMode(mode: ProductModeRequest) {
        preferences.edit().putString(KEY_REQUESTED_MODE, mode.name).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "product_mode"
        const val KEY_REQUESTED_MODE = "requested_mode"
    }
}
