#!/usr/bin/env python3
"""Summarise `dumpsys gfxinfo <pkg>` (+ optional framestats dumps) for the library-scroll run.

usage: gfx_summary.py SUMMARY.txt [FRAMESTATS_DUMP.txt ...] [--json OUT.json]

SUMMARY.txt   the plain `dumpsys gfxinfo <pkg>` taken once at the end (frame counts, histogram).
FRAMESTATS_*  `dumpsys gfxinfo <pkg> framestats` taken after each fling; the device keeps only the
              last 120 frames, so the dumps are merged by IntendedVsync. Without them only the
              gfxinfo summary is printed.

The frame breakdown uses the framestats columns (all in ns on the device, printed in ms):
  input      HandleInputStart  -> AnimationStart        (touch/fling dispatch)
  animation  AnimationStart    -> PerformTraversalsStart (Choreographer animation callbacks; this is
                                where Compose runs its frame clock: recomposition happens here)
  layout     PerformTraversalsStart -> DrawStart          (View measure/layout; Compose measure+layout
                                run inside the draw pass of AndroidComposeView so little shows here)
  draw       DrawStart         -> SyncQueued              (recording the display lists: Compose
                                measure/layout/draw lambdas run here)
  sync+gpu   SyncQueued        -> FrameCompleted          (RenderThread: sync, issue, swap, GPU)
"""
import json
import re
import sys


def pct(sorted_values, p):
    if not sorted_values:
        return float("nan")
    idx = min(len(sorted_values) - 1, max(0, int(round(p / 100.0 * (len(sorted_values) - 1)))))
    return sorted_values[idx]


def parse_summary(text):
    out = {}
    patterns = {
        "total_frames": r"Total frames rendered:\s*(\d+)",
        "janky_frames": r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)",
        "janky_legacy": r"Janky frames \(legacy\):\s*(\d+)\s*\(([\d.]+)%\)",
        "p50": r"50th percentile:\s*(\d+)ms",
        "p90": r"90th percentile:\s*(\d+)ms",
        "p95": r"95th percentile:\s*(\d+)ms",
        "p99": r"99th percentile:\s*(\d+)ms",
        "missed_vsync": r"Number Missed Vsync:\s*(\d+)",
        "high_input_latency": r"Number High input latency:\s*(\d+)",
        "slow_ui": r"Number Slow UI thread:\s*(\d+)",
        "slow_bitmap": r"Number Slow bitmap uploads:\s*(\d+)",
        "slow_issue": r"Number Slow issue draw commands:\s*(\d+)",
        "deadline_missed": r"Number Frame deadline missed:\s*(\d+)",
    }
    for key, pat in patterns.items():
        m = re.search(pat, text)
        if not m:
            continue
        if key.startswith("janky"):
            out[key] = int(m.group(1))
            out[key + "_pct"] = float(m.group(2))
        else:
            out[key] = int(m.group(1))
    return out


def parse_framestats(text):
    """Return a list of dicts, one per frame, from a `framestats` dump."""
    rows = []
    in_data = False
    header = None
    for line in text.splitlines():
        line = line.strip()
        if line == "---PROFILEDATA---":
            in_data = not in_data
            header = None
            continue
        if not in_data or not line:
            continue
        cols = line.rstrip(",").split(",")
        if header is None:
            header = cols
            continue
        if len(cols) < len(header):
            continue
        try:
            rows.append({h: int(c) for h, c in zip(header, cols)})
        except ValueError:
            continue
    return rows


def frame_metrics(frame):
    ms = 1e-6
    start = frame["IntendedVsync"]
    # Timestamps of 0x7fffffffffffffff mean "not recorded".
    def at(name):
        v = frame.get(name, 0)
        return v if 0 < v < (1 << 62) else None
    total = (at("FrameCompleted") or start) - start
    he = at("HandleInputStart")
    an = at("AnimationStart")
    pt = at("PerformTraversalsStart")
    ds = at("DrawStart")
    sq = at("SyncQueued")
    fc = at("FrameCompleted")
    def d(a, b):
        return (b - a) * ms if a and b else 0.0
    ui_thread = d(he or an or pt, sq)  # everything on the UI thread before handing to RenderThread
    return {
        "total": total * ms,
        "ui_thread": ui_thread,
        "input": d(he, an),
        "animation": d(an, pt),
        "layout": d(pt, ds),
        "draw": d(ds, sq),
        "render": d(sq, fc),
        "deadline": (frame.get("FrameDeadline", 0) - start) * ms if frame.get("FrameDeadline") else 16.67,
        "janky": at("FrameCompleted") is not None and at("FrameCompleted") > frame.get("FrameDeadline", 1 << 62),
    }


def main(argv):
    json_out = None
    args = []
    it = iter(argv[1:])
    for a in it:
        if a == "--json":
            json_out = next(it)
        else:
            args.append(a)
    if not args:
        print(__doc__)
        return 2
    result = {"summary": parse_summary(open(args[0]).read())}
    s = result["summary"]
    print("== dumpsys gfxinfo summary ==")
    for key in ("total_frames", "janky_frames", "janky_legacy", "p50", "p90", "p95", "p99",
                "missed_vsync", "high_input_latency", "slow_ui", "slow_bitmap", "slow_issue",
                "deadline_missed"):
        if key not in s:
            continue
        if key.startswith("janky"):
            print(f"  {key:<20} {s[key]:>5}  ({s[key + '_pct']:.1f} %)")
        elif key.startswith("p") and key[1:].isdigit():
            print(f"  {key:<20} {s[key]:>5} ms")
        else:
            print(f"  {key:<20} {s[key]:>5}")

    frames = {}
    for path in args[1:]:
        for fr in parse_framestats(open(path).read()):
            if fr.get("Flags", 0) & 1:  # window layout changed: not a scroll frame
                continue
            frames[fr["IntendedVsync"]] = fr
    if frames:
        ms = [frame_metrics(f) for _, f in sorted(frames.items())]
        n = len(ms)
        totals = sorted(m["total"] for m in ms)
        uis = sorted(m["ui_thread"] for m in ms)
        janky = sum(1 for m in ms if m["janky"])
        slow_ui = sum(1 for m in ms if m["ui_thread"] > 16.67 * 0.9)
        print(f"== framestats, {n} frames merged from {len(args) - 1} dumps ==")
        print(f"  janky (deadline missed)  {janky:>4} ({100.0 * janky / n:.1f} %)")
        print(f"  UI thread > 15 ms        {slow_ui:>4} ({100.0 * slow_ui / n:.1f} %)")
        print("  frame total   p50 %.1f  p90 %.1f  p95 %.1f  p99 %.1f  max %.1f ms" % (
            pct(totals, 50), pct(totals, 90), pct(totals, 95), pct(totals, 99), totals[-1]))
        print("  UI thread     p50 %.1f  p90 %.1f  p95 %.1f  p99 %.1f  max %.1f ms" % (
            pct(uis, 50), pct(uis, 90), pct(uis, 95), pct(uis, 99), uis[-1]))
        slow = [m for m in ms if m["janky"]]
        print("  mean ms per phase       input  anim  layout  draw  render   (all frames | janky frames)")
        for label, group in (("all", ms), ("janky", slow)):
            if not group:
                continue
            k = len(group)
            avg = lambda name: sum(m[name] for m in group) / k
            print("    %-6s %5d frames  %5.1f %5.1f %6.1f %5.1f %6.1f" % (
                label, k, avg("input"), avg("animation"), avg("layout"), avg("draw"), avg("render")))
        result["framestats"] = {
            "frames": n, "janky": janky, "janky_pct": 100.0 * janky / n,
            "slow_ui": slow_ui,
            "total_ms": {"p50": pct(totals, 50), "p90": pct(totals, 90), "p95": pct(totals, 95),
                         "p99": pct(totals, 99), "max": totals[-1]},
            "ui_ms": {"p50": pct(uis, 50), "p90": pct(uis, 90), "p95": pct(uis, 95),
                      "p99": pct(uis, 99), "max": uis[-1]},
        }
    if json_out:
        with open(json_out, "w") as f:
            json.dump(result, f, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
