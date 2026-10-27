# Polylute

Polylute is an Android music player for the files on your device.

It can send audio straight to a USB DAC, bit-perfect (Purity), or through its own processing (Resonance: per-headphone profiles, an equalizer and volume limits). It can also turn a phone into a dedicated digital audio player (DAP mode).

## Requirements

- Android 11 (API 30) or newer.
- A USB DAC for Purity and for Resonance over USB.
- To build: JDK 21, Android SDK 37, Android NDK 29.0.14206865 and CMake 3.21 or newer. Set the SDK path in `PixelPlayerOSS/local.properties` (`sdk.dir=...`) or with `ANDROID_HOME`.

## Two ways to use it

**App mode** is a normal music player. Install it and use it. Nothing is locked.

**DAP mode** is for a phone used only as a player. Open Settings, Home and DAP mode. Choose the home app; once Polylute is the home app, choose a level:

- **Home** (called Launcher in the app): Polylute is your home screen. Nothing is locked.
- **Pinned**: Android's app pinning keeps Polylute on screen until you unpin it. No adb needed.
- **Dedicated**: Android's Lock Task with Polylute's own lock screen. It needs Device Owner, set up once with adb.

App mode, Home and Pinned need nothing but the APK. Only Dedicated needs a computer, once, because Android lets an app become the device owner only through adb (or a factory reset with an enterprise setup).

### Is Dedicated for you?

Dedicated is for turning a phone, ideally a spare one, into a DAP: a phone that does one thing. On a phone you use every day, use App mode, or Pinned if you want it locked on screen. They need no computer and nothing to remove.

### Setting up Dedicated

1. Install Polylute and turn on USB debugging.
2. Remove every account from the phone, and every extra user.

   Why: Android only names a device owner on a phone that looks unused, with no accounts and no other users. It is a security rule. It matters only at the moment of the command in step 3; add the accounts back right after, and no factory reset is needed.

   Samsung's Secure Folder and work profiles count as extra users, and deleting them deletes the data inside. If you need that data, back it up first, or stay with App mode or Pinned.
3. From a computer, run the command for your build:

   ```sh
   # release build (app.polylute)
   adb shell dpm set-device-owner app.polylute/com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver

   # debug build (app.polylute.debug, a separate app)
   adb shell dpm set-device-owner app.polylute.debug/com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver
   ```

   The Dedicated page in the app shows the command for the build you are running and can copy it. Android may refuse; nothing is active until Android confirms it.
4. Add the accounts back.
5. Make Polylute the home app: Settings, Home and DAP mode, Choose home app. On a Device Owner phone the level is Dedicated; there is nothing else to choose.

### Leaving Dedicated

Settings, Home and DAP mode, **Stop being the device owner** (in the Spanish app: "Dejar de ser propietario del dispositivo"). Polylute clears what it set and gives up Device Owner. No reset is needed. Polylute stays the home app until you pick another one with Choose home app. If you cannot open the app at all, a factory reset also ends it.

## Build

```sh
cd PixelPlayerOSS
./gradlew :app:assembleDebug
```

Run Gradle with JDK 21. The APKs are in `app/build/outputs/apk/debug/`, one per ABI; add `-Ppixelplayer.enableAbiSplits=false` for a single universal APK. The debug build is the app `app.polylute.debug`, separate from the release app `app.polylute`.

For a release build, see [PixelPlayerOSS/docs/release-signing.md](PixelPlayerOSS/docs/release-signing.md).

The repository has two parts: `PixelPlayerOSS/` is the app, and `decent-player/` holds the USB audio driver, the Media3 wrapper and the FLAC decoder.

## Privacy and notices

What the app stores and every network request it can make: [PixelPlayerOSS/PRIVACY.md](PixelPlayerOSS/PRIVACY.md).

Third-party notices: Settings, About, Licences and open-source notices in the app, or [PixelPlayerOSS/app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md](PixelPlayerOSS/app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md).

## License and credits

Polylute is based on PixelPlayerOSS (GPL-3.0) and decent-player (MIT).

- PixelPlayerOSS, Copyright (C) 2026 Theo Vilardo, maintained by [lostf1sh](https://github.com/lostf1sh): https://github.com/lostf1sh/PixelPlayerOSS
- decent-player, Copyright (c) 2026 Marcelo Silva: https://github.com/Ma145/decent-player

The licenses are in `PixelPlayerOSS/LICENSE`, `decent-player/LICENSE` and `decent-player/NOTICE.md`.

© 2026 Polylute contributors · GPL-3.0-or-later.  
A fan nod to Risk of Rain 2; not affiliated with Gearbox or Hopoo Games.

All Freedoms Granted Until Gearbox Says Otherwise.

Please don't send Mithrix.
