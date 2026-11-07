#!/usr/bin/env python3
"""Self-test for tools/smoke/app_stall.py (the low-RAM smoke gate's cold-start verdict).

Runs in seconds, with no emulator: `python3 tools/smoke/test_app_stall.py`. lowram-smoke.yml runs it before the 25-minute
emulator job, so a broken gate script fails in seconds instead of after a full round. The cases are the real cold starts of
0.52.3 (numbers from the SUMMARY of each run), so any change to the attribution must keep their verdicts or say why.
The gate itself (app_ms < 700) is owner-approved and is not changed here.
"""
import os, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_stall  # noqa: E402

PID = 4242
MS = 1_000_000  # ns per ms


def davey(v0, delay, work, gl, tid=PID + 7):
    """One OpenGLRenderer Davey line: main starts `delay` ms after vsync, works `work` ms, RenderThread GL takes `gl` ms."""
    his = v0 + delay * MS
    sq = his + work * MS
    idc = sq + 1 * MS
    swap = idc + gl * MS
    kv = dict(IntendedVsync=v0, Vsync=v0, HandleInputStart=his, AnimationStart=his, PerformTraversalsStart=his,
              DrawStart=sq - 1 * MS, SyncQueued=sq, SyncStart=sq, IssueDrawCommandsStart=idc, SwapBuffers=swap,
              SwapBuffersCompleted=swap, FrameCompleted=swap + 1 * MS)
    body = ", ".join("%s=%d" % kv_ for kv_ in kv.items())
    total = (swap - v0) // MS
    return "11-07 06:10:34.000 %d %d I OpenGLRenderer: Davey! duration=%dms; Flags=1, %s, \n" % (PID, tid, total, body)


def skip(frames):
    return "11-07 06:10:34.261 %d %d I Choreographer: Skipped %d frames!  The application may be doing too much work on its main thread.\n" % (PID, PID, frames)


def run(lines):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.writelines(lines)
        path = f.name
    try:
        d, s = app_stall.parse(path, PID)
        return app_stall.analyse(d, s)
    finally:
        os.unlink(path)


V = 1_000_000_000_000


class AppStallTest(unittest.TestCase):
    def test_66ed074d_still_fails(self):
        # 66ed074d: first frame 670 ms to main start, 669 ms main work, 1441 ms software GL; Skipped 129 frames (2150 ms),
        # no Davey line for frame 2. Excluding the render wait leaves ~709 ms of real work: over the gate, as it should be.
        r = run([davey(V, 670, 669, 1441), skip(129)])
        self.assertEqual(r["excluded_ms"], 1441)
        self.assertGreater(r["app_ms"], 700)

    def test_2b84a204_passes(self):
        # 2b84a204 (onboarding no longer composes the hidden shell): frame 1 delay 272 ms, own work 515 ms, GL 924 ms;
        # Skipped 89 frames (1483 ms). 1483 - 924 = 559 ms app-attributable: passes.
        r = run([davey(V, 272, 515, 924), skip(89)])
        self.assertEqual(r["excluded_ms"], 924)
        self.assertEqual(r["app_ms"], 559)
        self.assertLess(r["app_ms"], 700)

    def test_fast_first_frame_counts_in_full(self):
        # GL under 700 ms is not the software first frame: nothing is excluded, the skip counts whole.
        r = run([davey(V, 300, 400, 650), skip(60)])
        self.assertEqual(r["excluded_ms"], 0)
        self.assertEqual(r["app_ms"], 1000)

    def test_second_davey_takes_the_cut_once(self):
        # Frame 2 has its own Davey line: the cut happens there (overlap with frame 1's GL) and never a second time.
        first = davey(V, 200, 300, 1500)
        second_v0 = V + 250 * MS
        r = run([first, davey(second_v0, 1700, 20, 5), skip(102)])
        self.assertLessEqual(r["excluded_ms"], 1501)
        self.assertEqual(r["unmatched_skips_ms"], [])

    def test_no_frames_no_stall(self):
        r = run([])
        self.assertEqual(r["app_ms"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
