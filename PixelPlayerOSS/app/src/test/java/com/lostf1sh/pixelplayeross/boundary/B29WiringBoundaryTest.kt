package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B29: where the changes are wired, read at the source. What each one does is measured by its own test; this pins that the real
 * code goes through them and that what the batch must not touch is where it was.
 */
class B29WiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun text(path: String) = File(main, path).readText().replace("\r\n", "\n")

    /** The source without its comments: what a scan for a call should read (a comment may name what it explains). */
    private fun code(source: String) = source.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trim().let { line -> line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    // ── L1: the lock surface's offset is written where the finger is ─────────────────────────────

    @Test
    fun `L1 the lock surface moves with the finger through a value written in the pointer callback, and decides from it`() {
        val lock = code(text("presentation/lockscreen/DapLockScreen.kt"))
        // no coroutine between the finger and the value the release reads
        assertThat(lock).doesNotContain("snapTo(")
        assertThat(lock).doesNotContain("Animatable(")
        // (B29-fix1: the callback also notes where the finger is, for the release; the offset is still written right there)
        assertThat(lock).contains("offset.drag(amount)")
        assertThat(lock).contains("val travelDp = with(density) { (-offset.px).toDp().value }")
        assertThat(lock).contains("LockDismissPolicy.shouldOpen(travelDp, upwardDpPerS)")
        // the layer reads the same value
        assertThat(lock).contains("translationY = offset.px")
        // and the drag callback itself starts no coroutine
        val drag = lock.substringAfter("onVerticalDrag = {").substringBefore("onDragEnd")
        assertThat(drag).doesNotContain("launch")
    }

    // ── L2 / L3: the song shown follows the engine ───────────────────────────────────────────────

    @Test
    fun `L2 the displayed song is brought in line with the engine when the screen comes back and when playback starts`() {
        val vm = code(text("presentation/viewmodel/PlayerViewModel.kt"))
        val resume = vm.substringAfter("fun onMainActivityResume()").substringBefore("\n    }\n")
        assertThat(resume).contains("reconcileDisplayedItem(\"resume\")")
        assertThat(vm).contains("reconcileDisplayedItem(\"playing\")")
        val reconcile = vm.substringAfter("private fun reconcileDisplayedItem(").substringBefore("private fun syncDisplayedMediaItemIfChanged")
        // the engine is the authority, and a crossfade (where the controller is deliberately ahead of it) is left alone
        assertThat(reconcile).contains("dualPlayerEngine.masterPlayer")
        assertThat(reconcile).contains("if (dualPlayerEngine.isTransitionRunning()) return")
        assertThat(reconcile).contains("DisplayedItemReconcile.followFrom(")
    }

    @Test
    fun `L3 previous decides with the engine's position unless a crossfade has the controller ahead of it`() {
        val holder = code(text("presentation/viewmodel/PlaybackStateHolder.kt"))
        // (B29-fix1 F2: the position and the neighbour both come from `previousAuthority`, the player this rule picks)
        val authority = holder.substringAfter("private fun previousAuthority(").substringBefore("\n    }\n")
        assertThat(authority).contains("!dualPlayerEngine.isTransitionRunning()")
        assertThat(authority).contains("engine")
        assertThat(holder.substringAfter("private fun authoritativePositionMs(").substringBefore("\n\n"))
            .contains("previousAuthority(controller).currentPosition")
    }

    // ── L7: the queue swipe ──────────────────────────────────────────────────────────────────────

    @Test
    fun `L7 the queue swipe lives on the full player only, starts at the controls and is decided by the policy`() {
        val screen = code(text("presentation/components/player/FullPlayerScreen.kt"))
        assertThat(screen).contains("QueueSwipePolicy.startsInZone(")
        assertThat(screen).contains("QueueSwipePolicy.intentAfterSlop(")
        assertThat(screen).contains("QueueSwipePolicy.Intent.OTHER -> Unit")
        // B33-U1: the header has no Queue button any more: the folded sheet at the foot is the way in, and it opens the same sheet this swipe drags
        assertThat(screen).doesNotContain("onQueue =")
        assertThat(screen).doesNotContain("onShowQueue")
        // and no other surface installs it: the queue sheet (with its reorder handles) is not in this gesture's reach
        main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "FullPlayerScreen.kt" }.forEach {
            assertThat(code(it.readText())).doesNotContain("playerQueueDragGesture")
        }
    }

    // ── L9: the strip under the mini player, one place ───────────────────────────────────────────

    @Test
    fun `L9 the scaffold stops its content above the navigation bar and no pushed screen adds the bar to its own end padding`() {
        val scaffold = code(text("presentation/components/DapBackScaffold.kt"))
        assertThat(scaffold).contains(".windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))")
        val screens = listOf(
            "AlbumDetailScreen", "ArtistDetailScreen", "PlaylistDetailScreen", "GenreDetailScreen", "StatsScreen",
            "AutoCollectionDetailScreen", "SettingsScreen", "SettingsCategoryScreen", "EqualizerScreen", "DacProfileScreens",
            "HeadphoneProfileScreens", "DuplicateSongsScreen",
        )
        for (name in screens) {
            val screen = code(text("presentation/screens/$name.kt"))
            assertThat(screen).contains("DapBackScaffold(")
            // the bar's height added to a bottom padding would count it twice (once in the viewport, once in the padding)
            assertThat(screen).doesNotContain("WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +")
            assertThat(screen).doesNotContain("aboveNavigationBar")
        }
        // the Artist page's list and its scrollbar end where they did: at the mini player plus a little air
        val artist = code(text("presentation/screens/ArtistDetailScreen.kt"))
        assertThat(artist).doesNotContain("MiniPlayerHeight + systemNavBarInset")
    }

    // ── L10: a plain skip changes no item ────────────────────────────────────────────────────────

    @Test
    fun `L10 a plain skip, from the buttons, the session and the lock screen, calls nothing that adds, replaces or sets items`() {
        // The 2-4 `Timeline changed reason=1 items=30` lines after a skip are ExoPlayer's own source updates (reason 1 is
        // TIMELINE_CHANGE_REASON_SOURCE_UPDATE: a source reports its duration); an item replaced by the app would be reason 0. This pins
        // that nothing on the skip path is the app replacing items, so there is nothing of ours to make into "one publication or none".
        val mutators = listOf("replaceMediaItem", "addMediaItem", "setMediaItem", "removeMediaItem", "moveMediaItem", "setShuffleOrder")
        fun body(source: String, signature: String): String {
            val start = source.indexOf(signature)
            assertThat(start).isAtLeast(0)
            val indent = source.substring(source.lastIndexOf('\n', start) + 1, start).takeWhile { it == ' ' }
            return source.substring(start, source.indexOf("\n$indent}\n", start))
        }
        val holder = code(text("presentation/viewmodel/PlaybackStateHolder.kt"))
        val skips = listOf(
            body(holder, "    fun previousSong()"),
            body(holder, "    fun nextSong()"),
            code(text("presentation/components/player/PlayerSkipCoordinator.kt")),
            code(text("presentation/lockscreen/DapLockSurface.kt")),
        )
        val mapping = code(text("data/service/player/MappingPlayer.kt"))
        val session = listOf("override fun seekToNext()", "override fun seekToNextMediaItem()", "override fun seekToPreviousMediaItem()", "override fun seekToPrevious()")
            .map { body(mapping, "    $it") }
        for (source in skips + session) for (call in mutators) assertThat(source).doesNotContain("$call(")
    }

    // ── L11: the cover's accent, at a controlled cost ────────────────────────────────────────────

    @Test
    fun `L11 the accent is a dynamic local, so a new one costs the widgets that wear it and not the whole player`() {
        val theme = code(text("ui/ds/DapTheme.kt"))
        assertThat(theme).contains("val LocalDapAccent = compositionLocalOf {")
        assertThat(theme).doesNotContain("val LocalDapAccent = staticCompositionLocalOf")
    }

    @Test
    fun `L11 one extraction per cover, newest request wins, and nothing in the player or lock UI asks for a palette itself`() {
        val processor = code(text("presentation/viewmodel/ColorSchemeProcessor.kt"))
        assertThat(processor).contains("extractionFlight.run(")
        // a forced refresh is new work, never joined to a running one
        assertThat(processor).contains("if (forceRefresh) generate()")
        val holder = code(text("presentation/viewmodel/ThemeStateHolder.kt"))
        // (B29-fix1 F4: the check is the cover and the request generation, and the refresh publishers ask the same question;
        //  B30-fix1 H4 adds the settings and the cover's regeneration to it, in the same one place)
        assertThat(holder).contains("latestRequestedUri == request.uri && requestGeneration == request.generation")
        assertThat(holder).contains("isCurrentRequest(request)")
        // the surfaces read the palette the holder already published: none of them extracts
        val surfaces = listOf(
            "presentation/lockscreen/DapLockSurface.kt", "presentation/lockscreen/DapLockScreen.kt", "presentation/lockscreen/DapLockScreenHost.kt",
            "presentation/components/UnifiedPlayerSheetLayers.kt", "presentation/components/player/FullPlayerScreen.kt",
            "presentation/components/scoped/SheetThemeState.kt",
        )
        for (path in surfaces) {
            val source = code(text(path))
            assertThat(source).doesNotContain("getOrGenerateColorScheme")
            assertThat(source).doesNotContain("extractAndGenerateColorScheme")
            assertThat(source).doesNotContain("ColorSchemeProcessor")
        }
    }

    @Test
    fun `L11 the full player's accent is the sheet's settled one and is held while the player is parked, the mini player stays graphite`() {
        val theme = code(text("presentation/components/scoped/SheetThemeState.kt"))
        // read from the raw scheme the sheet holds (the one that settles at once), not from the animated one
        assertThat(theme).contains("AlbumAccent.fromScheme(albumAccentScheme, accentSurface)")
        assertThat(theme).contains("if (isAlbumArtTheme && hasAlbumArt) currentSongActiveScheme ?: lastAlbumSchemeSnapshot else null")
        val layers = code(text("presentation/components/UnifiedPlayerSheetLayers.kt"))
        assertThat(layers).contains("rememberHeldWhile(parked, albumAccent) ?: appAccent")
        assertThat(layers).contains("CompositionLocalProvider(LocalDapAccent provides playerAccent) {")
        // the docked mini player block does not mention it
        val mini = layers.substringAfter("MiniPlayerContentInternal(").substringBefore("if (shouldRenderFullPlayer)")
        assertThat(mini).doesNotContain("albumAccent")
        assertThat(mini).doesNotContain("LocalDapAccent")
    }

    @Test
    fun `L11 the lock surface reads the palette with its lifecycle and provides the accent around the host`() {
        val lock = code(text("presentation/lockscreen/DapLockSurface.kt"))
        assertThat(lock).contains("playerViewModel.playerThemePreference.collectAsStateWithLifecycle()")
        assertThat(lock).contains("playerViewModel.activePlayerColorSchemePair.collectAsStateWithLifecycle()")
        assertThat(lock).contains("CompositionLocalProvider(LocalDapAccent provides (albumAccent ?: LocalDapAccent.current)) {")
        // the setting that already exists is the switch: neutral gives no scheme and so the app's accent
        assertThat(lock).contains("themePreference == ThemePreference.ALBUM_ART && hasCover")
    }
}
