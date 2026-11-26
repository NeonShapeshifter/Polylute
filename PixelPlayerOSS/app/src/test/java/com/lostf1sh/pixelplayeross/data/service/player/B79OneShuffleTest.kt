package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.backup.module.GlobalSettingsModuleHandler
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import com.lostf1sh.pixelplayeross.data.preferences.PreferenceBackupEntry
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import com.lostf1sh.pixelplayeross.utils.Shuffler
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.system.measureNanoTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B79 item 3:
 * - Restoring a legacy `.pxpl/settings` backup containing `random_mode=fast` succeeds without breaking.
 * - Two shuffle-ons on a 500-song queue deal two distinct orders, keep the playing song first, and off restores the base order.
 * - Repeat-all wrap with shuffle on deals a fresh pass-2 order through `ShuffleRedraw` with no consecutive duplicate across the wrap.
 * - Median-of-20 benchmark of 5,000-song Fisher-Yates and full `ShuffleRedraw` comparing `java.util.Random` vs `SecureRandom`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B79OneShuffleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<DualPlayerEngine>()
    private val players = mutableListOf<ExoPlayer>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.release() } }
        players.forEach { runCatching { it.release() } }
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun testContext(): Context {
        val dir = Files.createTempDirectory("b79-shuffle").toFile().also(tempDirs::add)
        return object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = dir
        }
    }

    private fun buildEngine(): Pair<DualPlayerEngine, ExoPlayer> {
        val dacProfiles = mockk<DacProfileRepository>(relaxed = true)
        every { dacProfiles.connected } returns MutableStateFlow(emptyList())
        val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)
        every { overrides.state } returns MutableStateFlow(MaintenanceOverrides())
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val engine = DualPlayerEngine(
            context,
            mockk<HeadphoneGraphicEqController>(relaxed = true),
            dacProfiles,
            overrides,
            scope,
        )
        engines += engine
        val player = ExoPlayer.Builder(context).build().also { players += it }
        DualPlayerEngine::class.java.getDeclaredField("playerA").apply { isAccessible = true }.set(engine, player)
        DualPlayerEngine::class.java.declaredMethods.single { it.name == "addMasterPlayerListeners" }
            .apply { isAccessible = true }
            .invoke(engine, player)
        return engine to player
    }

    private fun idleLooper() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        Thread.sleep(50)
        looper.idle()
        looper.idleFor(Duration.ofMillis(50))
    }

    @Test
    fun `3a - restoring settings backup containing legacy random_mode=fast skips removed key cleanly`() = runBlocking {
        val dir = Files.createTempDirectory("b79-backup").toFile().also(tempDirs::add)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        val repo = UserPreferencesRepository(dataStore = dataStore, json = Json)
        val gson = Gson()
        val payload = gson.toJson(
            listOf(
                PreferenceBackupEntry(key = "random_mode", type = "string", stringValue = "fast"),
                PreferenceBackupEntry(key = "persistent_shuffle_enabled", type = "boolean", booleanValue = true),
            ),
        )
        GlobalSettingsModuleHandler(repo, gson).restore(payload)
        assertThat(repo.persistentShuffleEnabledFlow.first()).isTrue()
    }

    @Test
    fun `3d - two shuffle-ons in a row on a 500-song queue deal distinct orders, keep current first, and off restores base order`() {
        val (engine, player) = buildEngine()
        val items = List(500) { i ->
            MediaItem.Builder().setMediaId("s-$i").setUri("file:///synthetic-$i.flac").build()
        }
        player.setMediaItems(items, 123, 0L)
        shadowOf(Looper.getMainLooper()).idle()

        // First shuffle ON
        engine.setShuffleEnabled(true)
        idleLooper()
        val firstOrder = engine.currentPlayOrder()!!
        assertThat(PlayOrder.isPermutation(firstOrder, 500)).isTrue()
        assertThat(firstOrder[0]).isEqualTo(123)
        assertThat(PlayOrder.isIdentity(firstOrder)).isFalse()

        // Shuffle OFF -> restores base order
        engine.setShuffleEnabled(false)
        idleLooper()
        val offOrder = engine.currentPlayOrder()!!
        assertThat(offOrder).isEqualTo(PlayOrder.identity(500))

        // Second shuffle ON -> deals a fresh distinct order, current song still first
        engine.setShuffleEnabled(true)
        idleLooper()
        val secondOrder = engine.currentPlayOrder()!!
        assertThat(PlayOrder.isPermutation(secondOrder, 500)).isTrue()
        assertThat(secondOrder[0]).isEqualTo(123)
        assertThat(secondOrder.toList()).isNotEqualTo(firstOrder.toList())
    }

    @Test
    fun `3b and 3d - repeat-all wrap with shuffle on deals a fresh order via ShuffleRedraw with no boundary duplicate`() {
        val (engine, player) = buildEngine()
        val items = List(50) { i ->
            MediaItem.Builder().setMediaId("w-$i").setUri("file:///synthetic-wrap-$i.flac").build()
        }
        val pass1Order = Shuffler.deal(50)
        player.setMediaItems(items, pass1Order[0], 0L)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = true
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(pass1Order, 1L))
        shadowOf(Looper.getMainLooper()).idle()

        val listener = DualPlayerEngine::class.java.getDeclaredField("masterPlayerListener")
            .apply { isAccessible = true }
            .get(engine) as Player.Listener

        // Simulate advancing to the last song of pass 1, then wrapping naturally (REASON_AUTO) to pass 1's first song.
        val lastOfPass1 = pass1Order.last()
        val firstOfPass1 = pass1Order.first()
        player.seekTo(lastOfPass1, 0L)
        shadowOf(Looper.getMainLooper()).idle()

        // Move ExoPlayer to firstOfPass1 and restore lastMasterMediaItemIndex=lastOfPass1 so REASON_AUTO sees the natural wrap.
        player.seekTo(firstOfPass1, 0L)
        shadowOf(Looper.getMainLooper()).idle()
        DualPlayerEngine::class.java.getDeclaredField("lastMasterMediaItemIndex")
            .apply { isAccessible = true }
            .setInt(engine, lastOfPass1)
        listener.onMediaItemTransition(items[firstOfPass1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        idleLooper()

        val pass2Order = engine.currentPlayOrder()!!
        assertThat(PlayOrder.isPermutation(pass2Order, 50)).isTrue()
        assertThat(pass2Order.toList()).isNotEqualTo(pass1Order.toList())
        assertThat(pass2Order[0]).isNotEqualTo(lastOfPass1)
    }

    @Test
    fun `3d - measure 5000-song Fisher-Yates and full ShuffleRedraw median of 20 (old Random vs SecureRandom)`() = runBlocking {
        val oldRandom = java.util.Random()
        // Review 11-26: the source the app actually uses (SecureRandom bytes taken in bulk), not a bare SecureRandom.
        val secureRandom = com.lostf1sh.pixelplayeross.utils.Shuffler.defaultJavaRandom()

        fun medianMs(runs: Int = 20, block: () -> Unit): Double {
            // Warm up JIT slightly
            repeat(3) { block() }
            val samples = LongArray(runs) { measureNanoTime(block) }
            samples.sort()
            return samples[runs / 2] / 1_000_000.0
        }

        val fyOldMs = medianMs {
            PlayOrder.randomPermutation(5_000, oldRandom)
        }
        val fySecureMs = medianMs {
            PlayOrder.randomPermutation(5_000, secureRandom)
        }

        val target = object : ShuffleRedraw.Target {
            override val generation = 1L
            override val itemCount = 5_000
            override val currentIndex = 1_234
            override val shuffleOn = true
            override fun publishOrder(order: IntArray) = Unit
        }
        val redrawOldMs = medianMs {
            runBlocking(Dispatchers.Unconfined) { ShuffleRedraw.redraw(target, oldRandom) }
        }
        val redrawSecureMs = medianMs {
            runBlocking(Dispatchers.Unconfined) { ShuffleRedraw.redraw(target, secureRandom) }
        }

        println(
            "B79_BENCHMARK_5000 fisherYatesOldMs=%.4f fisherYatesSecureMs=%.4f ratioFy=%.2f redrawOldMs=%.4f redrawSecureMs=%.4f ratioRedraw=%.2f"
                .format(
                    fyOldMs,
                    fySecureMs,
                    fySecureMs / fyOldMs.coerceAtLeast(0.0001),
                    redrawOldMs,
                    redrawSecureMs,
                    redrawSecureMs / redrawOldMs.coerceAtLeast(0.0001),
                ),
        )
        assertThat(fyOldMs).isGreaterThan(0.0)
        assertThat(fySecureMs).isGreaterThan(0.0)
        assertThat(redrawOldMs).isGreaterThan(0.0)
        assertThat(redrawSecureMs).isGreaterThan(0.0)
        // The secure source must stay cheap enough for the main thread's worst case (5,000 songs well under one frame).
        assertThat(fySecureMs).isLessThan(5.0)
    }
}
