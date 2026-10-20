# Library scroll jank

The measurement B13 used, as a script anybody can repeat on the device.

```sh
export ANDROID_SERIAL=<adb serial>            # `adb devices -l`; the HONOR is reached over Wi-Fi
tools/library-jank/measure-library-scroll.sh before          # one run, a label for its folder
ART=cold WARMUP=0 tools/library-jank/measure-library-scroll.sh after-cold
```

It opens Library -> Songs, goes back to the first row, resets `dumpsys gfxinfo`, does the reviewer's sequence
(6 flings down, then the 3 "up" ones, `input swipe 360 1300 360 300 60`, 1.2 s apart; coordinates are scaled for
other screen sizes) and prints what `dumpsys gfxinfo` says: frames, janky frames, p50/p90/p95/p99, Slow UI thread,
Missed Vsync. Every run leaves `./library-jank-runs/<label>/{gfxinfo.txt,summary.json}`.

## What decides the number

The same list measures very differently depending on what the artwork pipeline has to do, so `ART` picks it:

| `ART` | state | what a fling costs |
|---|---|---|
| `warm` (default) | thumbnails are still in Coil's memory cache | composition, layout and draw only |
| `disk` | memory cache dropped (`am send-trim-memory RUNNING_CRITICAL`), cover files on disk | + decode of the cached cover |
| `cold` | memory cache dropped **and** `files/album_art` moved aside for the run (put back afterwards, also if the script is interrupted) | + extracting every cover from its audio file: a fling over songs never shown before |

`WARMUP=1` runs the sequence once unmeasured first (it is what `warm` needs). `SETTLE=1` (default) waits until the
app's CPU is quiet: after an install the app runs a library sync for minutes, and that would be measured as scroll
jank. `TRACE=1` also records a Perfetto trace of the flings (below). `RELAUNCH=1` brings the app forward first.

Things the script guards, because each cost a wrong number once:

- **the screen sleeps** (30 s here) and a fling at a dark screen measures nothing: every gesture is preceded by a wake-up;
- **the list's position drifts**: the recipe's flings down go further than the ones "up" come back (those start at
  y=300, which is the list's header, and scroll nothing), so without the return-to-top step every run starts deeper
  than the one before; `TOP=1` (default) flings up inside the list until two screenshots agree;
- **nothing happened**: if the screen is identical after the flings down the script says
  `WARNING: ... the list did not scroll, discard this run`;
- **the taps are blind**: it refuses to tap unless the foreground window is the app (a system dialog such as the
  default-home chooser can sit on top after an install).

Expect run-to-run noise of several points; B13's numbers are 3 runs per cell, and a process that has just been
installed is slower for its first minutes (the JIT is cold: a debuggable app is never compiled ahead of time, Android
refuses it: `cmd package compile -m speed` leaves it at `verify`).

## Why it is not JankStats / Macrobenchmark

The `baselineprofile` module exists, but a Macrobenchmark needs `connectedAndroidTest`, and on this device that
task uninstalls the app at the end (it is the HOME app and Device Owner). `dumpsys gfxinfo` is the same source JankStats
reads, without installing anything. (`gfxinfo ... framestats` returns only ~10 frames per dump on this ROM, so the
script does not use it; `gfx_summary.py` can read such dumps if another device gives more.)

## Where did the frame go: Perfetto

```sh
TRACE=1 tools/library-jank/measure-library-scroll.sh traced        # leaves traced/trace.pftrace
TRACE_PROCESSOR=/path/to/trace_processor_shell python3 -I tools/library-jank/analyze_trace.py traced/trace.pftrace
```

`trace_processor_shell` is Perfetto's query tool; the prebuilt binaries are linked from https://get.perfetto.dev/trace_processor
(the launcher script there pins the SHA-256 of each; B13 used `v58.2/linux-amd64`, 58042408e6cc...). The analysis prints the
frames grouped by how many list rows were composed in them (the cost of one new row is the slope) and the main thread's
slices by inclusive and self time (`Compose:recompose`, `AndroidOwner:measureAndLayout`, text measure, `onForgotten`...).

## Where did the CPU go: simpleperf

For what does not show on the main thread (the artwork pool, GC), a sampling profile of the whole app, with Java frames:

```sh
NDK=$ANDROID_HOME/ndk/<version>/simpleperf
python3 $NDK/app_profiler.py -p app.polylute.debug \
  -r "-e cpu-clock -f 1500 --call-graph dwarf,16384 --duration 30" -o perf.data --disable_adb_root -nb &
tools/library-jank/measure-library-scroll.sh profiled
$NDK/bin/linux/x86_64/simpleperf report -i perf.data --sort tid,comm --percent-limit 2          # which threads
$NDK/bin/linux/x86_64/simpleperf report -i perf.data --comms DefaultDispatch --sort symbol --children --percent-limit 3
```

(`--call-graph dwarf` is what makes the interpreted Kotlin frames appear by name.) The debug package is debuggable, which
is what lets `--app` profile it.
