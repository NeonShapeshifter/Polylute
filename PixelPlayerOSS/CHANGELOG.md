# Changelog

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
