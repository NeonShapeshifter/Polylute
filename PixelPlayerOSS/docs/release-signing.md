# Signing a release

A release of Polylute (`app.polylute`) is signed with a key that only its owner holds. The key is **never** in the repository, never generated
by the build, and never replaced by a fallback: the build reads it from `keystore.properties` and from nowhere else. Without it, a release build
**fails** and says why; an unsigned artifact has to be asked for.

The debug build is a different app (`app.polylute.debug`) with its own, unrelated key. The release key never signs it and it never signs a release.

## 1. Make the key (once)

```sh
cd Polylute/PixelPlayerOSS
keytool -genkeypair -v \
  -keystore polylute-release.jks -storetype PKCS12 \
  -alias <alias> -keyalg RSA -keysize 4096 -validity 10000
```

Choose the alias yourself (a word you will recognise; it is not a secret but it is not written anywhere in this repository either). `keytool` asks for a password (twice) and for the name and organisation that go into the certificate; those are public, they are in every APK.
A PKCS12 key store has **one** password for the store and the key: use the same value for `storePassword` and `keyPassword` below.
`-validity 10000` is about 27 years; a key that expires before the app's last update cannot sign it.

Write down the certificate's fingerprint, which is how you (and anyone you give the APK to) recognise the key later:

```sh
keytool -list -v -keystore polylute-release.jks -alias <alias> | grep 'SHA256:'
```

## 2. Tell the build where it is

`Polylute/PixelPlayerOSS/keystore.properties` (ignored by git, as are `*.jks` and `*.keystore`):

```properties
storeFile=polylute-release.jks
storePassword=<the password>
keyAlias=<alias>
keyPassword=<the same password>
```

- `storeFile` is relative to `Polylute/PixelPlayerOSS/`, or an absolute path. If the line is left out the build looks for
  `polylute-release.jks` there.
- `chmod 600 keystore.properties polylute-release.jks`. Keep both out of backups that leave your control and out of every log, issue and chat.
- Before any commit, `git status` must show neither file. If one ever appears, stop: do not add it.

## 3. Build

```sh
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/…   (one APK per ABI)
./gradlew :app:bundleRelease       # app/build/outputs/bundle/release/app-release.aab
```

The version is `APP_VERSION_NAME` / `APP_VERSION_CODE` in `gradle.properties` (1.0.0, code 1), or `-PAPP_VERSION_NAME=…` / `-PAPP_VERSION_CODE=…`
on the command line. **The version code must go up with every build that anyone installs over another**; Android refuses an update that does not
raise it.

If `keystore.properties` is missing, names a store that does not exist, or lacks a password or the alias, a build that would produce a release
(`assembleRelease`, `bundleRelease`, `build`, `assemble`, …) stops before it starts:

```
A release build was asked for, but it cannot be signed: there is no keystore.properties in …
```

To build an **unsigned** release on purpose (the CI-style gates, a recipe that signs elsewhere), say so:

```sh
./gradlew :app:bundleRelease -Ppixelplayer.disableReleaseSigning=true
```

Debug builds (`assembleDebug`, tests, lint) never need any of this.

## 4. Check what came out

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs --verbose app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

The `SHA-256 digest` of the signer must be the fingerprint you wrote down in step 1. The 16 KB and release-surface checks of the same build are
`tools/page16k/check_page_size.py` and `tools/release-surface/check_release_surface.py`.

## 5. What to back up, and where

| What | Why |
| --- | --- |
| `polylute-release.jks` | The key. Without it nothing can ever update the app: an APK signed by another key is another app to Android, and installing it means uninstalling the first (and losing its data). |
| The store password (`storePassword` = `keyPassword`) and the alias (the `<alias>` you chose) | The file is useless without them, and they cannot be recovered from it. |
| The SHA-256 fingerprint | To recognise the right key, and to tell a friend what the APK should be signed with. |

Keep **two copies of the key file** in two places that fail independently (a password manager's attachment and an encrypted drive, for example), and
the passwords in a password manager. Test the backup once: copy the file elsewhere, point a scratch `keystore.properties` at it, and build.

If the key is lost or leaked there is no recovery for a sideloaded app: make a new key and publish the next build as a fresh install (everyone has to
uninstall the old one first, which deletes its data). A store that offers its own signing service (Google Play App Signing) can reset an *upload* key;
that is a separate arrangement and is not set up here.
