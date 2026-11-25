# Gate: why it was slow, and how it runs now

Finn, 2026-11-25. Gate is `.github/workflows/gate.yml` on the GitHub mirror `roxaninchue/ARGUS-mobile` (merge rule in
AGENTS.md). Read this before touching the workflow or the unit-test setup.

## Reading a Gate run
- A red run after **seconds** (8 to 15 s) is one of the cheap Node checks before any Java: the rename guard
  (the old brand name showed up in a file; AGENTS.md has the command), the do-not-port check, or the design JS tests. Run them locally
  first: the rename guard (AGENTS.md), then `node tools/check-do-not-port.mjs && node app/src/test/js/design-safe.test.mjs`.
- A red run after **about 5 minutes** is a compile error (app or test sources); the `e: file:...` lines are the
  annotations on the run page.
- A run **cancelled at 75 minutes** is the job time cap, not a failing test. That is what happened on every long run
  before 2026-11-25.
- The unauthenticated GitHub API allows 60 calls an hour per IP and runs out fast when polling. The run page and the
  job page (`github.com/roxaninchue/ARGUS-mobile/actions/runs/<id>`, `.../job/<id>`) are readable without a sign-in
  and show each step's conclusion and the annotations.

## Why it took 60 to 75 minutes
- The unit suite is about 590 test classes (`app/src/test`, mostly `agent/` and `ui/`).
- `app/build.gradle.kts` `testOptions.unitTests.all` sets **no `maxParallelForks`**, so Gradle runs every class in
  **one test JVM, one after another**.
- GitHub's `ubuntu-latest` runner for a public repo has **4 CPU cores** (and 16 GB RAM). Three of the four sat idle for
  the whole test step.
- Gradle runs with `--no-daemon` and in-process Kotlin compile, and Robolectric classes start slowly, so the serial
  suite landed at 60 to 75 minutes, right on the job's `timeout-minutes: 75`. Any extra tests pushed it over and the
  run was cancelled with no result.

## What changed (2026-11-25)
- Gate is a **4-way matrix** (`shard: [1, 2, 3, 4]`, `fail-fast: false`). Each shard is its own runner: it does the
  Node checks, builds the Go libraries, compiles, then runs its quarter of the classes. The run is green only when all
  four shards are green.
- `tools/gate-shard.py K N` prints the Gradle `--tests` filters for shard K: every test class (a top-level class in a
  `.kt` file with an `@Test`) goes to exactly one shard, round-robin over the sorted names, so `agent/` and `ui/`
  spread evenly. `tools/gate-shard.py --list` prints the full list.
- Expected time per run: roughly a quarter of the old test step plus setup and compile, about 20 to 25 minutes
  (an estimate until the first sharded runs report).

## The other lever, not switched on: parallel forks on one runner
- `maxParallelForks = 4` (one test JVM per core) in `unitTests.all` would also cut the test step about 4x on one
  runner.
- It is **off on purpose for now**: the forks share one working directory, so tests that write fixed paths, temp
  databases or other shared files could collide and flake. Turn it on only after checking the suite for shared
  state, and then keep the matrix or drop it (both together would use 16 JVMs over 4 runners, which is fine).
- A test-JVM heap of 6 GB across 4 forks would not fit in 16 GB; set `maxHeapSize` per fork (about 2 to 3 GB) when
  enabling it.
