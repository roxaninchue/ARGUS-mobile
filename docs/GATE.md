# Gate: why it was slow, and how it runs now

Finn, 2026-11-25. Gate is `.github/workflows/gate.yml` on the GitHub mirror `roxaninchue/ARGUS-mobile` (merge rule in
AGENTS.md). Read this before touching the workflow or the unit-test setup.

## Reading a Gate run
- A red run after **seconds** (8 to 15 s) is one of the cheap Node checks before any Java: the rename guard
  (the old brand name showed up in a file; AGENTS.md has the command), the do-not-port check, or the design JS tests. Run them locally
  first: the rename guard (AGENTS.md), then `node tools/check-do-not-port.mjs && node app/src/test/js/design-safe.test.mjs`.
- A red run after **about 5 minutes** is a compile error (app or test sources); the `e: file:...` lines are the
  annotations on the run page.
- A shard **cancelled at its time cap** (60 min now, 75 min before 2026-11-25) is not a failing test: either the slice
  is too slow or a test hangs. Find the class (the shard logs each test start) and add it to the quarantine list.
- The unauthenticated GitHub API allows 60 calls an hour per IP and runs out fast when polling. The run page and the
  job page (`github.com/roxaninchue/ARGUS-mobile/actions/runs/<id>`, `.../job/<id>`) are readable without a sign-in
  and show each step's conclusion and the annotations.

## Why it took 60 to 75 minutes
- The unit suite is about 590 test classes, ~4,200 tests (`app/src/test`, mostly `agent/` and `ui/`).
- Before 2026-11-25 `app/build.gradle.kts` set **no `maxParallelForks`** and the Gate was one job, so Gradle ran every
  class in **one test JVM, one after another**.
- GitHub's `ubuntu-latest` runner for a public repo has **4 CPU cores** (and 16 GB RAM). Three of the four sat idle for
  the whole test step.
- With `--no-daemon`, in-process Kotlin compile and slow Robolectric start-up, the serial suite took 60 to 75 minutes,
  right on the old `timeout-minutes: 75`.
- On top of that, **one test hangs**: `LifeShotsTest` stalls at `domainTileAsksToSetUpThatDomain` (found on
  `feat/phone-instances`, Gate #6 and #7, shard 0 stalled 58 min). A hang means the run never ends on its own and is
  cancelled at the cap with no result. That is the most likely cause of the 0.55.18 run (45495527052) being cancelled.
  `ToolSchedulerTest` also failed, but only after an earlier class leaked `Dispatchers.Main` into the same JVM.

## How it runs now (from MR !80, on main with Argus Phone steps 1 + 1a)
- Gate is a matrix: shards `0`, `1`, `2`, `3` and `q`, `fail-fast: false`, 60 min per job. Each shard is its own
  runner (its own 4 cores): Node checks (shard 0 only), Go libraries, compile, then its slice of the suite.
- **Sharding**: `-PtestShard=i -PtestShards=4` (in `app/build.gradle.kts`, `unitTests.all`) keeps only the test classes
  whose outer class name hashes to shard i.
- **One JVM per class**: `-PtestForkEvery=1` on the Gate, so a coroutine or `Dispatchers.Main` one class leaks can't
  fail the next class. Locally the default stays 100 classes per JVM.
- **Quarantine**: `app/src/test/gate-quarantine.txt` (one simple class name per line, with the reason). Shards 0-3 skip
  those classes (`-PtestQuarantine=skip`); shard `q` runs only them with a 10-minute cap and `continue-on-error`, so a
  known hang reports without blocking a merge. Fix a quarantined test, then delete its line.
- The run is green when shards 0-3 are green. Expected time per run: roughly a quarter of the old test step plus setup
  and compile.

## The other lever, not switched on: parallel forks on one runner
- `maxParallelForks = 4` (one test JVM per core) in `unitTests.all` would use all 4 cores of each shard's runner too.
- It is **off on purpose for now**: forks share one working directory, so tests that write fixed paths, temp databases
  or other shared files could collide and flake. Turn it on only after checking the suite for shared state.
- The test JVM heap is 6 GB; 4 forks would not fit in 16 GB, so set `maxHeapSize` per fork (about 2 to 3 GB) when
  enabling it.
