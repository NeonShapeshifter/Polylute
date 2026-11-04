# Changelog

## 1.3.0

- **Animated covers.** The full player can loop a muted `cover.mp4`, `cover.webp` or `cover.gif` from an album folder, with a folder picker for access. Lists, the mini player and the lock screen keep the still cover.
- **Lyrics.** Stamp line timings while listening, preview them and save synced lyrics. Choose lyrics over the cover, on their own page or off, with updated highlighting and player controls.
- **Album editing.** Edit album and track information together, replace the cover, and review suggested changes from MusicBrainz before saving. Metadata editing and rescanning retain library identities.
- **Library.** Search and browse composers, browse normalized genres, and use updated folder controls and scroll labels. Album details show more technical information.
- **Home and appearance.** Recent artists, suggested albums and listening summaries, with revised menus, cover-colour backgrounds and optional background motion.
- **Music info.** Clearer download selection and queue controls, shared-item indicators, and clearing the selection with Undo. Includes the download redesign, MusicBrainz tag matching and lyrics-saving improvements described in 1.2.1 below.
- **DAP and USB.** Updated screen and lock-screen policies, plus earlier detection of frozen DAC feedback after a sample-rate change.

## 1.2.1

- **Music info.** Find, Choose and Download now show their steps, scope and reasons for waiting. The first completed search chooses available new items; choose by type or by artist and album. Pause stops downloads only. Cancel clears the global pending queue with Undo and keeps saved items. Finished downloads have an OK action.
- **Delete with Undo.** Chosen downloads and page-menu deletes can be undone. Settings confirms before deleting all downloaded pictures, text and lyrics, while keeping your own files. About shows Read more, the source and licence, and selectable grouped credits.
- **Exact matches.** Scanning reads MusicBrainz IDs from FLAC/Vorbis, ID3 and MP4 tags; lookups use them when present. Library identities stay unchanged.
- **Folder covers and lyrics.** An unchanged library sync reads no settled folder-cover images. Artwork errors no longer fail the scan. Saving an old read-only lyrics file on Android 11 or later asks for write access before falling back to the app. Downloaded lyrics use the same SD-folder saving path.

## 1.2.0

The 1.2 release is about USB DACs: Purity is steadier, and Polylute now notices when a DAC gets stuck and brings it back by itself.

- **USB DAC stability.** Purity has one playback path: the old second FLAC player is gone. The USB output keeps a deeper buffer, measured in time, and loading a big queue (shuffle all on thousands of songs) no longer starves it. Every start of audio (a new song, a change of sample rate, a resume) begins with a short, exact cushion of silence, so the DAC is never fed an empty first moment. During a short pause or a skip, Polylute keeps the DAC fed with silence for up to two seconds instead of letting it run dry.
- **A DAC that gets stuck comes back on its own.** Some DACs (the Snowsky Melody among them) can lock their clock feedback far off, or freeze it on one value, which you hear as a "shh". Polylute now watches every feedback reading, including the absurd ones, and when the feedback is locked or frozen it restarts the DAC's audio interface: a short fade, a gap, and the song carries on at the same position, at the same volume. If that is not enough it tries a bus reset once, then stops trying. Recovery, sample-rate changes, hand-back and unplug never touch the DAC at the same time.
- **Bit-perfect is unchanged.** All of this changes only how many samples go in each USB packet, never their values. The silence before audio and during pauses is not counted as music, so the position never jumps.
- **"Mark noise now"** (Maintenance) keeps the last 30 seconds of what the DAC reported and what Polylute sent, with every rejected reading and recovery step, even when Android's own log has dropped them. Attach it to a bug report.
- **Music info.** The downloader is one screen: Find → Choose → Download, no default selections, a saved catalog that opens immediately, artist/album search and one resumable queue. Saved items can be selected and deleted there. Existing downloads stay; unfinished 1.1.1 jobs are discarded so you can choose again. Artist lists no longer fetch every missing photo: photos load for the song playing or the artist page you open.
- **Genres.** Pick, change or remove your own image for a genre; it shows in the genre list, the grid and the genre page, and stays in your backup. Without an image, a genre shows its initial again.
- **Shuffle.** Album, artist and genre Shuffle randomize whole blocks while keeping each page's song order. Shared songs play once by default; a Behaviour switch can repeat them in every artist or genre block. Repeat is on the left and Shuffle on the right in the player and the DAP lock screen.
- **Player.** Bookmark and heart sit on the title row; a long title scrolls, and the artist · album line uses the space it has, with a link to the album. An Appearance switch hides the bookmark. Covers span the screen without lyrics and grow to the space left with lyrics. After a skip from the notification, lock screen or media keys, the cover follows the song.
- **Library and lyrics.** Songs and albums appear right after you add your first folders, without restarting. New installations prefer local lyrics and scan `.lrc` files automatically; online lyrics stay off by default.
- **Android 10.** Polylute installs and runs on Android 10, including legacy file access and removable storage. Because of this, Android 11 and later ask once more for music access after updating.
- The package installer shows "Polylute" right after an update.

### Known issues
- On phones with aggressive battery managers (HONOR, Huawei, Xiaomi and similar), Polylute used as a normal app (not DAP mode) can be frozen when the screen turns off, which stops USB playback. Until a fix lands, exclude Polylute from battery optimisation (on HONOR: Battery → App launch → Polylute → manage manually, allow running in background).

## 1.1.1

- **Library stability.** Large or corrupt lyrics no longer break a whole-library read. Invalid text is removed from the database without changing music files or local lyrics files.

- **Music info.** Artist photos resolve Commons file links correctly, with their credits. Broken downloaded images are repaired automatically; working images stay until you choose to refresh them. Missing covers are checked against real artwork, and partial albums match their main artist.
- **Offline preparation.** Failed requests wait and retry instead of becoming permanent misses. Downloads follow listening order, show an estimate, and give the music you open or play priority. An album or artist can be prepared separately without replacing a paused library download. Refresh or delete downloaded information from its menu, and remove what one download added while preserving shared files. Deleted info stays deleted until you refresh it or prepare it again.
- **Player.** The top bar is gone. Lyrics and details open from the Up next strip; tapping its text or swiping up opens the queue. Covers stay square and whole, with a steady size when lyrics under the cover are enabled. Swipe down or use Back to close, with a Close player action for accessibility.
- **Appearance.** Smoother cover-colour gradients and frosted cards throughout the app. The bottom bar is frosted enough that the list behind it no longer shows through. The old player-colour and palette-style choices are removed; the default cover palette is used. The Device page appears only in DAP mode.

## 1.1.0

- **USB DACs.** A song you start right after sharing the DAC no longer waits for the automatic-resume check. In Dedicated mode the USB permission dialog now appears (the phone lifts its lock for the dialog and puts it back, within 30 seconds, whatever you answer). A DAC or dongle whose volume Polylute cannot set is asked about once: does it have its own knob or buttons; with a knob you choose Resonance at a safe starting level (recommended) or Purity; with none it is always Resonance. After a USB bus reset Polylute puts the DAC's last confirmed level back before it gives the DAC back.
- **Themes.** "Theme and accent" keeps Light, Graphite, Black, Gray and Navy with accents checked for contrast (WCAG AA). "From album art" draws a still gradient of two cover colours; "Cover glass" draws the blurred cover with more visible motion in the player and on the DAP lock screen. Both cover modes use light text, let the cover decide the background, and hide the Theme row. Cards and bars let the cover colours show through a stronger veil. Song changes update the backdrop and highlights without rebuilding the whole Library.
- **DAC EQ.** A DAC that has just powered up and answers with its defaults is read twice before it is called "off". For the Melody, writing to the DAC's EQ is available behind an explicit confirmation, one change at a time; the first write is yours to start.
- **Library.** Files you copy into your music folders are found: updating the library, and the background sync about every six hours, walk the folders you included and ask Android to index what it has not indexed yet.
- **Music info from the internet** (Settings, Library; three switches, all off). Missing covers from the Cover Art Archive, artist photos from Wikimedia Commons and descriptions from Wikipedia, found through MusicBrainz and Wikidata, with their credit and licence shown, kept until you delete them, with "Delete downloaded data". Deezer is gone.
- **Lyrics.** Immersive lyrics are back: after a quiet moment, while a song plays, the controls fold away and the text grows; touching the page brings them back, and that first touch does not seek. Scrolling counts as being there; an open options sheet or a paused song keeps the controls.
- **Mini player.** Its progress line can be dragged to seek, in the top of the mini player; a swipe lower down still changes the song.
- **Detailed logging** (Maintenance). Switch it on for 30 minutes to record what the app does into a file on the phone, scrubbed of titles, paths, addresses, accounts and serial numbers unless you include them; share it or delete it.
- **Sound.** On the Android route, files above 192 kHz are reduced with a proper low-pass filter (100 dB of rejection; a 180 kHz tone in a 384 kHz file used to fold into the music only 20 dB down). New, optional and off by default: a peak limiter for Resonance on the Android route, after the equalizer.
- **Dedicated mode.** Picking Polylute as the home app again after leaving puts the policy back every time, and the second copy of the app that Android could leave behind when you opened the icon is gone.
- **Permissions.** Polylute no longer asks for location or exact alarms. The sleep timer uses an alarm Android allows in Doze, plus the app's own timer. Every permission and its reason are in PRIVACY.md.
- The release now checks that nothing native code looks up by name was renamed by R8 (the cause of the 1.0.0 crash on the first FLAC).

- **Release cleanup.** Performance tracing, media-session profiling and temporary audio diagnostic overrides are development-only. A public install ignores benchmark launch extras and clears inherited diagnostic overrides; the OEM lock-screen compatibility option, noise capture and optional detailed logging remain available.

- **Release safeguards.** Lyrics links handle a missing or blocked browser. Offline preparation waits for unmetered Wi-Fi. LRCLIB requests share a one-second gate and respect rate-limit backoff. A confirmed empty playback queue clears the old artwork without clearing it during a pending track transition.
- **Offline preparation.** Download lyrics, artist photos, missing covers and descriptions for selected tracks or the whole library. Progress survives a restart, with pause/continue and results per song, artist or album. Artist photos and album data are deduplicated; two selected content types run in parallel and share artist lookups. Existing lyrics are kept. MusicBrainz is tried before the Wikipedia/Commons fallback. Artist pages open before the online photo or its colours are ready; descriptions expand/collapse on tap and credits stay in a local information dialog.

## 1.0.0

First release of Polylute.

- A music player for the files on your device: songs, albums, artists, genres and folders, playlists, favorites, search, queue, shuffle, repeat, sleep timer, gapless playback and crossfade, and home-screen widgets.
- Purity: bit-perfect output straight to a USB DAC, without Android's mixer. Resonance: the processed path, with per-headphone profiles, an equalizer and volume limits.
- Reads the EQ presets of supported FiiO and Snowsky DACs (the Melody). Writing to the DAC is not in 1.0.0.
- Lyrics from the audio file or an .lrc file. Lyrics from LRCLIB and artist pictures from Deezer are optional and off by default. MusicBrainz lookup on demand.
- Listening history, statistics by period, "Top N" lists and automatic collections.
- DAP mode, to use the phone as a dedicated digital audio player, at three levels: Launcher, Pinned and Dedicated. Dedicated needs Device Owner, set up once with adb, and adds a lock screen of its own.
- Backup and restore of settings, playlists, favorites, lyrics and history, with an optional passphrase.
- English, Spanish and Swedish.
- The application id is `app.polylute`. The debug build is a separate app, `app.polylute.debug`.
