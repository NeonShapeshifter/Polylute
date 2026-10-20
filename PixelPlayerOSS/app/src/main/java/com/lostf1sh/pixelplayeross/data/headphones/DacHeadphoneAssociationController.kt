package com.lostf1sh.pixelplayeross.data.headphones

import android.content.Context
import com.decent.usbaudio.UsbAudioDevice
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.volume.selectSafetyDac
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/** Restores a remembered DAC/headphone association without requiring any UI lifecycle. */
@Singleton
class DacHeadphoneAssociationController @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: DualPlayerEngine,
    private val dacs: DacProfileRepository,
    private val headphones: HeadphoneProfileRepository,
    private val equalizer: HeadphoneEqualizerCoordinator,
    @AppScope private val appScope: CoroutineScope,
) {
    private var started = false
    private val usbDevice = UsbAudioDevice.getInstance(context)

    /** Guards the one-shot association reconciliation so it runs once per process, not per attach. */
    private var associationsReconciled = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        appScope.launch {
            // The same DAC Safety resolves: the one Direct opened, else the only one attached
            // (AUD-075). The route state is here so a Direct open re-evaluates it.
            combine(dacs.profiles, dacs.connected, engine.audioRouteState) { profiles, connected, _ ->
                selectSafetyDac(profiles, connected, usbDevice.openedDevicePath)?.profileId
            }.distinctUntilChanged().collect { dacId ->
                reconcileAssociationsOnce()
                if (dacId == null) return@collect
                val state = headphones.currentState()
                val associated = state.dacAssociations[dacId]
                if (associated != null && associated != state.selectedProfileId) {
                    equalizer.restoreAssociation(dacId)
                }
            }
        }
    }

    /**
     * Re-keys any association still stored under a DAC display name.
     *
     * This runs here because this is the only component that holds both sides of the association:
     * the headphone profiles record the map, and the DAC profiles are what a legacy name key has to
     * be resolved against. It runs before the restore above, so the association it looks up is
     * already on the id keying the rest of the code uses.
     *
     * It touches nothing when the map is empty, which is the state of every install that never
     * paired a DAC with headphones, and it reports rather than guesses when a name matches more
     * than one DAC profile.
     */
    private suspend fun reconcileAssociationsOnce() {
        if (associationsReconciled) return
        val current = headphones.currentState()
        if (current.dacAssociations.isEmpty()) {
            associationsReconciled = true
            return
        }
        val migration = HeadphoneProfileCatalog.reconcileDacAssociations(current, dacs.profiles.value)
        if (migration.migrated.isNotEmpty() || migration.needsAttention) {
            headphones.replaceAssociations(migration.state.dacAssociations)
            if (migration.migrated.isNotEmpty()) {
                Timber.tag(TAG).i(
                    "Re-keyed %d DAC association(s) from display name to profile id",
                    migration.migrated.size,
                )
            }
            migration.ambiguous.forEach {
                Timber.tag(TAG).w(
                    "DAC association under '%s' matches more than one DAC profile; left unbound",
                    it.key,
                )
            }
            migration.unresolved.forEach {
                Timber.tag(TAG).w(
                    "DAC association under '%s' matches no known DAC profile; left as stored",
                    it.key,
                )
            }
        }
        associationsReconciled = true
    }

    private companion object {
        const val TAG = "DacHeadphoneAssociation"
    }
}
