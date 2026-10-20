#!/usr/bin/env python3
"""Where did the main thread's frame time go? Reads a Perfetto trace taken with TRACE=1.

usage: TRACE_PROCESSOR=/path/to/trace_processor_shell analyze_trace.py trace.pftrace [package]

`trace_processor_shell` is Perfetto's standalone query tool (https://perfetto.dev/docs/analysis/trace-processor,
prebuilt binaries are linked from https://get.perfetto.dev/trace_processor). The trace needs the sections
Compose and the framework emit with the `view`/`gfx` atrace categories (perfetto-config.txt has them).

It prints
  1. the frames (Choreographer#doFrame on the main thread) grouped by how many rows were composed in
     them (a row = one `rememberAsyncImagePainter`): the cost of one new row is the slope,
  2. the main thread's slices by inclusive and self time (Compose:recompose, measure/layout, draw, ...),
  3. the main thread's CPU against the frames that contained it.
"""
import csv
import io
import os
import subprocess
import sys
import tempfile

TP = os.environ.get("TRACE_PROCESSOR", "trace_processor_shell")


def query(trace, sql):
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
        f.write(sql)
        path = f.name
    try:
        out = subprocess.run([TP, "-q", path, trace], capture_output=True, text=True, check=True).stdout
    finally:
        os.unlink(path)
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if line.startswith('"') and line.count('","') >= 0 and i + 1 < len(lines) and not line.startswith('"Trace'):
            return list(csv.DictReader(io.StringIO("\n".join(lines[i:]))))
    return []


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    trace = argv[1]
    pkg = argv[2] if len(argv) > 2 else "app.polylute.debug"
    main_utid = (
        "select t.utid from thread t join process p using(upid) "
        f"where p.name = '{pkg}' and t.tid = p.pid order by t.utid desc limit 1"
    )

    frames = query(trace, f"""
with fr as (select sl.ts, sl.dur from slice sl join thread_track tt on sl.track_id=tt.id
            where tt.utid=({main_utid}) and sl.name like 'Choreographer#doFrame%' and sl.depth=0),
     sub as (select sl.ts, sl.name from slice sl join thread_track tt on sl.track_id=tt.id where tt.utid=({main_utid}))
select round(f.dur/1e6,1) as dur_ms,
       (select count(*) from sub s where s.ts>=f.ts and s.ts<f.ts+f.dur and s.name='rememberAsyncImagePainter') as rows
from fr f order by f.ts;
""")
    by = {}
    for r in frames:
        by.setdefault(int(r["rows"]), []).append(float(r["dur_ms"]))
    print("== frames by rows composed in the frame ==")
    print("  rows  frames  mean ms   max ms")
    for rows in sorted(by):
        d = by[rows]
        print(f"  {rows:>4}  {len(d):>6}  {sum(d) / len(d):>7.1f}  {max(d):>7.1f}")
    one = [x for r in by for x in by[r] if r == 1]
    zero = by.get(0, [])
    if one and zero:
        print(f"  => a frame with one new row costs {sum(one) / len(one):.1f} ms against {sum(zero) / len(zero):.1f} ms with none")

    slices = query(trace, f"""
with s as (select sl.id, sl.name, sl.dur, sl.parent_id from slice sl join thread_track tt on sl.track_id=tt.id
           where tt.utid=({main_utid}) and sl.dur>0),
     c as (select parent_id pid, sum(dur) cdur from s where parent_id is not null group by parent_id)
select s.name, count(*) n, round(sum(s.dur)/1e6,1) incl_ms, round(sum(s.dur - coalesce(c.cdur,0))/1e6,1) self_ms
from s left join c on c.pid=s.id
where s.name not like 'Choreographer#doFrame%' and s.name not like 'deliverInputEvent%' and s.name not like 'ViewPostImeInputStage%'
group by s.name order by incl_ms desc limit 22;
""")
    print("\n== main-thread slices (ms over the whole recording) ==")
    print(f"  {'name':<44} {'n':>5} {'incl':>9} {'self':>9}")
    for r in slices:
        print(f"  {r['name'][:44]:<44} {r['n']:>5} {r['incl_ms']:>9} {r['self_ms']:>9}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
