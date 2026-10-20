package com.lostf1sh.pixelplayeross.presentation

import android.content.Context
import android.content.ContextWrapper
import com.lostf1sh.pixelplayeross.MainActivity

/** The [MainActivity] a composition runs in, or null in a host that is not it (a JVM render, a preview). */
internal tailrec fun Context.mainActivityOrNull(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.mainActivityOrNull()
    else -> null
}
