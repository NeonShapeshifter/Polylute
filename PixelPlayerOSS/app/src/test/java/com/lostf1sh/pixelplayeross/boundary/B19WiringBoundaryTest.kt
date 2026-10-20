package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B19: where each decision of the batch is made. Source assertions, like the rest of this suite: the engine, the service and the view
 * model need a device, so these pin the wiring around the pure parts the other tests execute (`QueueLoadAnchoredOrderTest`,
 * `QueueLoadSkipSequenceTest`, `ShuffleToggleIntentTest`, `SessionPublicationCostTest`).
 */
class B19WiringBoundaryTest {

    private val main = "src/main/java/com/lostf1sh/pixelplayeross"

    private fun source(relative: String): String {
        val direct = File(relative)
        val file = if (direct.isFile) direct else File("app/$relative")
        check(file.isFile) { "Could not locate $relative from ${File(".").absolutePath}" }
        return file.readText()
    }

    private fun block(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        check(start >= 0) { "missing: $from" }
        val end = text.indexOf(to, start + from.length)
        check(end > start) { "missing end: $to" }
        return text.substring(start, end)
    }

    private val vm get() = source("$main/presentation/viewmodel/PlayerViewModel.kt")
    private val holder get() = source("$main/presentation/viewmodel/PlaybackStateHolder.kt")
    private val engine get() = source("$main/data/service/player/DualPlayerEngine.kt")
    private val service get() = source("$main/data/service/MusicService.kt")
    private val planning get() = source("$main/presentation/viewmodel/QueueLoadPlanning.kt")
    private val hold get() = source("$main/data/service/player/QueueLoadSkipHold.kt")
    private val mapping get() = source("$main/data/service/player/MappingPlayer.kt")

    // --- S1: the block is anchored to the sequence, and "played" is what was played -----------------------

    @Test
    fun `the first stage anchors the block to the sequence with the engine's one-call order, before anything plays`() {
        val first = block(planning, "internal fun putFirstSongsInPlayer(", "/**\n * The second stage")
        val set = first.indexOf("player.setMediaItems(firstItems, plan.startLocalIndex, 0L)")
        val order = first.indexOf("plan.blockOrder")
        val after = first.indexOf("afterLoad()")
        val settle = first.indexOf("hold.settle(")
        assertThat(set).isAtLeast(0)
        // The load, then the order, then the flag/prepare/play, then the skips that were held: they follow the sequence.
        assertThat(order).isGreaterThan(set)
        assertThat(after).isGreaterThan(order)
        assertThat(settle).isGreaterThan(after)
        // What the view model hands it is the engine's setPlayOrder: one setShuffleOrder, not a replacement of the block.
        assertThat(vm).contains("setBlockOrder = dualPlayerEngine::setPlayOrder")
    }

    @Test
    fun `what was played is recorded by occurrence from the player's transitions, not read as a prefix of an order`() {
        assertThat(planning).doesNotContain("songsPlayedInBlock")
        assertThat(planning).doesNotContain("playedOf")
        assertThat(planning).contains("class BlockVisits(")
        assertThat(planning).contains("override fun onMediaItemTransition(")
        // A list index per occurrence: the block's index shifted by where it sits in the list, then the player's own index.
        assertThat(planning).contains("plan.windowStart + index")
        // The old lookups by song id are gone from the order arithmetic.
        val order = source("$main/utils/PlayOrder.kt")
        assertThat(order).doesNotContain("fun indicesOf(")
        assertThat(order).contains("fun dealt(baseIds: List<String>, sequence: List<String>, played: List<Int> = emptyList())")
        // The final order is the plan's: the sequence led by the occurrences that were played.
        assertThat(vm).contains("plan.orderAfterAttach(played)?.let { applyPlayOrder(songsToPlay.size, it, played.size) }")
    }

    @Test
    fun `the sequence's order is computed off the main thread before the player is touched`() {
        val load = block(vm, "    private suspend fun internalPlaySongs(", "    private suspend fun buildResolvedPlaybackMediaItem")
        val order = load.indexOf("PlayOrder.fromSequence(songsToPlay.map { it.id }, playSequence.map { it.id })")
        assertThat(order).isAtLeast(0)
        assertThat(load.lastIndexOf("withContext(Dispatchers.Default)", order)).isGreaterThan(load.indexOf("val plan: QueueLoadPlan"))
        assertThat(load.indexOf("putFirstSongsInPlayer(")).isGreaterThan(order)
    }

    // --- S2: a toggle flips what shuffle is going to be ----------------------------------------------------

    @Test
    fun `every toggle reads the last intent and none reads the player's flag`() {
        assertThat(engine).contains("val shuffleIntent: Boolean")
        assertThat(engine).contains("::playerA.isInitialized && shuffleGate.intent")
        assertThat(holder).contains("setShuffleEnabled(!dualPlayerEngine.shuffleIntent)")
        assertThat(holder).doesNotContain("setShuffleEnabled(!dualPlayerEngine.isShuffleEnabled)")
        // The notification / lock screen / head unit action.
        val toggle = block(service, "MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE -> {", "MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON -> {")
        assertThat(toggle).contains("val enabled = !engine.shuffleIntent")
        assertThat(toggle).doesNotContain("isManualShuffleEnabled")
    }

    @Test
    fun `the Play controls ask for shuffle off, they do not flip what they read from the screen`() {
        listOf("SearchScreen.kt", "PlaylistDetailScreen.kt").forEach { name ->
            val screen = source("$main/presentation/screens/$name")
            assertThat(screen).contains("playerViewModel.turnShuffleOff()")
            assertThat(screen).doesNotContain("playerViewModel.toggleShuffle()")
        }
        // A collection's Play goes through `playSongs`, which establishes shuffle off itself (B17-H1); it never flips what it reads.
        assertThat(source("$main/presentation/screens/AutoCollectionDetailScreen.kt")).doesNotContain("playerViewModel.toggleShuffle()")
        val off = block(vm, "    fun turnShuffleOff() {", "    /** Shuffle to a given state for a queue that is being loaded")
        assertThat(off).contains("playbackStateHolder.setShuffleEnabled(false)")
    }

    // --- S4: the queue goes to the app's controller only, and the publication says how long it took ------------

    @Test
    fun `the session gives the queue to the app's controller only, through one policy`() {
        val connect = block(service, "            override fun onConnect(", "            override fun onPlayerCommandRequest(")
        assertThat(connect).contains("SessionQueuePolicy.getsQueue(")
        assertThat(connect).contains("isMediaNotificationController = session.isMediaNotificationController(controller)")
        assertThat(connect).contains("SessionQueuePolicy.playerCommands(defaultResult.availablePlayerCommands, getsQueue)")
        // The commands that go out are the policy's, not the defaults it started from.
        assertThat(connect).doesNotContain("                    defaultResult.availablePlayerCommands\n                )")
        // The policy is the only place that decides it, and it takes COMMAND_GET_TIMELINE and nothing else away.
        val policy = source("$main/data/service/SessionQueuePolicy.kt")
        assertThat(policy.split("Player.COMMAND_GET_TIMELINE").size - 1).isEqualTo(1)
        assertThat(policy).contains("controllerPackage == appPackage && !isMediaNotificationController")
        assertThat(service).doesNotContain("COMMAND_GET_TIMELINE")
    }

    @Test
    fun `the mapped timeline compares by the timeline it maps, not by walking and mapping every item twice`() {
        val timeline = block(mapping, "internal class MappedTimeline(", "    override fun getWindow(")
        assertThat(timeline).contains("other.delegate === delegate")
        assertThat(timeline).contains("System.identityHashCode(delegate)")
    }

    @Test
    fun `the publication of the session is timed with a bracket around its own listener and logged with the size`() {
        // The beginning goes on the wrapped player before the session registers on it; the end right after, on both paths.
        assertThat(service).contains(").also { it.addListener(publicationMeter.begin) }")
        val build = block(service, "        mediaSession = MediaSession.Builder(this, wrapFadingPlayer(engine.masterPlayer))", "        val localOnlyProvider")
        assertThat(build.indexOf(".build()")).isLessThan(build.indexOf("mediaSession?.player?.addListener(publicationMeter.end)"))
        val swap = block(service, "    private fun publishMediaSessionPlayer(", "    private fun syncLocalListeningStatsFromPlayer(")
        assertThat(swap.indexOf("session.player = wrappedPlayer")).isLessThan(swap.indexOf("wrappedPlayer.addListener(publicationMeter.end)"))
        assertThat(swap).contains("oldPlayer.removeListener(publicationMeter.begin)")
        assertThat(swap).contains("oldPlayer.removeListener(publicationMeter.end)")
        assertThat(service).contains("\"session publication %d ms (session %d ms, controllers %d ms) items=%d reason=%d\"")
    }

    @Test
    fun `a slow publication names what the main thread was busy with, and the connection of each controller is logged with its queue decision`() {
        // B31 P2.3: the "controllers" part is a message's wait at the end of the main queue; the probe is on the looper's message log only
        // inside the window, and nothing else in the app uses that log.
        assertThat(service).contains("busy = MainLooperBusyProbe(")
        assertThat(service).contains("Looper.getMainLooper().setMessageLogging(printer)")
        assertThat(service).contains("\"session publication waited %d ms behind: %s\"")
        assertThat(service).contains("\"session controller connected: package=%s notification=%s getsQueue=%s\"")
        assertThat(service.split("setMessageLogging").size - 1).isEqualTo(1)
    }

    // --- S3: held skips are a sequence ---------------------------------------------------------------------

    @Test
    fun `the hold keeps the actions in order, bounded, and the session's player asks it with the neighbour and the repeat mode`() {
        assertThat(hold).contains("private val waiting = ArrayDeque<Int>()")
        assertThat(hold).contains("const val MAX_WAITING = 32")
        // No running sum of directions: no statement adds to a counter (the doc may say what B18 did).
        assertThat(hold.lines().none { it.trim().startsWith("held +=") || it.trim().startsWith("private var held") }).isTrue()
        assertThat(mapping).contains("hold.hold(direction, innerPlayer.hasQueueNeighbour(direction), innerPlayer.repeatMode == Player.REPEAT_MODE_ALL)")
        // Both stages replay the same way, against the real queue.
        assertThat(planning.split("hold.settle(player.skipSteps())").size - 1).isEqualTo(2)
    }
}
