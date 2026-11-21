@file:Suppress("DEPRECATION")
package com.lostf1sh.pixelplayeross.data.service

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.session.MediaButtonReceiver
import android.app.ForegroundServiceStartNotAllowedException
import com.lostf1sh.pixelplayeross.ui.glancewidget.showPlaybackStartRefused
import androidx.media3.common.util.UnstableApi
import timber.log.Timber

@Suppress("DEPRECATION")
@OptIn(UnstableApi::class)
class PixelPlayerMediaButtonReceiver : MediaButtonReceiver() {

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) {
            super.onReceive(context, intent)
            return
        }

        try {
            super.onReceive(context, intent)
        } catch (e: IllegalStateException) {
            MusicService.unmarkPendingMediaButtonForegroundStart()
            Timber.tag(TAG).w(e, "Media button start denied while app cached")
            showPlaybackStartRefused(context)
        } catch (throwable: Throwable) {
            MusicService.unmarkPendingMediaButtonForegroundStart()
            Timber.tag(TAG).w(throwable, "Media button dispatch failed before MusicService start")
            throw throwable
        }
    }

    @OptIn(UnstableApi::class)
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        // Media3 has already rejected key-up, repeats, and non-play keys on API 26+ here.
        MusicService.markPendingMediaButtonForegroundStart()
        return true
    }

    @androidx.annotation.RequiresApi(31)
    override fun onForegroundServiceStartNotAllowedException(
        context: Context,
        intent: Intent,
        exception: ForegroundServiceStartNotAllowedException,
    ) {
        MusicService.unmarkPendingMediaButtonForegroundStart()
        Timber.tag(TAG).w(exception, "Media button foreground start refused")
        showPlaybackStartRefused(context)
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }
}
