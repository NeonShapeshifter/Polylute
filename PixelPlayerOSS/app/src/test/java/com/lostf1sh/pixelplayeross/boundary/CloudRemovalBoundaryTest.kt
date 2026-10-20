package com.lostf1sh.pixelplayeross.boundary

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_5_6
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.model.SettingsVisibility
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.settings.SettingsSurfacePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The network boundary the DAP is defined by:
 *
 * > Music is local. The internet may enrich local music. The internet must not be required to
 * > own, index, play, organize or resume music.
 *
 * Every assertion here is negative on purpose. Removing an architecture is not finished when the
 * screens stop compiling — it is finished when nothing can reach the removed thing again: no
 * class on the classpath, no route, no setting, no resource, no worker, no DI binding, no query
 * against a dropped table, and no credential left in a place a backup can lift it out of.
 *
 * These are pure-JVM tests. They read the classpath, the generated `R` classes and a handful of
 * source and XML files rather than starting Android, so they cost nothing and cannot rot into
 * "the emulator was busy". Where a claim can be checked by running real code — the v5 -> v6
 * migration, the lyrics source resolution, the product-mode resolver — it is run, not inspected.
 */
class CloudRemovalBoundaryTest {

    // --- 1. The removed classes are gone from the classpath ----------------------------------

    @Test
    fun `no class of the removed architecture is still on the classpath`() {
        // Covers the whole vertical slice at once: the server clients and their models, the
        // scrobbler, the cloud-download cache, the stream proxies, every worker the removal
        // decommissioned, the Hilt module that bound them, and the screens and view models that
        // drove them. A single survivor means the slice was cut short.
        val stillPresent = REMOVED_CLASSES.filter { fqcn ->
            runCatching { Class.forName(fqcn) }.isSuccess
        }

        assertThat(stillPresent).isEmpty()
    }

    // --- 2. Nothing in the navigation graph reaches a server or an account --------------------

    @Test
    fun `no screen route addresses a server, a cloud or an account`() {
        val declared = Screen::class.java.declaredClasses
            .filter { Screen::class.java.isAssignableFrom(it) }
        val routes = declared.mapNotNull { nested -> screenInstance(nested)?.route }

        // Guard the guard: a reflection change that silently found no singletons would make the
        // assertion below vacuous rather than failing.
        assertThat(routes).hasSize(declared.size)

        assertThat(routes.filter { REMOVED_ARCHITECTURE.containsMatchIn(it) }).isEmpty()
    }

    // --- 3 and 4. The Settings root and search both stopped offering the removed surface ------

    @Test
    fun `the Settings root has no account, server or cloud category`() {
        val offenders = SettingsCategory.entries.filter { REMOVED_ARCHITECTURE.containsMatchIn(it.id) }

        assertThat(offenders.map { it.id }).isEmpty()
        // Removing the accounts surface left no orphaned ownership class behind either.
        assertThat(SettingsVisibility.entries.map { it.name })
            .containsExactly("PUBLIC", "MAINTENANCE")
        assertThat(SettingsSurfacePolicy.visibleCategories())
            .containsAtLeastElementsIn(SettingsCategory.entries.filter { it.visibility == SettingsVisibility.PUBLIC })
    }

    @Test
    fun `no screen route carries a settings-search parameter`() {
        // Settings Search was a second way into the surface: it handed the user a route of its own,
        // built by appending `highlightKey` to a destination. It has been removed, so no route may
        // carry that parameter and no destination may be addressed by a settings query again.
        val declared = Screen::class.java.declaredClasses
            .filter { Screen::class.java.isAssignableFrom(it) }
        val routes = declared.mapNotNull { nested -> screenInstance(nested)?.route }

        assertThat(routes).hasSize(declared.size)
        // Not a bare "search" match: `search` is the music Search root and stays.
        assertThat(routes.filter { route ->
            route.contains("highlightKey") || route.contains("settings_search")
        }).isEmpty()
    }

    // --- 5. No resource belongs to the removed architecture -----------------------------------

    @Test
    fun `no string, drawable, id or layout resource belongs to the removed architecture`() {
        // The removal deleted several hundred resource elements by keyword. This is the check
        // that it took exactly the cloud ones: any survivor would be an orphan at best, and a
        // live reference to a deleted resource is a build break at worst.
        val offenders = listOf(
            R.string::class.java,
            R.array::class.java,
            R.drawable::class.java,
            R.id::class.java,
            R.layout::class.java,
        ).flatMap { holder ->
            holder.declaredFields.map { "${holder.simpleName}/${it.name}" }
        }.filter { REMOVED_ARCHITECTURE.containsMatchIn(it) }

        assertThat(offenders).isEmpty()
    }

    // --- 6. No declared cloud component, and no cleartext host to reach one through ----------

    @Test
    fun `the manifest declares no cloud component and no cleartext exemption`() {
        val manifest = Project.text("src/main/AndroidManifest.xml")

        // The server login activities are gone as declarations, not just as classes...
        assertThat(manifest).doesNotContain("NavidromeLoginActivity")
        assertThat(manifest).doesNotContain("JellyfinLoginActivity")
        // ...and the removal did not take the transport hardening with it. INTERNET stays: the
        // optional enrichment (lyrics, MusicBrainz, artist artwork) is still over the network.
        assertThat(manifest).contains("android:networkSecurityConfig=\"@xml/network_security_config\"")
    }

    @Test
    fun `no cleartext host exemption survives in the network security config`() {
        // Comments are stripped first: the file's own documentation has to be free to name the
        // attribute it is explaining without the assertion tripping over its own explanation.
        val config = Project.text("src/main/res/xml/network_security_config.xml")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        assertThat(config).contains("cleartextTrafficPermitted=\"false\"")
        // The loopback / 127.0.0.1 exemption existed only for the removed stream proxies. Every
        // endpoint left is optional HTTPS enrichment, so no domain may be exempted any more.
        assertThat(config).doesNotContain("domain-config")
        assertThat(config).doesNotContain("localhost")
        assertThat(config).doesNotContain("127.0.0.1")
        assertThat(config).doesNotContain("cleartextTrafficPermitted=\"true\"")
    }

    @Test
    fun `the strict network security config is the only one in the build`() {
        // The debug source set used to override this resource with a permissive base-config whose
        // only reason to exist was reaching a self-hosted server over plain HTTP. With that
        // architecture gone nothing in the app speaks cleartext - not even in a debug build - so
        // the override went with it and `src/main`'s config now governs every variant.
        val overrides = Project.dir("src/debug/res/xml")
            .listFiles()
            .orEmpty()
            .filter { it.name == "network_security_config.xml" }

        assertThat(overrides.map { it.name }).isEmpty()
    }

    // --- 7. No surviving background work, and no worker that could retry forever -------------

    @Test
    fun `the only scheduled worker left is the local library one`() {
        // "Removing cloud architecture but leaving a crashing or retrying worker is failure."
        // The precise form of that failure is a second @HiltWorker that WorkManager can build and
        // that nothing left in the app knows how to satisfy.
        val workers = Project.mainSources().filter { "@HiltWorker" in it.readText() }

        assertThat(workers.map { it.name }).containsExactly("SyncWorker.kt")
    }

    @Test
    fun `no file on the library or startup path names a network client`() {
        // The rule, stated as an assertion: owning, indexing and organizing music never touches
        // the network. These are the files that do that work, plus the entry points.
        val offenders = LOCAL_PIPELINE.filter { NETWORK_MARKER.containsMatchIn(Project.text(it)) }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `optional enrichment is not wired into startup or into the library pipeline`() {
        // MusicBrainz is a sanctioned enrichment: user-triggered, local-track-centric, optional.
        // It stays that way only while nothing on the startup or indexing path can reach it.
        //
        // MusicDao is outside this list on purpose. It carries the write half - the statement
        // that persists a match the user accepted - and persisting a result into the local
        // database is not the same as running a lookup. The trigger is the ViewModel behind
        // Track Details, and the assertion below is that nothing else is one.
        val offenders = STARTUP_AND_INDEXING.filter { "musicbrainz" in Project.text(it).lowercase() }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `no copy from a deleted server screen is left behind unreferenced`() {
        // The purge that removed the server architecture matched on the services' names. A
        // screen's own copy does not usually name a service - "Username", "Connect", "Last
        // synced", "Sync", "Connection details" give nothing away - so a name-based sweep walks
        // straight past it and leaves it translated into twelve locales forever.
        //
        // What identifies those leftovers is the prefix they were declared under together with
        // the fact that nothing references them any more. A live string under the same prefix
        // passes, so this fails only on dead copy.
        val referenced = Project.referenceHaystack()
        val stale = DECLARED_RESOURCE_NAMES
            .filter { name -> DELETED_SCREEN_PREFIXES.any { name.startsWith(it) } }
            .filterNot { it in referenced }

        assertThat(stale).isEmpty()
    }

    // --- 8. The database keeps the local library and drops only what pointed at a server ------

    @Test
    fun `no DAO or entity still addresses a dropped table`() {
        // A query against a table the v5 -> v6 migration drops would not fail at build time; it
        // would fail the first time a user touched that screen.
        val databaseDir = Project.dir("src/main/java/com/lostf1sh/pixelplayeross/data/database")
        val offenders = databaseDir.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Migrations.kt" }
            .filter { file -> DROPPED_TABLES.any { it in file.readText() } }

        assertThat(offenders.map { it.name }.toList()).isEmpty()
    }

    @Test
    fun `the migration deletes only rows that belonged to a server`() {
        val executed = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(any<String>()) } answers { executed += firstArg<String>() }

        MIGRATION_5_6.migrate(db)

        assertThat(executed).isNotEmpty()

        // Every dropped table is dropped explicitly. SQLite drops a table's indexes with it.
        DROPPED_TABLES.forEach { table ->
            assertThat(executed).contains("DROP TABLE IF EXISTS `$table`")
        }

        // The destructive half of this migration runs against the user's music database, so the
        // assertions on it are the point of the test. Every statement that removes user rows is
        // scoped to rows of server origin, and none of them can touch a local row.
        val destructive = executed.filter { it.startsWith("DELETE", ignoreCase = true) }
        assertThat(destructive).isNotEmpty()
        destructive.forEach { sql ->
            assertThat(sql).contains(" WHERE ")
        }

        // A local row is never written with a non-zero source_type and never carries a server
        // content URI, so this predicate selects strictly less than "all server rows" and
        // strictly more than "nothing". Both halves are load-bearing: dropping `source_type <> 0`
        // would miss server rows, and inverting it to `source_type = 0` would delete the user's
        // entire library.
        //
        // The trailing space keeps `DELETE FROM songs` from also matching `songs_fts`, which the
        // migration prunes by a different and equally deliberate predicate.
        val songDeletes = executed.filter { it.startsWith("DELETE FROM songs ") }
        assertThat(songDeletes).hasSize(1)
        assertThat(songDeletes.single()).contains("source_type <> 0")
        assertThat(songDeletes.single()).contains("navidrome://%")
        assertThat(songDeletes.single()).contains("jellyfin://%")

        assertThat(executed.filter { "source_type = 0" in it }).isEmpty()

        // The tables that point at a song declare no foreign key, so they have to be cleared by
        // the same predicate rather than left dangling.
        executed.filter { it.contains("DELETE FROM favorites") || it.contains("DELETE FROM lyrics") }
            .forEach { assertThat(it).contains("source_type <> 0") }
    }

    // --- 9. The credential stores cannot leave the device in a backup -------------------------

    @Test
    fun `every legacy credential store is excluded from both backup paths`() {
        // Cross-checked against the cleanup that deletes them, so the two can never drift: adding
        // a store to the purge without excluding it here fails this test.
        val declared = Regex("\"([a-z_]+_prefs(?:_plain)?\\.xml)\"")
            .findAll(Project.text("src/main/java/com/lostf1sh/pixelplayeross/data/startup/LegacyCloudDataCleanup.kt"))
            .map { it.groupValues[1] }
            .toSet()

        assertThat(declared).isNotEmpty()

        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { rulesFile ->
            val rules = Project.text("src/main/res/xml/$rulesFile")
            declared.forEach { name ->
                assertThat(rules).contains("<exclude domain=\"sharedpref\" path=\"$name\"/>")
            }
            assertThat(rules).contains("<exclude domain=\"file\" path=\"cloud_downloads/\"/>")
        }
    }

    // --- 10. What the removal was required to leave alone is still there ----------------------

    @Test
    fun `every surface the removal had to preserve is still declared`() {
        val strings = R.string::class.java.declaredFields.map { it.name }
        val arrays = R.array::class.java.declaredFields.map { it.name }
        val resources = strings + arrays

        val missing = PRESERVED_RESOURCE_PATTERNS.mapValues { (_, pattern) ->
            resources.count { pattern in it.lowercase() }
        }.filterValues { it == 0 }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `the containment, DAC and headphone routes survived the removal`() {
        val routes = screenRoutes().toSet()

        listOf(
            Screen.Settings,
            Screen.Library,
            Screen.Maintenance,
            Screen.DeviceCapabilities,
            Screen.DacProfile,
            Screen.KnownDacs,
            Screen.HeadphoneProfile,
            Screen.KnownHeadphones,
            Screen.SafetyMaxVolume,
            Screen.Equalizer,
            Screen.About,
            Screen.PlaylistDetail,
            Screen.AudioBookmarks,
        ).forEach { screen ->
            assertThat(routes).contains(screen.route)
        }
    }

    // --- 11. The two product modes still need no account --------------------------------------

    @Test
    fun `App Mode and Dedicated Mode resolve without any account state`() {
        // ProductModeResolver is a pure function of the requested mode and the device's
        // provisioning evidence. There is no third input, and in particular no signed-in user:
        // if the removal had been bolted onto an account check, this signature would have grown.
        assertThat(ProductModeRequest.entries.map { it.name }).containsExactly("APP", "PINNED", "DEDICATED")

        assertThat(ProductModeResolver.resolve(
            requestedMode = ProductModeRequest.DEDICATED,
            evidence = DedicatedProvisioningEvidence(
                isDeviceOwner = false,
                isPixelPlayerHome = false,
                isDedicatedModeEnabled = false,
                isLockTaskPermitted = false,
            ),
        ).effectiveMode).isEqualTo(EffectiveProductMode.DEDICATED_SETUP_REQUIRED)

        assertThat(ProductModeResolver.resolve(
            requestedMode = ProductModeRequest.APP,
            evidence = DedicatedProvisioningEvidence(
                isDeviceOwner = false,
                isPixelPlayerHome = false,
                isDedicatedModeEnabled = false,
                isLockTaskPermitted = false,
            ),
        ).effectiveMode).isEqualTo(EffectiveProductMode.APP)
    }

    // --- 12. Lyrics are still local-first ------------------------------------------------------

    @Test
    fun `embedded lyrics still outrank the network when the preference is absent or unreadable`() {
        // The Phase 8.6 order is embedded -> local .lrc -> stored/imported -> online. The online
        // source is optional enrichment and must never become the fallback, so anything that is
        // not an explicit, recognised user choice resolves to EMBEDDED_FIRST.
        listOf(null, "", "NOT_A_SOURCE", "navidrome").forEach { stored ->
            assertThat(LyricsSourcePreference.fromName(stored))
                .isEqualTo(LyricsSourcePreference.EMBEDDED_FIRST)
        }
        listOf(-1, 99, Int.MAX_VALUE, Int.MIN_VALUE).forEach { ordinal ->
            assertThat(LyricsSourcePreference.fromOrdinal(ordinal))
                .isEqualTo(LyricsSourcePreference.EMBEDDED_FIRST)
        }

        // Online-first still exists as a choice a user can make. It is simply never the default,
        // and the enum's declaration order must not be relied on as the priority order.
        assertThat(LyricsSourcePreference.API_FIRST).isNotEqualTo(LyricsSourcePreference.EMBEDDED_FIRST)
    }

    // --- helpers ------------------------------------------------------------------------------

    private companion object {

        /**
         * Matches the vocabulary of the removed architecture. Deliberately narrow: `server`,
         * `sync` and `online` are all still legitimate — they name the lyrics enrichment and the
         * local MediaStore scan — so they are not in here.
         */
        val REMOVED_ARCHITECTURE =
            Regex("navidrome|jellyfin|subsonic|listenbrainz|scrobbl|cloud|account", RegexOption.IGNORE_CASE)

        /** The tables `MIGRATION_5_6` drops. */
        val DROPPED_TABLES = listOf(
            "navidrome_songs",
            "navidrome_playlists",
            "jellyfin_songs",
            "jellyfin_playlists",
            "listenbrainz_pending_listens",
            "offline_tracks",
        )

        val REMOVED_CLASSES = listOf(
            // server clients and their transports
            "com.lostf1sh.pixelplayeross.data.navidrome.NavidromeRepository",
            "com.lostf1sh.pixelplayeross.data.navidrome.NavidromeStreamProxy",
            "com.lostf1sh.pixelplayeross.data.network.navidrome.NavidromeApiService",
            "com.lostf1sh.pixelplayeross.data.network.navidrome.NavidromeResponseParser",
            "com.lostf1sh.pixelplayeross.data.navidrome.model.NavidromeCredentials",
            "com.lostf1sh.pixelplayeross.data.navidrome.model.NavidromeSong",
            "com.lostf1sh.pixelplayeross.data.jellyfin.JellyfinRepository",
            "com.lostf1sh.pixelplayeross.data.jellyfin.JellyfinStreamProxy",
            "com.lostf1sh.pixelplayeross.data.network.jellyfin.JellyfinApiService",
            "com.lostf1sh.pixelplayeross.data.network.jellyfin.JellyfinResponseParser",
            "com.lostf1sh.pixelplayeross.data.jellyfin.model.JellyfinCredentials",
            "com.lostf1sh.pixelplayeross.data.jellyfin.model.JellyfinSong",
            // scrobbling
            "com.lostf1sh.pixelplayeross.data.listenbrainz.ListenBrainzRepository",
            "com.lostf1sh.pixelplayeross.data.listenbrainz.ScrobbleManager",
            "com.lostf1sh.pixelplayeross.data.listenbrainz.ListenBrainzApiService",
            "com.lostf1sh.pixelplayeross.data.listenbrainz.ListenBrainzEndpoint",
            "com.lostf1sh.pixelplayeross.data.preferences.ListenBrainzPreferencesRepository",
            // cloud streaming and the downloaded-copy cache
            "com.lostf1sh.pixelplayeross.data.stream.CloudStreamProxy",
            "com.lostf1sh.pixelplayeross.data.stream.CloudStreamSecurity",
            "com.lostf1sh.pixelplayeross.data.stream.CloudMusicUtils",
            "com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository",
            "com.lostf1sh.pixelplayeross.data.image.NavidromeCoilFetcher",
            "com.lostf1sh.pixelplayeross.data.image.JellyfinCoilFetcher",
            // the persistence that backed all of it
            "com.lostf1sh.pixelplayeross.data.database.NavidromeDao",
            "com.lostf1sh.pixelplayeross.data.database.NavidromeSongEntity",
            "com.lostf1sh.pixelplayeross.data.database.NavidromePlaylistEntity",
            "com.lostf1sh.pixelplayeross.data.database.JellyfinDao",
            "com.lostf1sh.pixelplayeross.data.database.JellyfinSongEntity",
            "com.lostf1sh.pixelplayeross.data.database.JellyfinPlaylistEntity",
            "com.lostf1sh.pixelplayeross.data.database.ListenBrainzDao",
            "com.lostf1sh.pixelplayeross.data.database.ListenBrainzPendingListenEntity",
            "com.lostf1sh.pixelplayeross.data.database.OfflineTrackDao",
            "com.lostf1sh.pixelplayeross.data.database.OfflineTrackEntity",
            // the background work
            "com.lostf1sh.pixelplayeross.data.worker.CloudSyncCoordinator",
            "com.lostf1sh.pixelplayeross.data.worker.CloudTrackDownloadWorker",
            "com.lostf1sh.pixelplayeross.data.worker.NavidromeSyncWorker",
            "com.lostf1sh.pixelplayeross.data.worker.JellyfinSyncWorker",
            "com.lostf1sh.pixelplayeross.data.worker.ScrobbleFlushWorker",
            // dependency injection
            "com.lostf1sh.pixelplayeross.di.NetworkModule",
            // the user-facing surface
            "com.lostf1sh.pixelplayeross.presentation.screens.AccountsScreen",
            "com.lostf1sh.pixelplayeross.presentation.viewmodel.AccountsViewModel",
            "com.lostf1sh.pixelplayeross.presentation.screens.CloudDownloadsScreen",
            "com.lostf1sh.pixelplayeross.presentation.viewmodel.CloudDownloadsViewModel",
            "com.lostf1sh.pixelplayeross.presentation.components.CloudLibraryPickerSheet",
            "com.lostf1sh.pixelplayeross.presentation.navidrome.auth.NavidromeLoginActivity",
            "com.lostf1sh.pixelplayeross.presentation.navidrome.dashboard.NavidromeDashboardScreen",
            "com.lostf1sh.pixelplayeross.presentation.jellyfin.auth.JellyfinLoginActivity",
            "com.lostf1sh.pixelplayeross.presentation.jellyfin.dashboard.JellyfinDashboardScreen",
        )

        /**
         * The process entry points and the indexing path: everything that runs before the user
         * has asked for anything, plus the code that builds the library.
         */
        val STARTUP_AND_INDEXING = listOf(
            "src/main/java/com/lostf1sh/pixelplayeross/PixelPlayerApplication.kt",
            "src/main/java/com/lostf1sh/pixelplayeross/MainActivity.kt",
            "src/main/java/com/lostf1sh/pixelplayeross/data/worker/SyncManager.kt",
            "src/main/java/com/lostf1sh/pixelplayeross/data/worker/SyncWorker.kt",
            "src/main/java/com/lostf1sh/pixelplayeross/data/worker/SyncExecutionPlan.kt",
            "src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt",
        )

        /** Files that own, index or organize the local library, plus the process entry points. */
        val LOCAL_PIPELINE = STARTUP_AND_INDEXING + listOf(
            "src/main/java/com/lostf1sh/pixelplayeross/data/database/MusicDao.kt",
        )

        /** Every resource name the app declares, whichever locale file declares it. */
        val DECLARED_RESOURCE_NAMES =
            R.string::class.java.declaredFields.map { it.name } +
                R.array::class.java.declaredFields.map { it.name }

        /** The prefixes the deleted server screens declared their copy under. */
        val DELETED_SCREEN_PREFIXES = listOf("auth_", "dash_")

        val NETWORK_MARKER = Regex(
            "okhttp|retrofit|HttpURLConnection|java\\.net\\.|ktor|HttpClient|@GET|@POST",
            RegexOption.IGNORE_CASE,
        )

        /**
         * One representative name fragment per feature the phase was required to keep. Counted
         * rather than pinned by name so a rename inside a surviving feature does not fail the
         * test, while deleting the feature does.
         */
        val PRESERVED_RESOURCE_PATTERNS = mapOf(
            "DAC profiles" to "dac",
            "headphone profiles" to "headphone",
            "Safety Max" to "safety_max",
            "equalizer" to "equalizer",
            "Track Details" to "song_info",
            "waveform" to "waveform",
            "local credits" to "credits",
            "lyrics" to "lyric",
            "playlists" to "playlist",
            "favorites" to "favorite",
            "queue" to "queue",
            "backup and restore" to "backup",
            "Maintenance" to "maintenance",
            "About" to "about",
            "release history" to "changelog",
            "ReplayGain" to "replaygain",
        )

        /**
         * The `Screen` singleton objects, read through Java reflection so the test does not need
         * kotlin-reflect on the unit-test classpath. A `sealed class` requires its subclasses to
         * live beside it, so the declared nested classes are the whole navigation graph.
         */
        fun screenInstance(nested: Class<*>): Screen? =
            nested.declaredFields.firstOrNull { it.name == "INSTANCE" }
                ?.let { field -> field.get(null) as? Screen }

        fun screenRoutes(): List<String> =
            Screen::class.java.declaredClasses
                .filter { Screen::class.java.isAssignableFrom(it) }
                .mapNotNull { screenInstance(it)?.route }
    }
}

/**
 * The module's source tree, resolved from wherever Gradle happened to start the test JVM. Walking
 * up for the manifest rather than assuming a working directory keeps this working from an IDE run
 * as well as from the Gradle test task.
 */
private object Project {

    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("Could not locate the app module from ${File("").absolutePath}")

    fun dir(relative: String): File = File(root, relative).also { it.isDirectory }

    fun text(relative: String): String {
        val file = File(root, relative)
        check(file.isFile) { "Missing expected file: $relative" }
        return file.readText().replace("\r\n", "\n")
    }

    fun mainSources(): List<File> =
        File(root, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    /**
     * Every byte of `src/main` except the locale resource files themselves, as one string. A
     * resource name appearing in here has a reference — from Kotlin (`R.string.foo`), from a
     * layout (`@string/foo`), from the manifest, from anywhere.
     *
     * The declaration files are excluded so that a name declared in one locale and referenced
     * nowhere still reads as unreferenced. The one thing this cannot see is a name referenced
     * only from another `res/values*` file; nothing in the app does that, and if it ever did the
     * failure would name the string and a reader would see why.
     */
    fun referenceHaystack(): String =
        File(root, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in REFERENCE_EXTENSIONS }
            .filterNot { it.parentFile?.name?.startsWith("values") == true }
            .joinToString("\n") { it.readText() }

    private val REFERENCE_EXTENSIONS = setOf("kt", "java", "xml")
}
