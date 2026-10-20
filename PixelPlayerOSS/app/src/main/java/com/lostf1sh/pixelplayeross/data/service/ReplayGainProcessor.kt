package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.media.ReplayGainManager
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.ReplayGainRuntimeState
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * Owns all ReplayGain volume-normalization state and logic, extracted from
 * [MusicService].
 *
 * The processor encapsulates the RG enable/album-gain flags, the in-flight IO
 * read job, the "expected vs. user-selected" volume bookkeeping that keeps a
 * programmatic RG volume change from being mistaken for a user gesture, and the
 * crossfade-aware application of the computed gain. It reaches the players only
 * through the injected [DualPlayerEngine]; the service supplies the current
 * session media item via [currentSessionMediaItem] so stale IO results can be
 * discarded.
 *
 * All public methods are main-thread affine (the IO tag read is dispatched
 * internally), matching the original in-service behaviour.
 */
class ReplayGainProcessor(
    private val engine: DualPlayerEngine,
    private val replayGainManager: ReplayGainManager,
    private val scope: CoroutineScope,
    private val currentSessionMediaItem: () -> MediaItem?,
) {
    companion object {
        private const val TAG = "MusicService_PixelPlayer"

        /** Below this, a contributor is treated as unity — it is not doing anything audible. */
        internal const val UNITY_EPSILON = 0.0001f

        /**
         * The composed gain: the user's level with ReplayGain *modifying* it, never replacing it.
         *
         * Single definition, used by the processor and by the requirement attribution, so the two
         * cannot drift apart. ReplayGain applied twice would require a second multiplication
         * somewhere, and there is deliberately nowhere for one to live.
         */
        internal fun effectiveGainOf(
            userDigitalGain: Float,
            replayGainFactor: Float?,
            profileCompensation: Float = 1f,
        ): Float = (userDigitalGain * (replayGainFactor ?: 1f) * profileCompensation).coerceIn(0f, 1f)

        /**
         * Recovers the user's own contribution from a composed value.
         *
         * An external write to `player.volume` carries the product of both. Storing it as the
         * user's level would fold ReplayGain's attenuation into the user's setting, and the next
         * application would multiply by ReplayGain again.
         */
        internal fun userGainFrom(composed: Float, replayGainFactor: Float?): Float {
            val factor = replayGainFactor ?: 1f
            return if (factor > UNITY_EPSILON) {
                (composed / factor).coerceIn(0f, 1f)
            } else {
                composed.coerceIn(0f, 1f)
            }
        }

        /** Whether the user's own level requires a gain stage. */
        internal fun requiresDigitalVolume(userDigitalGain: Float): Boolean =
            abs(userDigitalGain - 1f) > UNITY_EPSILON

        /**
         * Whether ReplayGain requires a gain stage.
         *
         * Non-unity, not merely enabled: ReplayGain switched on for a track whose tags ask for
         * 0 dB is a mathematical identity, the same way a flat enabled EQ is.
         */
        internal fun requiresReplayGain(replayGainFactor: Float?): Boolean =
            replayGainFactor?.let { abs(it - 1f) > UNITY_EPSILON } ?: false
    }

    private var enabled = false
    private var useAlbumGain = false
    private var job: Job? = null
    private var requestToken = 0L

    // ── The two gain contributors, kept apart ───────────────────────────
    //
    // `player.volume` is an *output*: the product of these two. It is not the place either
    // contributor is remembered, and neither requirement may be derived from it — reading a
    // combined value back would attribute ReplayGain's attenuation to the user's own volume
    // control, and the two have to be nameable separately for the truth surfaces.
    //
    //   effectiveGain = userDigitalGain * replayGainFactor
    //
    // ReplayGain *modifies* the user's selected level; it does not replace it. The previous model
    // wrote the ReplayGain multiplier straight into `player.volume`, which silently discarded the
    // user's own setting for as long as ReplayGain was on, and restored it — often audibly — when
    // ReplayGain was switched off. Multiplication is the correct composition and is what makes
    // "ReplayGain applied exactly once" a checkable claim rather than an assumption.

    /** The user's own digital level. Survives ReplayGain being toggled in either direction. */
    private var userDigitalGain = 1f

    /**
     * The ReplayGain factor, or null when ReplayGain has not produced one.
     *
     * Null and 1.0 are deliberately distinct: null means "no ReplayGain contribution", 1.0 means
     * "ReplayGain ran and this track needs no correction". Only the first is a reason for
     * [replayGainActive] to be false when the feature is switched on.
     */
    private var replayGainFactor: Float? = null

    /**
     * The active headphone profile's boost compensation (`ProfileGainCompensation.factor`), ≤ 1.
     * A contributor of its own, so the user's level is never moved by a profile (AUD-025, REV-1).
     */
    private var profileCompensation = 1f

    /** A factor computed mid-crossfade, applied once the transition finishes. */
    private var pendingReplayGainFactor: Float? = null

    // Diagnostic-only override. Held separately so the normal paths cannot wipe it: an injected
    // factor must survive the `apply()` calls that fire on metadata changes, or a measurement
    // window closes with the state it was measuring already cleared. Null in production.
    private var diagnosticReplayGainFactor: Float? = null
    private var diagnosticOverrideActive = false

    // Effective volume we just wrote programmatically — used to ignore the echoed onVolumeChanged.
    private var expectedVolume: Float? = null

    // MediaId for which the current factor was computed.
    private var lastMediaId: String? = null

    /**
     * The gain to write to the player: both contributors, multiplied exactly once.
     *
     * Every write to `player.volume` in this class goes through here. There is no other
     * multiplication of the two, which is what makes a double application impossible rather than
     * merely unlikely.
     */
    private val effectiveGain: Float
        get() = effectiveGainOf(userDigitalGain, replayGainFactor, profileCompensation)

    /** Whether ReplayGain is contributing a non-unity factor right now. */
    val replayGainActive: Boolean
        get() = enabled && (replayGainFactor?.let { abs(it - 1f) > 0.0001f } ?: false)

    /** The user's own level, for requirement attribution. */
    val userGain: Float
        get() = userDigitalGain

    /** The composed gain, for diagnostics and the crossfade target. */
    val combinedGain: Float
        get() = effectiveGain

    fun setEnabled(value: Boolean) {
        enabled = value
        publish(applied = false)
    }

    fun setUseAlbumGain(value: Boolean) {
        useAlbumGain = value
        publish(applied = false)
    }

    /** Seeds the user's digital gain from the player's current volume on startup. */
    fun captureUserVolume(volume: Float) {
        userDigitalGain = volume.coerceIn(0f, 1f)
        publishGain()
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Diagnostic only: forces ReplayGain to a known factor, bypassing the tag read.
     *
     * The physical gain proof needs a *deterministic* factor — "about 0.5" cannot distinguish a
     * correct product from a double application. Real tags give whatever the album happens to
     * carry, so the factor is injected instead. It writes the same field the tag path writes and
     * composes through the same single multiplication, so the arithmetic under test is identical.
     *
     * @param factor the ReplayGain factor, or null to clear ReplayGain's contribution.
     */
    /**
     * Diagnostic only: sets the user's digital gain directly, as the contributor it is.
     *
     * Deliberately *not* routed through `player.volume`. That path carries the composed value, and
     * the echo guard that distinguishes a programmatic write from a gesture compares against the
     * last composed value — so setting the user gain to a number that happens to equal the current
     * effective gain (0.5 while ReplayGain is already 0.5) is indistinguishable from its own echo
     * and is silently discarded. Writing the contributor is both unambiguous and a more honest
     * description of what is under test.
     */
    fun setDiagnosticUserGain(gain: Float) {
        userDigitalGain = gain.coerceIn(0f, 1f)
        if (!engine.isTransitionRunning()) {
            applyEffectiveGain(engine.masterPlayer)
        } else {
            publishGain()
        }
    }

    /**
     * Sets the active profile's boost compensation. Called for every published profile EQ,
     * including the first one after a restart, so it never depends on observing a switch (REV-2).
     */
    fun setProfileCompensation(factor: Float) {
        profileCompensation = factor.coerceIn(0f, 1f)
        if (!engine.isTransitionRunning()) {
            applyEffectiveGain(engine.masterPlayer)
        } else {
            publishGain()
        }
    }

    fun setDiagnosticReplayGainFactor(factor: Float?) {
        diagnosticReplayGainFactor = factor
        diagnosticOverrideActive = factor != null
        replayGainFactor = factor
        if (!engine.isTransitionRunning()) {
            applyEffectiveGain(engine.masterPlayer)
        } else {
            publishGain()
        }
    }

    /**
     * Mirrors [Player.Listener.onVolumeChanged]. A write we made ourselves (via [expectedVolume]) is
     * its own echo and ignored. Any other write is *not* the user choosing a level: every user
     * control reaches [setDiagnosticUserGain] through `DualPlayerEngine.setUserDigitalGain`, which
     * writes the contributor. Inferring intent from `player.volume` is what let the engine's
     * `cancelNext` (`volume = 1f`, ~1.5 s into every track) reset the user's gain to 100 % (AUD-001).
     *
     * So a foreign write is overwritten with the composed gain instead: `player.volume` is an
     * output of this class, and nothing else may raise it.
     */
    fun onPlayerVolumeChanged(volume: Float) {
        if (engine.isTransitionRunning()) return
        val expected = expectedVolume
        if (expected != null && abs(expected - volume) < 0.001f) {
            expectedVolume = null
            return
        }
        expectedVolume = null
        if (abs(effectiveGain - volume) < 0.001f) return
        applyEffectiveGain(engine.masterPlayer)
    }

    /**
     * Writes the composed gain to the player.
     *
     * The only place `player.volume` is assigned in this class.
     */
    private fun applyEffectiveGain(player: Player) {
        val gain = effectiveGain
        expectedVolume = gain
        player.volume = gain
        publishGain()
    }

    /**
     * Publishes the two contributors and their product.
     *
     * The routing policy reads these rather than the player's volume, so that each requirement is
     * owned by the contributor that causes it. Called from every path that changes either value —
     * including the one that only records a pending factor and writes nothing, because the pending
     * factor is itself a change the requirements have to see.
     */
    private fun publishGain() {
        engine.publishGainModel(
            userDigitalGain = userDigitalGain,
            replayGainFactor = replayGainFactor,
            combinedGain = effectiveGain,
            profileCompensation = profileCompensation,
        )
    }

    /**
     * Re-applies the last computed RG volume immediately (no IO) unless a
     * crossfade is running, preventing a brief full-volume spike on resume,
     * same-track seeks, and queue edits.
     */
    fun reapplyLastAppliedVolume(player: Player) {
        if (engine.isTransitionRunning()) return
        applyEffectiveGain(player)
    }

    /**
     * Pre-computes ReplayGain for the incoming crossfade track. Seeds
     * [DualPlayerEngine.incomingTrackEffectiveVolume] from cache when available so
     * the fade loop ends at the correct volume, then kicks off [apply] which (since
     * the transition is running) stores the result as a pending volume.
     */
    fun prepareForTransition(player: Player) {
        val incomingItem = player.currentMediaItem
        // The fade loop ramps to a *level*, so it needs the composed gain — not the ReplayGain
        // factor alone. Passing the bare factor would make the fade land at a level that ignores
        // the user's own volume, which is audible as a jump at the moment the fade completes.
        cachedVolumeFor(incomingItem)?.let {
            engine.incomingTrackEffectiveVolume = effectiveGainOf(userDigitalGain, it, profileCompensation)
        }
        apply(incomingItem)
    }

    /**
     * Applies ReplayGain volume normalization to [mediaItem]. Reads RG tags from
     * the file on an IO thread and adjusts the master player's volume accordingly.
     */
    fun apply(mediaItem: MediaItem?) {
        job?.cancel()
        requestToken += 1
        val currentRequestToken = requestToken

        if (mediaItem == null) {
            publish(applied = false)
            return
        }

        if (diagnosticOverrideActive) {
            // A diagnostic factor is in force; the tag path must not overwrite or clear it.
            replayGainFactor = diagnosticReplayGainFactor
            if (!engine.isTransitionRunning()) {
                applyEffectiveGain(engine.masterPlayer)
            }
            publish(applied = true, mediaId = mediaItem.mediaId)
            return
        }

        if (!enabled) {
            pendingReplayGainFactor = null
            // Switching ReplayGain off removes only its contribution. The user's own level is
            // untouched and becomes the whole gain again.
            replayGainFactor = null
            if (!engine.isTransitionRunning()) {
                applyEffectiveGain(engine.masterPlayer)
            }
            publish(applied = false)
            return
        }

        val mediaId = mediaItem.mediaId
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)

        if (filePath.isNullOrBlank()) {
            Timber.tag(TAG).d("ReplayGain: No file path for track, keeping user-selected volume")
            // No tags to read: ReplayGain contributes nothing for this track, and the user's level
            // stands on its own.
            replayGainFactor = null
            if (!engine.isTransitionRunning()) {
                applyEffectiveGain(engine.masterPlayer)
            }
            publish(applied = false, mediaId = mediaId)
            return
        }

        val resolvedUseAlbumGain = useAlbumGain

        // Apply the last known RG volume immediately so there is no full-volume spike
        // while the IO coroutine reads the tags for the new track.
        if (!engine.isTransitionRunning()) {
            applyEffectiveGain(engine.masterPlayer)
        }

        // Read ReplayGain tags on IO thread to avoid blocking main
        job = scope.launch {
            val rgValues = withContext(Dispatchers.IO) {
                replayGainManager.readReplayGain(filePath)
            }

            if (currentRequestToken != requestToken) {
                return@launch
            }

            val currentMediaId = currentSessionMediaItem()?.mediaId
            if (currentMediaId != mediaId) {
                Timber.tag(TAG).d("ReplayGain: Ignoring stale result for mediaId=%s", mediaId)
                return@launch
            }

            val volume = replayGainManager.getVolumeMultiplier(
                rgValues,
                useAlbumGain = resolvedUseAlbumGain
            )

            if (engine.isTransitionRunning()) {
                // Store for application after transition completes.
                // Also pass to engine so the crossfade loop ends at the correct RG
                // volume instead of hard-coding 1f, preventing the audible jump.
                pendingReplayGainFactor = volume
                // Composed for the fade loop; see prepareForTransition.
                engine.incomingTrackEffectiveVolume = effectiveGainOf(userDigitalGain, volume, profileCompensation)
                // No player write happens on this path, but the factor still changed what the
                // signal path must do, so the requirements have to see it.
                publishGain()
                Timber.tag(TAG).d("ReplayGain: Stored pending factor=%.2f for %s (transition running)",
                    volume, mediaItem.mediaMetadata.title
                )
            } else {
                pendingReplayGainFactor = null
                engine.incomingTrackEffectiveVolume = null
                replayGainFactor = volume
                lastMediaId = mediaId
                applyEffectiveGain(engine.masterPlayer)
                publish(applied = true, mediaId = mediaId, multiplier = volume)
                Timber.tag(TAG).d("ReplayGain: Applied volume=%.2f for %s",
                    volume, mediaItem.mediaMetadata.title
                )
            }
        }
    }

    /**
     * Returns the cached ReplayGain volume for a media item if already computed, or null.
     * Does NOT trigger an IO read — only reads from the in-memory cache.
     */
    private fun cachedVolumeFor(mediaItem: MediaItem?): Float? {
        if (!enabled || mediaItem == null) return null
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return null
        if (filePath.isBlank()) return null
        val cached = replayGainManager.getCachedReplayGain(filePath) ?: return null
        return replayGainManager.getVolumeMultiplier(cached, useAlbumGain = useAlbumGain)
    }

    /**
     * Pre-fetches ReplayGain tags for a media item into the cache without applying the volume.
     * Called on queue changes and track transitions so the cache is warm by the time
     * [apply] runs, avoiding the 1-2s JNI read delay on playback start.
     */
    fun prefetch(mediaItem: MediaItem?) {
        if (!enabled || mediaItem == null) return
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return
        if (filePath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            replayGainManager.readReplayGain(filePath)
        }
    }

    /**
     * Applies the volume that was held back while a crossfade was running, or
     * triggers a fresh computation if none was pending.
     */
    fun onTransitionFinished() {
        val player = engine.masterPlayer
        val pending = pendingReplayGainFactor
        pendingReplayGainFactor = null

        if (!enabled) {
            replayGainFactor = null
            applyEffectiveGain(player)
            Timber.tag(TAG).d("ReplayGain: Transition finished, RG disabled — user gain=%.2f", userDigitalGain)
            return
        }

        if (pending != null) {
            // The crossfade loop ramps to this value; apply it now as the stable post-fade volume.
            // Also update lastAppliedVolume so any subsequent onPositionDiscontinuity
            // (REASON_AUTO_TRANSITION fires right after crossfade ends) uses this value
            // immediately instead of launching a new IO coroutine and causing a spike.
            replayGainFactor = pending
            applyEffectiveGain(player)
            publish(applied = true, mediaId = player.currentMediaItem?.mediaId, multiplier = pending)
            Timber.tag(TAG).d("ReplayGain: Transition finished, applied pending volume=%.2f", pending)
        } else {
            // No pending volume was computed during transition, trigger full computation
            apply(currentSessionMediaItem())
            Timber.tag(TAG).d("ReplayGain: Transition finished, no pending volume — triggering full recomputation")
        }
    }

    /**
     * Recomputes RG only when the track actually changed; otherwise re-applies the
     * last known volume. [onMediaMetadataChanged] also fires on queue edits without a
     * track change, which would otherwise launch a redundant IO read and cause a spike.
     */
    fun onMediaMetadataChanged(currentItem: MediaItem?) {
        val currentMediaId = currentItem?.mediaId ?: return
        if (currentMediaId != lastMediaId) {
            apply(currentItem)
        } else {
            reapplyLastAppliedVolume(engine.masterPlayer)
        }
    }

    private fun publish(
        applied: Boolean,
        mediaId: String? = currentSessionMediaItem()?.mediaId,
        multiplier: Float? = null,
    ) {
        engine.publishReplayGainState(
            ReplayGainRuntimeState(
                requested = enabled,
                useAlbumGain = useAlbumGain,
                applied = applied,
                mediaId = mediaId,
                multiplier = multiplier,
            )
        )
    }
}
