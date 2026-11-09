#!/usr/bin/env python3
"""Low-RAM smoke gate: the worst APP-ATTRIBUTABLE main-thread stall of one process during a cold start.

usage: app_stall.py <logcat -v threadtime file> <pid>
  -> prints JSON {raw_ms, app_ms, excluded_ms, frame1_own_ms, stall_limit_ms, frame1_limit_ms, failures:[...], frames:[...]}

The emulator draws Blaze's very first frame in software (swiftshader GL). While the RenderThread draws and swaps that
frame the main thread is parked in HWUI waiting for it, so Choreographer reports a "Skipped N frames" that no app code
caused. On a slow runner that draw takes seconds, on a fast one a few hundred ms.

Gate rule (owner-approved 2026-10-27, changed 2026-11-09 after d0f196dc):

  1. The main thread's wait on the first frame's RenderThread draw + swap [IssueDrawCommandsStart, SwapBuffersCompleted]
     is ALWAYS removed from the app-attributable stall, whatever its length. Until 2026-11-09 it was removed only when that
     draw took over 700 ms, so on a fast runner (d0f196dc: 413 ms GL + 80 ms swap) the whole wait counted as app stall.
     The cut is made once: from the frame right after the first drawn frame when HWUI logged a Davey line for it (the part
     of its delay that overlaps the first frame's draw), otherwise from the largest Choreographer skip that matches no Davey
     line (at most the first frame's draw time).
  2. For each frame HWUI reported (OpenGLRenderer "Davey!" lines, logged for every frame over 700 ms), the main-thread delay
     before the frame started is HandleInputStart - IntendedVsync, and the frame's own main-thread work (input + animation +
     measure/layout + record) is SyncQueued - HandleInputStart. Choreographer skips that match no Davey line count in full.
     app_ms is the worst of these after the cut, and must stay under 700 ms (42 frames at 60 Hz).
  3. Stricter check on the first frame: frame1_own_ms = wait for main + layout + record of frame 1, i.e.
     SyncQueued - IntendedVsync, with the emulator's draw and swap left out, must be at most 400 ms. Rule 1 can therefore
     never hide a real first-composition regression.

raw_ms is the old uncut Choreographer number, reported alongside. failures lists every check that failed, empty on a pass.
"""
import json, re, sys

FRAME_MS = 1000.0 / 60
STALL_LIMIT_MS = 700      # app_ms must be under this
FRAME1_LIMIT_MS = 400     # frame 1's own main-thread work must be at most this

def parse(path, pid):
    davey, skips = [], []
    with open(path, errors="replace") as fh:
        lines = fh.readlines()
    for line in lines:
        f = line.split(None, 6)
        if len(f) < 7 or f[2] != str(pid):
            continue
        msg = f[6]
        if "Davey!" in msg:
            kv = dict((k, int(v)) for k, v in re.findall(r"(\w+)=(-?\d+)", msg))
            if "IntendedVsync" in kv and "HandleInputStart" in kv:
                davey.append(kv)
        elif f[3] == str(pid) and f[5].startswith("Choreographer"):
            m = re.search(r"Skipped (\d+) frames", msg)
            if m:
                skips.append(int(m.group(1)))
    davey.sort(key=lambda d: d["IntendedVsync"])
    return davey, skips

def overlap(a0, a1, b0, b1):
    return max(0, min(a1, b1) - max(a0, b0))

def analyse(davey, skips):
    frames, excluded = [], 0.0
    first = davey[0] if davey else None
    # The first frame's RenderThread draw + swap, cut whatever its length (rule 1).
    draw = None
    if first and first.get("SwapBuffersCompleted", 0) and first.get("IssueDrawCommandsStart", 0):
        draw_ms = max(0, first["SwapBuffersCompleted"] - first["IssueDrawCommandsStart"]) / 1e6
        if draw_ms > 0:
            draw = (first["IssueDrawCommandsStart"], first["SwapBuffersCompleted"], draw_ms)
    matched = set()
    for i, d in enumerate(davey):
        v0, s = d["IntendedVsync"], d["HandleInputStart"]
        delay = max(0, s - v0) / 1e6
        cut = 0.0
        if draw and i == 1:
            cut = overlap(v0, s, draw[0], draw[1]) / 1e6
        work = max(0, d.get("SyncQueued", s) - s) / 1e6
        app = max(delay - cut, work)
        excluded += cut
        frames.append({"frame": i + 1, "delay_ms": round(delay), "render_wait_excluded_ms": round(cut), "own_work_ms": round(work),
                       "app_ms": round(app), "first_frame_gl_ms": round(draw[2]) if draw and i == 0 else None})
        for j, n in enumerate(skips):
            if j not in matched and abs(n * FRAME_MS - delay) <= 2 * FRAME_MS:
                matched.add(j); break
    unmatched = [n * FRAME_MS for j, n in enumerate(skips) if j not in matched]
    # The frame after the first frame is often fast, so HWUI logs no Davey line for it, yet Choreographer still reports the
    # main thread's wait on the first frame as one "Skipped N frames" (66ed074d, d0f196dc). With no Davey line to cut it
    # from, take the same one-time cut from the largest unmatched skip, at most the first frame's draw + swap time.
    if draw and len(davey) < 2 and unmatched:
        k = max(range(len(unmatched)), key=lambda x: unmatched[x])
        cut = min(unmatched[k], draw[2])
        unmatched[k] -= cut
        excluded += cut
    raw = max([n * FRAME_MS for n in skips] + [0])
    app = max([f["app_ms"] for f in frames] + unmatched + [0])
    # Rule 3: frame 1's own main-thread work = wait for main + layout + record = SyncQueued - IntendedVsync.
    frame1_own = None
    if first:
        frame1_own = max(0, first.get("SyncQueued", first["HandleInputStart"]) - first["IntendedVsync"]) / 1e6
    failures = []
    if round(app) >= STALL_LIMIT_MS:
        failures.append("app-attributable main stall %d ms >= %d ms" % (round(app), STALL_LIMIT_MS))
    if frame1_own is not None and round(frame1_own) > FRAME1_LIMIT_MS:
        failures.append("frame 1 own main-thread work %d ms > %d ms" % (round(frame1_own), FRAME1_LIMIT_MS))
    return {"raw_ms": round(raw), "app_ms": round(app), "excluded_ms": round(excluded),
            "frame1_own_ms": round(frame1_own) if frame1_own is not None else None,
            "stall_limit_ms": STALL_LIMIT_MS, "frame1_limit_ms": FRAME1_LIMIT_MS, "failures": failures,
            "unmatched_skips_ms": [round(x) for x in unmatched], "first_frame_draw_ms": round(draw[2]) if draw else None,
            "frames": frames}

if __name__ == "__main__":
    d, s = parse(sys.argv[1], sys.argv[2])
    print(json.dumps(analyse(d, s)))
