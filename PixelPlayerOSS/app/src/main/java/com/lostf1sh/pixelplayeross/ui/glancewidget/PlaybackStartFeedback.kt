package com.lostf1sh.pixelplayeross.ui.glancewidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lostf1sh.pixelplayeross.R

/** A refused background start remains silent; the listener gets a way forward. */
fun showPlaybackStartRefused(context: Context) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context.applicationContext, R.string.playback_open_to_resume, Toast.LENGTH_LONG).show()
    }
}
