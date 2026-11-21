# Engineering notes

## Unit test heap

The Android unit suite runs in one worker JVM. Full runs hit the 1 GiB heap
limit during the large queue tests, even though those tests passed on their
own. Heap histograms taken at the time showed retained byte arrays, Robolectric
resource tables and Media3 items. That is why the worker has a 1536 MiB limit in
[app/build.gradle.kts](../app/build.gradle.kts). Recheck with a full run of
`./gradlew :app:testDebugUnitTest` before lowering it.

## Compose tracing

The debug build includes Compose runtime tracing and the Perfetto SDK to
identify composables during queue taps and other UI interactions. To capture
named composition slices, enable SDK tracing in the debug process and include
the `track_event` data source in the recording. Ordinary system trace
categories alone do not capture these SDK events. The tracing dependencies are
declared only for debug in [app/build.gradle.kts](../app/build.gradle.kts).

## DAC EQ profiles

The profile table distinguishes upstream verification from verification by
Polylute on real hardware. Upstream verification records where the data came
from. Write eligibility goes through the app's write gate, which checks the
profile and any required user consent. Curve writes target only the user slot
owned by the app. When adding a device, keep it read-only until there is a
supported write path for that model and a hardware check of write, readback and
restore. See [FiioHidProfiles](../app/src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/FiioHidProfiles.kt)
and [DacEqWriteGate](../app/src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqWriteGate.kt).

## USB reset and volume

A reset can return a DAC to its power-up volume. Linux can drop the claimed
interfaces and bind the audio driver again before the reset call returns.
Polylute reclaims the streaming interface first, then the control interface,
and restores and reads back the hardware level before normal hand-back.
There is still an interval between kernel rebinding and the app reclaiming
an interface. If a claim fails, the app reports what it actually holds and
whether the level could be confirmed. See
[ResetLevelRestorer](../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/ResetLevelRestorer.kt).

## Database migrations

Android Auto Backup can restore a database with the expected version number
and a different set of columns. Where migrations use `hasColumn`, preserve
the check before adding a column so a restored database with that column
already present can still migrate. Update the exported Room schema alongside
migration changes. See [Migrations](../app/src/main/java/com/lostf1sh/pixelplayeross/data/database/Migrations.kt)
and the [exported schemas](../app/schemas).

## Live background

On Android 13 and later, the animated album background uses an AGSL shader.
The animation moves slowly enough that the app updates it at 30 frames per
second, reducing shader updates compared with 60. The static background uses
the existing drawing path. JVM renders cover that static path. Use an Android
device to assess the shader's motion or GPU cost. See
[LiveBlobs](../app/src/main/java/com/lostf1sh/pixelplayeross/ui/glass/LiveBlobs.kt).

## WAV metadata editing

The previous WAV tag writer changed an audio byte in a regression fixture
with a `LIST` chunk before the audio and an ID3 chunk after it. The dedicated
[WavTagWriter](../app/src/main/java/com/lostf1sh/pixelplayeross/data/media/WavTagWriter.kt)
copies the chunks that are outside the tags byte for byte and updates the RIFF
size. The editor works on a copy, then checks the audio payload before
replacing the original. The WAV check compares the non-tag chunks in order.
[B57Fix1EditorFaultsTest](../app/src/test/java/com/lostf1sh/pixelplayeross/data/media/B57Fix1EditorFaultsTest.kt)
covers the regression.
