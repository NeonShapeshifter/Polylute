package com.lostf1sh.pixelplayeross.data.media

import android.content.Context
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaControllerFactory @Inject constructor() {
    /**
     * Builds the controller bound to the **main looper**, explicitly.
     *
     * This is not decorative. `MediaController` enforces an application-thread contract on every
     * getter: `verifyApplicationThread()` throws
     * `IllegalStateException("MediaController method is called from a wrong thread")` when the
     * calling thread's looper is not the controller's `applicationLooper`.
     *
     * `MediaController.Builder` resolves that looper with `getCurrentOrMainLooper()` — the
     * **constructing thread's looper if it has one**, and only falling back to main when it does
     * not. So a controller built on any looper-bearing thread would be bound to *that* thread,
     * while every reader in this app runs on the main dispatcher. Pinning it here makes the
     * contract a property of construction rather than a property of which thread happened to
     * reach the builder first.
     *
     * This is the contract `PlaybackStateHolder` relies on: it samples the player from an
     * injected `@MainAppScope` scope, and that is only sufficient because the controller is
     * provably bound to main.
     */
    fun create(
        context: Context,
        token: SessionToken,
        listener: MediaController.Listener
    ): ListenableFuture<MediaController> {
        return MediaController.Builder(context, token)
            .setListener(listener)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
    }
}
