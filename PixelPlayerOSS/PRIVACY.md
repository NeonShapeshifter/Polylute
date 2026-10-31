# Privacy

Polylute plays the music stored on your device. It has no accounts, no ads, no analytics and no crash-reporting service. It has no server of its own and never contacts one.

This page describes version 1.1.0.

## What is stored, and where

Everything is kept on the device, in the app's private storage.

- **Library.** A database of the songs, albums, artists and genres found on the device (title, artist, album, genre, year, track number, duration, file and cover-art locations), your playlists, favorites, audio bookmarks, search history and lyrics.
- **Listening history.** A file with one entry per listen: the song, when it started and ended, how long it was heard, how it ended, where it was played from (an album, a playlist, a search and so on, by id and never by title), whether shuffle was on, and the audio format and output used, including the name of the USB DAC.
- **Settings.** App settings, headphone profiles, volume limits and the device-mode state, in the settings store and a few small preference files.
- **Music info availability.** Find remembers the results on the phone, including source addresses, credits and the lyrics text LRCLIB returns. Pictures and text become saved music info or lyrics only after you select them and choose Download selected. One queue remembers its progress and whether you paused it across restarts.
- **Caches.** Cover-art thumbnails, an image cache of up to 100 MB, and waveform data. Clearing the app's cache removes them.
- **Downloaded music info.** If you turn on any of the three "Music info from the internet" switches (below), the pictures and descriptions they download are kept in the app's private storage (`files/music-info/`) until you choose to delete them, without automatic eviction or an app-defined total storage cap, with who made each picture and under what licence. Settings, Library, "Delete downloaded data" removes all of it, and the artist pictures that pointed at it. Offline, the app shows what is kept and asks for nothing.
- **New files in your music folders.** When you update the library, and about every six hours while the app syncs in the background, the app walks the music folders you included and asks Android's media scanner to index the audio files it has not indexed yet (`MediaScannerConnection`). It reads folder and file names on the phone and nothing else, and sends them nowhere. It remembers, in a small preference file (`library_file_walk`), when it last walked and which files Android declined to index (by path, size and modification time), so it does not ask again about the same file.
- **Last crash.** If the app crashes, it keeps the error text and shows it at the next start. You decide whether to share it. The app does not send it.
- **Route trace.** A text file (`diagnostics/route-trace.txt`, in the app's own folder under Android/data) with the last 1,500 lines of what the audio route did: a DAC arriving or leaving, which output was used and why, with a timestamp. The previous session's lines are kept ahead of the current one's. It is not shown in the app and the app does not send it.
- **Noise marks.** If you press "Mark noise now" (in the song's More sheet, or in Maintenance), the app writes one text file in its private storage (`files/noise-marks/`; the newest 20 are kept). It holds the last seconds of the USB output's counters, the audio route and format, the name of the DAC, the phone model and Android version, and the title of the song that was playing. It stays on the phone unless you share it with the button the app offers after the mark. The app does not send it.
- **Detailed log.** Only if you turn on "Detailed logging" in Maintenance. It is off by default and switches itself off after 30 minutes. While it is on, the app writes a rotating text file in its private storage (`files/detailed-logs/`, two files of at most 512 KB; the older one is dropped when the newer is full). The file holds: the app's own lines about the DAC and its HID conversation, the USB audio route and format, the volume ceiling and the DAC's level, Dedicated mode and the lock screen, the sleep timer and the library scan's counters (their tags are listed in `DetailedLogPolicy`); USB attach, detach, permission, route and power events; the route trace; every ten seconds the USB output's one-second summaries and every minute the stream's state (the same facts a noise mark holds, without the song's title or any device's name); and the phone model, the Android version and the app version. It does **not** hold song titles, artists, albums, playlists, file names or paths, web addresses, e-mail or account names, serial numbers or the names of Bluetooth devices: lines from the rest of the app are not written (a warning from one is noted by its tag with its text withheld), and every line is scrubbed of those before it is written. A second switch, "Include song titles and file paths", off every time logging starts, lifts that: then every line of the app is written and paths and titles stay in it. "Share log" sends the file through Android's share sheet only when you press it; "Delete log" removes it. The app never sends it, and Android's backup excludes it.
- **DAC EQ copies.** Copies of a connected DAC's EQ presets (gain and filters), kept so the DAC's own settings can be put back.
- **Backups you make.** Settings can write a backup to a file you choose with Android's file picker. It can hold settings, playlists, favorites, lyrics, listening history, equalizer settings and artist images. You can protect it with a passphrase (AES-256-GCM). The app never sends it anywhere.

The app allows Android's own backup. If that is on for your phone, Android may copy the app's data to your Google account, except the files the app excludes: the settings store, the crash text, the device-mode state and the detailed log.

## Network

Polylute makes no network request unless you turn on one of the features marked "Only when you turn it on" below or press one of the lookup buttons. Every one of those switches is off by default.

| Feature | When | What is sent | To whom |
| --- | --- | --- | --- |
| Online lyrics ("Online lyrics from LRCLIB") | When you turn it on, or explicitly select Lyrics in an offline download; existing/local lyrics are kept | The song's title and artist. The exact lookup also sends the album and the duration in seconds. | LRCLIB, https://lrclib.net |
| Missing album covers ("Music info from the internet", Settings, Library) | When you turn it on, or explicitly select missing covers in an offline download; existing covers are kept | The album's title and its artist's name, as a search; then the identifier MusicBrainz answered with, to fetch that release's front cover. | MusicBrainz, https://musicbrainz.org; the Cover Art Archive, https://coverartarchive.org (it redirects to archive.org, which serves the picture) |
| Artist photos (same section) | When you turn it on, or explicitly select this content in an offline download | The artist's name, as a search at MusicBrainz; then an image relationship supplied by MusicBrainz, or Wikidata and Wikimedia Commons when that relationship supplies no usable picture. The linked image host receives the download request. | MusicBrainz and image hosts it links to; Wikidata, https://www.wikidata.org; Wikimedia Commons, https://commons.wikimedia.org and its image servers (upload.wikimedia.org) |
| Artist and album descriptions (same section) | When you turn it on, or explicitly select this content in an offline download | The artist's name, or the album's title and artist, as a search at MusicBrainz; then the identifiers each answer gave, to ask Wikidata for the Wikipedia page names and Wikipedia for the summary, in the app's language and then in English. | MusicBrainz; Wikidata; Wikipedia, https://<language>.wikipedia.org |
| MusicBrainz lookup ("Find on MusicBrainz") | Only when you press it on a song | A search text built from the song's title, artist and, if known, album. Nothing else. | MusicBrainz, https://musicbrainz.org |
| "Find lyrics" link in the song editor | Only when you tap it | Opens the lrclib.net search page in your browser, with the song's title and artist in the address. Your browser makes that request, not Polylute. | LRCLIB, https://lrclib.net |

- Requests use HTTPS only. Android's cleartext traffic is off for the app. The app trusts Android's system certificates and the certificates you installed yourself.
- The server sees your IP address. The app names itself in the User-Agent header as `Polylute/<version> (Android; Music Player)`. The MusicBrainz request instead sends `Polylute/<version> ( https://github.com/NeonShapeshifter/Polylute )`, because MusicBrainz asks clients to be reachable.
- The app sends no library list, no listening history, no settings and no identifier of you or your device.
- The music-info requests are made one at a time, at most one a second, as MusicBrainz asks; all of them carry the project-address User-Agent above, because MusicBrainz and Wikimedia ask clients to be reachable. A switch that is off sends nothing automatically for that kind. An explicit offline-download selection permits a one-shot download without changing that switch. What was downloaded stays on the phone, until you delete it; there is no app-defined total storage cap.
- Finding music info and downloading selected items require unmetered Wi-Fi. LRCLIB calls share a 500 ms minimum interval (at most two requests per second), and rate-limit responses postpone subsequent requests. MusicBrainz requests are at least 1.1 seconds apart.
- Offline preparation uses one target per song for lyrics, per artist for photos, and per artist/album for descriptions. Two content queues may run concurrently, sharing MusicBrainz artist lookups; the music-info request limit above still applies. Available results include content already stored on the phone.
- Pictures and descriptions keep their origin and available credit/licence metadata. Information opens a local credits dialog; artist and album pages do not launch Wikipedia or another browser. MusicBrainz annotations and image relationships are checked first; Wikidata/Wikipedia/Commons remain fallbacks.
- Deezer is no longer used. Version 1.0.0's switch "Artist artwork from Deezer" is gone, its client and its requests are removed from the app, and the picture addresses it had stored in the library are cleared the first time this version starts, so no picture is loaded from Deezer's servers. The new switches start off whatever the old one was.
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

Polylute supports Android 10 (API 29) or newer. On Android 10 it uses legacy shared-storage access to read your included music folders, local lyrics and artwork, and to edit or delete the files you choose. Android 11 and newer use scoped storage.

## Permissions

The permissions are in `app/src/main/AndroidManifest.xml`; this is every one of them and what it is for. Polylute asks for no location and for no exact alarm: version 1.1.0 removed both (the location permission only ever served to read the name of the Wi-Fi network on Android 11 and older, and the sleep timer works with an inexact alarm that Android allows in Doze, plus the app's own timer).

| Permission | What it is for |
| --- | --- |
| `INTERNET` | The requests listed under "Network" above, and nothing else. |
| `ACCESS_NETWORK_STATE` | Whether the phone is online, so the online features do not wait on a connection that is not there, and the Device settings page can say whether Wi-Fi is connected. |
| `ACCESS_WIFI_STATE` | Whether the Wi-Fi radio is on (Device settings page). |
| `CHANGE_WIFI_STATE` | The Wi-Fi switch on the Device settings page, which works only when Polylute is the Device Owner (Dedicated mode). |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and older) | The same for the Bluetooth radio. |
| `BLUETOOTH_CONNECT` (Android 12 and newer) | The Bluetooth switch (Device Owner only) and the name of the connected Bluetooth audio device, to tell which output the sound goes to. |
| `BLUETOOTH_SCAN`, declared "never for location" | To hear about Bluetooth audio devices that Android's own Bluetooth screen finds while the Device settings page is open. Polylute never starts a scan. |
| `READ_MEDIA_AUDIO` (Android 13 and newer), `READ_EXTERNAL_STORAGE` (Android 12 and older) | Reading the music in the folders you include. |
| `WRITE_EXTERNAL_STORAGE` (Android 10 only) | Editing tags, saving local lyrics and deleting the music files you choose, after Android grants storage access. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` | The playback service and its notification, so music goes on with the screen off. |
| `WAKE_LOCK` | Keeping the processor awake while a song plays. |
| `WRITE_SETTINGS` | Dedicated mode's screen-off time on the Device settings page. It is Android's special access: Android's own screen asks you, and Polylute asks for it nowhere else. |
| `RECEIVE_BOOT_COMPLETED` | Putting Dedicated mode's policy back after the phone restarts (Device Owner only). |
