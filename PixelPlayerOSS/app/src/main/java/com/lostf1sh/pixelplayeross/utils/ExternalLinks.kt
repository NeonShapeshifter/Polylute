package com.lostf1sh.pixelplayeross.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** DAP/device-owner policies can remove or forbid the browser even for an otherwise valid HTTPS address. */
internal fun Context.tryOpenExternalLink(url: String): Boolean = try {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
