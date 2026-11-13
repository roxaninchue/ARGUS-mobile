# Building ARGUS clean, first try

Owner rule (Finn, 13 Nov 2026): every agent follows this before pushing a commit that will build. A red CI run costs about 25 minutes and the owner's trust. Every red run so far came from the **release unit tests**, never from the Kotlin compile of the app itself.

## 0. When a build runs at all
- CI runs on GitHub Actions on the mirror `roxaninchue/ARGUS-mobile`, which is synced from GitLab main.
- It runs only on a commit **without** `[skip ci]` that touches a non-`.md`, non-`docs/` path. Markdown and `docs/` changes never build.
- Build only when the owner says "build". Every other commit title ends with `[skip ci]`.
- Check before you push: a commit you meant as `[skip ci]` but forgot the tag on **will** build. Run 43472970210 (4a04c56b) started this way without an order.
- To build a docs-only change, touch a non-md file (for example a comment in `app/build.gradle.kts`).

## 1. Pre-flight: what has broken CI before, and the check for each
| Trap | Seen in | Check before pushing |
|---|---|---|
| A plain JUnit test touches `Settings`, a `Context` or an Android API (Settings needs a Context under Gradle, so the test throws) | ModelPickTest (run 43472970210), TeamStoreTest.delegateToSpecialistsStillWorks (run #44) | `grep -n "Settings\.\|Context\|getSystemService" <every new or changed test>`. Assert on in-memory state instead (e.g. `ModelPick.current.value`), or make it a Robolectric test. Main code that reads Settings must wrap it in `try { } catch (_: Throwable) { }` so pure tests can call it |
| Source-scan tests: tests that grep main sources for an exact needle string (SpaceWiringTest, ToolCapTest, BrainRaceTest, ToolTurnRaceTest, ChatOverlayLogicTest, RequestBudgetTest, ReliabilityP2Test, SecurityP4Test, WaveB4NoLinuxTest, ThemeEverywhereTest…) | SpaceWiringTest ×4 (run #44) | Before you edit a line in any main file, `grep -rn "<distinctive text from that line>" app/src/test` and keep every needle true. Prefer adding a new line to rewriting a needled one |
| Banned owner-name strings | OwnerName19Test (run #44) | Never write the names that test bans, not even in examples, test data or comments. Use neutral names (`nova@hitch.io`, "Rhea") |
| Version pins in tests | SpaceWiringTest version pin | A version bump updates `app/build.gradle.kts`, CHANGELOG, STATUS, about.md **and** any test that pins the version (`grep -rn "versionCode\|0\.5[0-9]\.[0-9]" app/src/test`) |
| Agent.kt growth cap | LocalEngineTest (≤ 20 added lines) | `git diff --stat` on Agent.kt; seam use is recorded in overhaul.md (9/20 as of 13 Nov) |
| do-not-port | tools/check-do-not-port.mjs | `node tools/check-do-not-port.mjs` must show 0 hits |
| A-team evals | evals/ | `node --test evals/test/*.test.mjs` must pass 34/34 |
| Low-RAM smoke contract | tools/smoke | Don't touch LowRamSmokeTest (exactly 7 tests, STALL_MS 700), and never add `@Ignore` or `assume` |

## 2. Run the tests the way CI will
**Best: local Gradle.** If an Android SDK and Gradle are available, run `./gradlew testReleaseUnitTest` (CI's `build` job runs the release unit tests) or at least `testDebugUnitTest --tests '<packages you touched>.*'`. Known sandbox workarounds (P2-SF-01/02):
- On aarch64, override aapt2 with an aarch64 build.
- Compile Kotlin in a Kotlin daemon with `-Xmx3g`, because the in-process compile OOMs at 1.5 GB.
- Robolectric tests fail on Linux aarch64 ("native runtime is not supported"). That is the environment, not the code; they run on CI (x86).

**Otherwise: the plain-JVM path** (toolchain in the Hark project workspace `work/tc`, `. env.sh`):
1. Compile every new or changed **test** with kotlinc, using the main sources it needs plus `tc/stub/*.kt`, then run it with JUnit from `app/` with `src/test/resources` on the classpath. If a test only passes because a stub returns the default where the real `Settings` would throw, it will fail on Gradle; see trap 1.
2. Compile and run every **source-scan** test that reads a file you changed. Most compile alone: `kotlinc <Test>.kt BrandName.kt`, then run from `app/`.
3. Compile Compose files with `bash tc/compose.sh` (glue stubs in `tc/cstub`).
4. Run `bash tc/fullc.sh app/src/main/java <log>` and compare its error set (path and message, line numbers stripped) with the same run on unchanged main. There must be no new errors. The baseline is about 5,120 errors, all from libraries missing in the sandbox.

## 3. Push and watch
1. Make one commit without `[skip ci]`. Its title says what is built.
2. Find the run with `curl -s "https://api.github.com/repos/roxaninchue/ARGUS-mobile/actions/runs?per_page=3"`, match `head_sha`, and poll its `status` every 30 s. It takes about 20 to 25 minutes.
3. Job logs need a GitHub token (403 without one). Job names and conclusions are public: `/actions/runs/<id>/jobs`. On red, reproduce the failure locally with steps 1 and 2 above instead of guessing.
4. Artifacts: `blaze-<sha>` (APK) and `shots-<sha>`. Download them through `https://nightly.link/roxaninchue/ARGUS-mobile/actions/artifacts/<artifact-id>.zip`.
5. The CI APK is unsigned. For install testing only, sign it with apksig (from maven.google.com) and a throwaway debug keystore, using v2 and v3 signatures, and check that `ApkVerifier` reports verified=true. Never use or commit the release keystore.

## 4. After a build
- Log it in overhaul.md (the Handoff log, plus the Status "Now" line) and in STATUS.md: run id, sha, jobs, and what was not run (device, Robolectric shots not in the Screenshots step).
- If a build started without the owner's "build", add a row to the Deviations table.
