#!/usr/bin/env python3
"""Self-test for tools/smoke/app_stall.py (the low-RAM smoke gate's cold-start verdict).

Runs in seconds, with no emulator: `python3 tools/smoke/test_app_stall.py`. lowram-smoke.yml runs it before the 25-minute
emulator job, so a broken gate script fails in seconds instead of after a full round. The cases are the real cold starts of
0.52.3 (numbers from the SUMMARY of each run), so any change to the attribution must keep their verdicts or say why.
The gate is owner-approved: app_ms < 700 with the first frame's draw + swap always cut, and frame 1's own main-thread
work (wait for main + layout + record) at most 400 ms. Both checks changed on 2026-11-09 after d0f196dc.
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


def frame1(v0, wait, layout, record, gl, swap, tid=PID + 7):
    """A Davey line with the SUMMARY's own split: vsync to main start, layout, record, RenderThread GL, swap (all ms)."""
    his = v0 + wait * MS
    pts = his
    ds = pts + layout * MS
    sq = ds + record * MS
    idc = sq
    sb = idc + gl * MS
    sbc = sb + swap * MS
    kv = dict(IntendedVsync=v0, Vsync=v0, HandleInputStart=his, AnimationStart=his, PerformTraversalsStart=pts,
              DrawStart=ds, SyncQueued=sq, SyncStart=sq, IssueDrawCommandsStart=idc, SwapBuffers=sb,
              SwapBuffersCompleted=sbc, FrameCompleted=sbc)
    body = ", ".join("%s=%d" % kv_ for kv_ in kv.items())
    return "11-09 08:41:56.710 %d %d I OpenGLRenderer: Davey! duration=%dms; Flags=1, %s, \n" % (PID, tid, (sbc - v0) // MS, body)


# d0f196dc's measured cold start, verbatim from smoke-out/logcat-release.txt of run 42585194751 (pid 6197).
D0F196DC_LOGCAT = [
    "11-09 08:41:56.710  6197  6212 I OpenGLRenderer: Davey! duration=866ms; Flags=1, FrameTimelineVsyncId=1267, "
    "IntendedVsync=89928851157, Vsync=90078851151, InputEventId=0, HandleInputStart=90085573723, AnimationStart=90085574154, "
    "PerformTraversalsStart=90085609216, DrawStart=90295016131, FrameDeadline=89945517823, FrameInterval=90085568235, "
    "FrameStartTime=16666666, SyncQueued=90300831038, SyncStart=90301017226, IssueDrawCommandsStart=90301324052, "
    "SwapBuffers=90715083802, FrameCompleted=90795622747, DequeueBufferDuration=2804, QueueBufferDuration=454627, "
    "GpuCompleted=90795622747, SwapBuffersCompleted=90791984650, DisplayPresentTime=1363312874193158144, \n",
    "11-09 08:41:56.731  6197  6197 I Choreographer: Skipped 43 frames!  The application may be doing too much work on its main thread.\n",
]


def skip(frames):
    return "11-07 06:10:34.261 %d %d I Choreographer: Skipped %d frames!  The application may be doing too much work on its main thread.\n" % (PID, PID, frames)


def run(lines, pid=PID):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.writelines(lines)
        path = f.name
    try:
        d, s = app_stall.parse(path, pid)
        return app_stall.analyse(d, s)
    finally:
        os.unlink(path)


V = 1_000_000_000_000


class AppStallTest(unittest.TestCase):
    def test_66ed074d_still_fails(self):
        # 66ed074d: first frame 670 ms to main start, 669 ms main work, 1441 ms software GL; Skipped 129 frames (2150 ms),
        # no Davey line for frame 2. Excluding the render wait leaves ~709 ms of real work: over the gate, as it should be.
        # Frame 1's own work (670 + 669 ms) also fails the 400 ms check.
        r = run([davey(V, 670, 669, 1441), skip(129)])
        self.assertEqual(r["excluded_ms"], 1441)
        self.assertGreater(r["app_ms"], 700)
        self.assertEqual(r["frame1_own_ms"], 1339)
        self.assertEqual(len(r["failures"]), 2)

    def test_2b84a204_stall_passes_but_frame1_fails_now(self):
        # 2b84a204 (onboarding no longer composes the hidden shell): frame 1 delay 272 ms, own work 515 ms, GL 924 ms;
        # Skipped 89 frames (1483 ms). 1483 - 924 = 559 ms app-attributable: under 700. Its frame 1 still did 787 ms of
        # main-thread work (mostly first composition), which the 400 ms frame-1 check added on 2026-11-09 rejects.
        r = run([davey(V, 272, 515, 924), skip(89)])
        self.assertEqual(r["excluded_ms"], 924)
        self.assertEqual(r["app_ms"], 559)
        self.assertLess(r["app_ms"], 700)
        self.assertEqual(r["frame1_own_ms"], 787)
        self.assertEqual(r["failures"], ["frame 1 own main-thread work 787 ms > 400 ms"])

    def test_d0f196dc_passes(self):
        # d0f196dc (fast runner, TotalTime 1200 ms): frame 1 866 ms = 157 wait + 209 layout + 5 record + 413 RenderThread
        # GL + 80 swap; Skipped 43 frames (717 ms) with no Davey line for frame 2. The old rule cut nothing because GL was
        # under 700 ms and failed it at 717. Now the 493 ms draw + swap is cut: 224 ms stall, 371 ms frame-1 work. Pass.
        r = run([frame1(V, 157, 209, 5, 413, 80), skip(43)])
        self.assertEqual(r["raw_ms"], 717)
        self.assertEqual(r["excluded_ms"], 493)
        self.assertEqual(r["app_ms"], 224)
        self.assertEqual(r["frame1_own_ms"], 371)
        self.assertEqual(r["failures"], [])

    def test_d0f196dc_real_logcat_passes(self):
        # The same run, replayed from its real logcat lines.
        r = run(D0F196DC_LOGCAT, pid=6197)
        self.assertEqual(r["raw_ms"], 717)
        self.assertEqual(r["excluded_ms"], 491)
        self.assertEqual(r["app_ms"], 226)
        self.assertEqual(r["frame1_own_ms"], 372)
        self.assertEqual(r["failures"], [])

    def test_fast_runner_layout_regression_still_fails(self):
        # Same fast runner, but first composition grows to 450 ms of layout. The cut draw + swap brings the stall under
        # 700 ms, so only the frame-1 check can catch it, and it does.
        r = run([frame1(V, 157, 450, 5, 413, 80), skip(66)])
        self.assertLess(r["app_ms"], 700)
        self.assertEqual(r["frame1_own_ms"], 612)
        self.assertEqual(r["failures"], ["frame 1 own main-thread work 612 ms > 400 ms"])

    def test_short_first_frame_draw_is_cut_too(self):
        # A first-frame draw under 700 ms used to count in full. It is cut now: the skip drops to 1000 - 650 = 350 ms, so
        # the worst stall is frame 1's own 400 ms of work. Frame 1's wait + work, 700 ms, still fails the 400 ms check.
        r = run([davey(V, 300, 400, 650), skip(60)])
        self.assertEqual(r["excluded_ms"], 650)
        self.assertEqual(r["unmatched_skips_ms"], [350])
        self.assertEqual(r["app_ms"], 400)
        self.assertEqual(r["frame1_own_ms"], 700)
        self.assertEqual(r["failures"], ["frame 1 own main-thread work 700 ms > 400 ms"])

    def test_unrelated_stall_counts_in_full(self):
        # A skip that matches a later Davey frame of its own is not the first-frame wait: nothing is cut from it.
        first = davey(V, 100, 150, 300)
        late_v0 = V + 5_000 * MS
        r = run([first, davey(late_v0, 900, 20, 5), skip(54)])
        self.assertEqual(r["app_ms"], 900)
        self.assertEqual(r["failures"], ["app-attributable main stall 900 ms >= 700 ms"])

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
        self.assertIsNone(r["frame1_own_ms"])
        self.assertEqual(r["failures"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
