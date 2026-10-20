#!/usr/bin/env bash
# B13-3: repeatable jank measurement of the library songs list on the device.
#
#   ANDROID_SERIAL=<adb serial> tools/library-jank/measure-library-scroll.sh [label]
#
# What it does (the exact sequence the reviewer used for B13, made scriptable):
#   1. optionally brings the app forward again (RELAUNCH=1: `monkey` LAUNCHER intent; the device-owner HOME app
#      cannot be force-stopped, and this does not reset the list's scroll position),
#   2. opens Library -> Songs (taps by screen fraction, so it works at any resolution) and flings back up to
#      the first row, so every run crosses the same rows,
#   3. optionally runs the same sequence once unmeasured (WARMUP=1) so thumbnails are cached,
#   4. `dumpsys gfxinfo <pkg> reset` (and, with TRACE=1, a Perfetto recording around the flings),
#   5. FLINGS_DOWN long flings down then FLINGS_UP up, GAP seconds apart
#      (`input swipe 360 1300 360 300 60` at 720x1610, scaled for other sizes),
#   6. `dumpsys gfxinfo <pkg>` (the numbers the reviewer read) plus, per fling, a framestats dump so
#      the per-phase breakdown (input / animation=Compose recomposition / draw / render) can be built.
#
# Output: $OUT_DIR/<label>/{gfxinfo.txt,framestats-N.txt,summary.json} and the summary on stdout.
# The device keeps the last 120 frames of framestats only, which is why they are taken after each fling.
#
# The result depends on what the artwork pipeline has to do, so ART picks the regime (B13 measured all three):
#   ART=warm  (default) thumbnails are still in Coil's memory cache: composition/layout cost only,
#   ART=disk  memory cache dropped (`am send-trim-memory RUNNING_CRITICAL`), cover files on disk: the decode path,
#   ART=cold  memory cache dropped AND the cover caches (files/album_art, files/album_art_thumbs) moved aside for the run and put back
#             afterwards (also if the script is interrupted): the extraction path, which is what a fling
#             over songs that were never shown before costs.
# SETTLE=1 (default) waits until the app's CPU is quiet before starting: after an install the app runs a
# library sync for minutes, which would otherwise be measured as scroll jank.
#
# Env knobs: PKG (default the debug package), RELAUNCH=0|1, WARMUP=0|1, FLINGS_DOWN=6, FLINGS_UP=3,
#            ART=warm|disk|cold, SETTLE=1|0, TOP=1|0 (return to the first row before measuring),
#            DEPTH=N (after going to the top, N unmeasured flings down before measuring: the first ~150 rows are the
#            list's initial load, and everything after them also pays for paging, so a fling over 4,870 songs is only
#            measured honestly deep in the list; DEPTH=60 is about row 1,500),
#            TRACE=1 (also record $out/trace.pftrace with perfetto-config.txt; analyse it with
#            trace_processor_shell, see README.md),
#            GAP=1.2, FRAMESTATS=0 (1 = also dump framestats after each fling; this ROM returns only ~10 frames per dump, so it is off),
#            OUT_DIR (default ./library-jank-runs).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
label="${1:-run-$(date +%Y%m%d-%H%M%S)}"
PKG="${PKG:-app.polylute.debug}"
RELAUNCH="${RELAUNCH:-${RESTART:-0}}"
TRACE="${TRACE:-0}"
ART="${ART:-warm}"
TOP="${TOP:-1}"
DEPTH="${DEPTH:-0}"
DEPTH_GAP="${DEPTH_GAP:-0.5}"
SETTLE="${SETTLE:-1}"
WARMUP="${WARMUP:-0}"
FLINGS_DOWN="${FLINGS_DOWN:-6}"
FLINGS_UP="${FLINGS_UP:-3}"
GAP="${GAP:-1.2}"
FRAMESTATS="${FRAMESTATS:-0}"
OUT_DIR="${OUT_DIR:-./library-jank-runs}"
out="$OUT_DIR/$label"
mkdir -p "$out"

adb_sh() { adb shell "$@" | tr -d '\r'; }

size="$(adb_sh wm size | tail -1 | sed -E 's/.*: *//')"   # 720x1610
W="${size%x*}"; H="${size#*x}"
frac() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%d", a * b }'; }
X="$(frac "$W" 0.5)"
Y_FROM="$(frac "$H" 0.807)"   # 1300 of 1610
Y_TO="$(frac "$H" 0.186)"     #  300 of 1610
Y_LIST_TOP="$(frac "$H" 0.31)"      #  500: inside the list, below the tabs and the sort header
Y_LIST_BOTTOM="$(frac "$H" 0.87)"   # 1400

# The device sleeps by itself (and the app's own timeout turns the screen off while nothing plays): a fling at a dark
# screen measures nothing, so every gesture is preceded by a wake-up, which costs a few milliseconds when awake.
wake() { adb_sh input keyevent KEYCODE_WAKEUP; }
fling_down() { wake; adb_sh input swipe "$X" "$Y_FROM" "$X" "$Y_TO" 60; }
# The reviewer's recipe goes back up from y=300, which is the list's header on this layout: those flings start on
# the header and scroll nothing. They are kept as they are, for comparability with the numbers B13 started from.
fling_up()   { wake; adb_sh input swipe "$X" "$Y_TO" "$X" "$Y_FROM" 60; }
fling_up_in_list() { wake; adb_sh input swipe "$X" "$Y_LIST_TOP" "$X" "$Y_LIST_BOTTOM" 60; }

open_songs() {
  adb_sh input tap "$(frac "$W" 0.789)" "$(frac "$H" 0.953)"   # bottom bar: Library
  sleep 1.0
  adb_sh input tap "$(frac "$W" 0.178)" "$(frac "$H" 0.132)"   # first tab: Songs
  sleep 1.0
}

screen_hash() { adb exec-out screencap -p | md5sum | cut -c1-12; }

# Back to the first row. RELAUNCH does not do it (the activity comes forward with the list's scroll state), so
# without this every run starts three flings deeper than the one before and, after enough runs, at the very end.
# Flings up until two screenshots a few flings apart are identical.
to_top() {
  local prev="" cur n
  for ((n = 1; n <= 300; n++)); do
    fling_up_in_list; sleep 0.3
    if (( n % 6 == 0 )); then
      cur="$(screen_hash)"
      if [[ "$cur" == "$prev" ]]; then return 0; fi
      prev="$cur"
    fi
  done
  echo "WARNING: the list never stopped moving upwards, the run may not start at the top" >&2
}

sequence() {  # $1 = dump prefix or empty
  local i=0 k before
  before="$(screen_hash)"
  for ((k = 0; k < FLINGS_DOWN; k++)); do
    fling_down; sleep "$GAP"
    if [[ -n "$1" ]]; then i=$((i + 1)); adb_sh dumpsys gfxinfo "$PKG" framestats > "$1$i.txt"; fi
  done
  # Not a measurement: a run whose list never moved (library still syncing, wrong tab) is meaningless.
  if [[ "$(screen_hash)" == "$before" ]]; then
    echo "WARNING: the screen is identical after the down flings: the list did not scroll, discard this run" >&2
  fi
  for ((k = 0; k < FLINGS_UP; k++)); do
    fling_up; sleep "$GAP"
    if [[ -n "$1" ]]; then i=$((i + 1)); adb_sh dumpsys gfxinfo "$PKG" framestats > "$1$i.txt"; fi
  done
}

cpu_ticks() {  # utime+stime of the app, in clock ticks (100/s)
  local pid; pid="$(adb_sh pidof "$PKG" | awk '{print $1}')"
  adb_sh cat "/proc/$pid/stat" | sed 's/.*) //' | awk '{print $12 + $13}'
}

settle() {  # two consecutive 3 s windows under 5 % of one core
  local quiet=0 t a b
  for ((t = 0; t < 100 && quiet < 2; t++)); do
    a="$(cpu_ticks)"; sleep 3; b="$(cpu_ticks)"
    if (( b - a < 15 )); then quiet=$((quiet + 1)); else quiet=0; fi
  done
  if (( quiet < 2 )); then echo "WARNING: the app never went quiet (sync still running?), numbers are not clean" >&2; fi
}

ART_DIRS="album_art album_art_thumbs"   # the full-size covers and the list thumbnails
restore_art() {
  adb_sh "run-as $PKG sh -c 'for d in $ART_DIRS; do if [ -d files/\$d.b13keep ]; then rm -rf files/\$d; mv files/\$d.b13keep files/\$d; fi; done'" || true
}
trap restore_art EXIT
restore_art   # a previous interrupted run may have left the real cache aside

if [[ "$SETTLE" == 1 ]]; then settle; fi

focus() { adb_sh dumpsys window | grep -m1 mCurrentFocus || true; }

launch_songs() {
  if [[ "$RELAUNCH" == 1 ]]; then
    adb_sh monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
    sleep 6
  fi
  adb_sh input keyevent KEYCODE_WAKEUP
  # The taps below are blind: never send them to another window (this is a device-owner HOME app and a
  # system dialog such as the default-home chooser can sit on top after an install).
  if [[ "$(focus)" != *"$PKG/"* ]]; then
    adb_sh am start -n "$PKG/com.lostf1sh.pixelplayeross.MainActivity" > /dev/null
    sleep 3
  fi
  if [[ "$(focus)" != *"$PKG/"* ]]; then
    echo "refusing to tap: the foreground window is not $PKG ($(focus))" >&2
    exit 3
  fi
  open_songs
  if [[ "$TOP" == 1 ]]; then to_top; fi
  local k
  for ((k = 0; k < DEPTH; k++)); do fling_down; sleep "$DEPTH_GAP"; done
  if (( DEPTH > 0 )); then sleep 2; fi
}

launch_songs

if [[ "$WARMUP" == 1 ]]; then
  sequence ""
  # Back to the first row, so the measured pass crosses the rows the warm-up crossed.
  launch_songs
fi

if [[ "$ART" == disk || "$ART" == cold ]]; then
  adb_sh am send-trim-memory "$PKG" RUNNING_CRITICAL > /dev/null || true
  if [[ "$ART" == cold ]]; then
    adb_sh "run-as $PKG sh -c 'for d in $ART_DIRS; do if [ -d files/\$d ]; then mv files/\$d files/\$d.b13keep; fi; mkdir -p files/\$d; done'"
  fi
  sleep 3
fi

adb_sh dumpsys gfxinfo "$PKG" reset > /dev/null
if [[ "$TRACE" == 1 ]]; then
  adb_sh rm -f /data/misc/perfetto-traces/b13.pftrace
  sed "s/__PKG__/$PKG/" "$here/perfetto-config.txt" \
    | adb shell 'perfetto --background-wait --txt -c - -o /data/misc/perfetto-traces/b13.pftrace' > /dev/null
fi
if [[ "$FRAMESTATS" == 1 ]]; then sequence "$out/framestats-"; else sequence ""; fi
adb_sh dumpsys gfxinfo "$PKG" > "$out/gfxinfo.txt"
if [[ "$TRACE" == 1 ]]; then
  adb_sh 'killall -INT perfetto; sleep 4'
  adb pull /data/misc/perfetto-traces/b13.pftrace "$out/trace.pftrace" > /dev/null
fi

shopt -s nullglob
python3 -I "$here/gfx_summary.py" "$out/gfxinfo.txt" "$out"/framestats-*.txt --json "$out/summary.json"
