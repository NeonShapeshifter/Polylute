# Privacy

Polylute plays the music stored on your device. It has no accounts, no ads, no analytics and no crash-reporting service. It has no server of its own and never contacts one.

This page describes version 1.0.0.

## What is stored, and where

Everything is kept on the device, in the app's private storage.

- **Library.** A database of the songs, albums, artists and genres found on the device (title, artist, album, genre, year, track number, duration, file and cover-art locations), your playlists, favorites, audio bookmarks, search history and lyrics.
- **Listening history.** A file with one entry per listen: the song, when it started and ended, how long it was heard, how it ended, where it was played from (an album, a playlist, a search and so on, by id and never by title), whether shuffle was on, and the audio format and output used, including the name of the USB DAC.
- **Settings.** App settings, headphone profiles, volume limits and the device-mode state, in the settings store and a few small preference files.
- **Caches.** Cover-art thumbnails, an image cache of up to 100 MB, and waveform data. Clearing the app's cache removes them.
- **Last crash.** If the app crashes, it keeps the error text and shows it at the next start. You decide whether to share it. The app does not send it.
- **DAC EQ copies.** Copies of a connected DAC's EQ presets (gain and filters), kept so the DAC's own settings can be put back.
- **Backups you make.** Settings can write a backup to a file you choose with Android's file picker. It can hold settings, playlists, favorites, lyrics, listening history, equalizer settings and artist images. You can protect it with a passphrase (AES-256-GCM). The app never sends it anywhere.

The app allows Android's own backup. If that is on for your phone, Android may copy the app's data to your Google account, except the files the app excludes: the settings store, the crash text and the device-mode state.

## Network

Polylute makes no network request unless you turn on one of the first two features below or press one of the lookup buttons. The two features are off by default.

| Feature | When | What is sent | To whom |
| --- | --- | --- | --- |
| Online lyrics ("Online lyrics from LRCLIB") | Only when you turn it on, and only for a song with no lyrics stored, embedded or in an .lrc file | The song's title and artist. The exact lookup also sends the album and the duration in seconds. | LRCLIB, https://lrclib.net |
| Artist pictures ("Artist artwork from Deezer") | Only when you turn it on | The artist's name, as a search. The app then downloads the picture Deezer names, from Deezer's image servers. | Deezer, https://api.deezer.com and its image servers |
| MusicBrainz lookup ("Find on MusicBrainz") | Only when you press it on a song | A search text built from the song's title, artist and, if known, album. Nothing else. | MusicBrainz, https://musicbrainz.org |
| "Find lyrics" link in the song editor | Only when you tap it | Opens the lrclib.net search page in your browser, with the song's title and artist in the address. Your browser makes that request, not Polylute. | LRCLIB, https://lrclib.net |

- Requests use HTTPS only. Android's cleartext traffic is off for the app. The app trusts Android's system certificates and the certificates you installed yourself.
- The server sees your IP address. The app names itself in the User-Agent header as `Polylute/<version> (Android; Music Player)`. The MusicBrainz request instead sends `Polylute/<version> ( https://github.com/NeonShapeshifter/Polylute )`, because MusicBrainz asks clients to be reachable.
- The app sends no library list, no listening history, no settings and no identifier of you or your device.
- If you turn the Deezer switch off, the app stops searching. Picture addresses already stored stay in the library, and artist lists still show those pictures. A picture that is no longer in the image cache is downloaded again from Deezer's image servers.
- Music is played from files on the device. There is no feature that streams music from a server.

## Device Owner role (Dedicated mode)

Device Owner is optional. It exists only if you set it up yourself with adb. Without it, the Launcher and Pinned levels use ordinary Android features: choosing a home app, and Android's app pinning.

As Device Owner, Polylute does these things and no others:

- It locks the phone to itself with Android's Lock Task: it sets the Lock Task allowlist to its own package and the Lock Task features.
- It sets itself as the persistent home app.
- It turns the system lock screen off, and uses its own lock screen, while Dedicated mode and the app's lock-screen setting are on. It does this only if the phone has no PIN, pattern or password. With a real credential, Android's lock screen stays.
- It turns Wi-Fi and Bluetooth on or off when you ask on its Device settings page.

It does not wipe the phone, restrict users, hide or suspend other apps, read other apps' data, or log anything about the phone. It asks for no legacy device-admin policies. It has no server to be managed from.

You can give the role up at any time: Settings, Home and DAP mode, Stop being the device owner. No reset is needed.

## Permissions

The permissions are in `app/src/main/AndroidManifest.xml`. The Internet permission is used only for the requests listed above.
