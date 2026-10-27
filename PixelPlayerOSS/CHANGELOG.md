# Changelog

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
