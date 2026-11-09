# Building ARGUS mobile (and not breaking CI)

This branch is built **only by CI** (owner rule: no local Gradle on `feat/argus-3d-home` / `feat/argus-hark-design`; on
`feat/argus-hark-design` a standalone kotlinc type-check and the pure tests are allowed, see the 0.52.3 notes at the end).
Every push costs a 15–25 minute CI round, so the cheapest build is the one you check before you push.

## Where builds run

| What | Where |
|---|---|
| Source of truth | GitLab `blazeinchue/BlazeMobile`, branch `feat/argus-hark-design` (0.52.3; `feat/argus-3d-home` was 0.52.2) |
| CI | GitHub Actions on the mirror `roxaninchue/ARGUS-mobile` (GitLab mirrors every branch within minutes) |
| GitLab CI | Not used (credits exhausted). `.gitlab-ci.yml` is left alone. |

Never push or merge on GitHub. Never merge draft MR !27. Never change `applicationId` (it belongs to MR !26).

**Every workflow has `concurrency: cancel-in-progress: true`.** A new push cancels the round that is running.
Batch fixes into one commit per round and don't push docs while a round you care about is still running.

## Toolchain (what CI uses)

- Gradle wrapper 8.7, Android `compileSdk`/`targetSdk` 34, `minSdk` 26, Kotlin `jvmTarget` 17, Compose compiler plugin.
- JDK: Temurin **17** in `build.yml` (Build APK); Temurin **21** in `mobile-build.yml`, `lowram-smoke.yml`,
  `home-focus-check.yml`, `home-intro-check.yml`.
- Go 1.26.x for the native libraries (`wgproxy/` browser VPN, `blazerouter/`), built before Gradle.
- Node for `app/src/test/js/design-safe.test.mjs`.

### Memory settings (do not remove)

`gradle.properties`:

```
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Xss8m -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.parallel=false
kotlin.compiler.execution.strategy=in-process
```

Without these `compileReleaseKotlin` runs out of memory on the 7 GB GitHub runners (fixed in d12934f8).
The other workflows also pass `-Dorg.gradle.jvmargs=-Xmx5g -Xss8m -XX:MaxMetaspaceSize=1g` and
`-Pkotlin.compiler.execution.strategy=in-process -Pkotlin.jvm.target.validation.mode=warning`.

## The CI jobs and the command each runs

| Check run | Workflow | Command (abridged) |
|---|---|---|
| `apk` | Build APK (`build.yml`) | `./gradlew testReleaseUnitTest :storeapi:test assembleRelease`, then screenshots with `BLAZE_SHOTS=1 ./gradlew testDebugUnitTest --tests '…ShotsTest*' --tests '…SpaceShotsTest'` |
| `build`, `build-without-youtube` | Mobile build | `./gradlew :app:assembleRelease` (+ release unit tests); the no-YouTube APK must contain no `mediaapps/youtube` classes |
| `unit-tests` | Mobile build | `./gradlew :app:testDebugUnitTest --continue` |
| `shots` | Mobile build | `./gradlew :app:testDebugUnitTest --tests` ShotsTest, Shots375Test, StageShotsTest, MoviesShotsTest, MediaRevampShotsTest, SecretCardsShotsTest, HomeRedesignShotsTest, ComposerShotsTest, Blaze050ShotsTest, SpaceShotsTest |
| `app-template` | Mobile build | builds the android-app-builder skill template |
| `smoke` | Low-RAM smoke gate | `assembleRelease assembleDebug assembleDebugAndroidTest`, then API 31 emulator, 1.5 GB RAM, 192 MB heap. Cold-start verdict (`tools/smoke/app_stall.py`): app-attributable main stall under 700 ms, and frame 1's own main-thread work at most 400 ms |
| `focus` | Home focus check | `assembleDebug assembleDebugAndroidTest`, `SpaceHomeCheck` on the low-RAM emulator (0.52.2: ArgusHomeCheck) |
| `intro` | Home intro check | same, `SpaceOnboardingCheck` via tools/smoke/run-space-onboarding.sh (0.52.2: ArgusSpotlightCheck) |

Note: **any** test source that fails to compile fails `unit-tests`, `shots`, `build` and `build-without-youtube`
together, because they all compile the unit-test source set.

## Reading CI

```
GET https://api.github.com/repos/roxaninchue/ARGUS-mobile/commits/{sha}/check-runs
GET https://api.github.com/repos/roxaninchue/ARGUS-mobile/actions/jobs/{job_id}/logs
```

Use an authenticated token (anonymous calls hit the 60/hour limit fast). In a log, search for
`e: ` (Kotlin compile errors), `FAILED`, `BUILD FAILED` and `tests completed`. Fix the **first** `e:` line;
the rest are usually cascades (one bad paren produced "Unresolved reference 'assertFalse'" and a type mismatch).

## Errors that stopped this branch, and how to avoid them

| Commit | What broke | Cause | Avoid it by |
|---|---|---|---|
| before d12934f8 | `compileReleaseKotlin` OOM | Gradle daemon + Kotlin daemon both on a 7 GB runner | Keep the memory settings above |
| before 4ad444cc | Release unit tests couldn't start Compose tests | `ui-test-manifest` missing on the release test classpath | Keep `ui-test-manifest` on the test classpath; Compose-only tests (`*ShotsTest*`, e.g. `SpaceShotsTest`, `ShotsTest`) are skipped in Release |
| 4ad444cc | 35 unit-test failures | Blaze → Argus rename: tests expecting old strings/commands | Commands accept both `Argus` and `Blaze`; when renaming, grep `app/src/test` and `app/src/androidTest` for the old string in the same commit |
| 4ad444cc | Skill hash / store catalog tests | `assets/skills/**` edited without resyncing hashes and `store/catalog.json` | Any change under `assets/skills/` → regenerate the hashes and catalog in the same commit |
| 93b65e51 | `compileDebugKotlin`: `ParsedChat` unresolved | A sweeping edit to `ChatJson.kt` deleted a class that was still used | Before deleting a symbol, `git grep -n '\bName\b'` across `app/src` (main, test, androidTest) |
| f2041477 | `compile*UnitTestKotlin`: `AuthReturnTest.kt:40` | A trailing `// comment)` swallowed the call's closing `)` | Never end a line with a `)` inside a comment; run the paren check below |
| 2c7ef81d | `Halo0459Test.memoryGateStopsSmallTalk…` ("hi Argus") | `BrandName.NAME` became "Argus" but `MemoryGate`'s small-talk regexes only knew `blaze`/`halo` | Regexes and word lists that match the assistant's name must include `argus` (grep `blaze\|` in `app/src/main`) |
| 2c7ef81d | `PhonePerfTest.perfAfterIsFasterAndAlwaysVerified` (Release) | Wall-clock p50 bar too tight for the shared runner (7.7 ms vs 7.0 ms) | Timing asserts in unit tests need headroom for noisy runners; prefer ratios with slack |
| 4ad444cc | `ArgusSpotlightCheck` gesture didn't take | Emulator under load drops a swipe | The check retries gestures; don't make on-device checks depend on one gesture |
| 10d5bd15 | Release cold start 706 ms app-attributable (373 ms the round before) | Slow runner (TotalTime 3633 vs 2230 ms) | Watch it; if it repeats, profile `BlazeApp.onCreate` and the first composition of the release build |
| 2c7ef81d | `LowRamSmokeTest.f_argusHome` / `z_noStallAnywhere`: main stalled 903 ms in "argus home (auto)" | First HWUI frame of the test process: SwiftShader compiled Skia's programs on the RenderThread (878 ms GL, Davey 1191 ms) and the first window draw held main until it completed; main's own work was ~115 ms. Not ART verification (that sampled stack was from cold start) | The in-process gate warms the renderer with the ARGUS Home itself before its monitor starts (same exclusion run-smoke.sh makes for cold start). A generic warm-up (10d5bd15) was not enough: 706 ms, the Home frame still spent 644 ms in GL. Read the Davey line's `IssueDrawCommandsStart`→`SwapBuffers` before blaming main-thread code |
| b5f84b74 | shots: `ComposerShotsTest.spotlightModels` timed out | 0.52.3 removed the model picker rows; the shots-only test still waited for "ARGUS Panoptes" (only `mobile-build.yml`'s shots job runs `ComposerShotsTest`, with `BLAZE_SHOTS=1`, so unit-tests stayed green) | When removing UI, grep `app/src/test` for its strings including `*ShotsTest*` files, not just the plain unit tests |
| 21e7f293 | shots: `SpaceShotsTest.profileLight` "component is not displayed" | `SpaceProfileScreen` draws the line it is given; the caller (`SettingsSheet`) passes `SpaceProfile.subtitle(...)`. The test passed the raw e-mail signed-out, then asserted the "Not signed in" subtitle | Tests should call a screen the way its real caller does; check the call site before asserting derived text |
| b5f84b74 | smoke: release cold start app-attributable main stall 1850 ms | 0.52.3 onboarding/space view (open; see below) | Read SUMMARY.md's cold-start line, the Davey timeline and the main-stack samples before changing code |
| 21e7f293/66ed074d | smoke: 2150 ms (worse) | Guess without the per-frame data: prefetching the space image + logo in `StartWarm` (before `warmClasses`) and skipping the root backdrop under onboarding. Made it worse | Avoid: blind prefetch on StartWarm's thread; it delays class warm-up and competes for the emulator's CPU. Get the frame data first |
| 2b84a204 (PASS, 559 ms) | smoke fix after 66ed074d | Per-frame data: first frame = ~670 ms main (636 ms layout: the hidden Home, top bar, chat and Spotlight composed under the opaque onboarding) + ~1440 ms waiting on software-GL. `app_stall.py` only cut that wait when frame 2 had a Davey line | Don't compose UI that a full-screen layer hides on first run; `app_stall.py` now cuts the soft-first-frame wait from the first unmatched skip (gate stays 700 ms). StartWarm prefetch removed |
| d0f196dc | smoke: release cold start 717 ms app-attributable (0 ms excluded) | Fast runner (TotalTime 1200 ms): frame 1 = 157 ms vsync to main start, 209 ms layout, 5 ms record, then 413 ms RenderThread GL + 80 ms swap with main blocked. GL under 700 ms is not the software first frame to `app_stall.py`, so the whole wait counts; per-runner-speed main work did not grow vs 2b84a204 | The first-run welcome is drawn alone in frame 1 (`FirstRunGate`), ChatScreen's setup composes from frame 2; MenuIcons rules lazy + cached, icons warmed off main after the first frame. Main work is only ~215 ms of the 717: on a fast runner the GL + swap wait (~490 ms) is most of the number |
| d0f196dc (gate rule, 2026-11-09) | Same run: the gate itself counted the emulator's draw as app stall | `app_stall.py` cut the main thread's wait on the first frame's draw + swap only when that draw took over 700 ms, so a fast runner was penalised for being fast | Gate changed (approved by Lukas): the first frame's draw + swap is always cut (d0f196dc replays at 226 ms, 491 ms excluded), and frame 1's own work (wait for main + layout + record) must be at most 400 ms (d0f196dc: 372 ms). 2b84a204's frame 1 (787 ms of main work) would fail that new check |

The deep-link scheme stays `blaze://` (any case) after the rename; don't rename it.

## Pre-push checklist (no compiler needed)

1. **One commit per round**, small, with every file it needs.
2. **Symbols**: for every class/function you removed or renamed, `git grep` finds no remaining use.
3. **Renames**: `git grep -in 'blaze'` in the files you touched; test strings updated in the same commit.
4. **Parens and braces**: for changed `.kt` lines, strip strings and comments and check `(`/`)` and `{`/`}` balance
   (a one-file Python check is enough; tree-sitter-kotlin works too but has false positives on ~17 % of files,
   so compare before/after rather than trusting a single run).
5. **Shaders**: `glslangValidator` (ES 300) on any changed GLSL.
6. **Assets**: changed `assets/skills/**` → hashes + `store/catalog.json` synced.
7. **Don't touch**: `applicationId`; old Home board code (`HomeBoardUi`, `HomeGridUi`, `HomeIntro`, `HomeRedesign`).
8. Push, then wait for **all nine** check runs before the next push (a new push cancels the running round).
9. A failure that survives two fix attempts: stop and report instead of guessing a third time.
10. Smoke used to cancel on a new push (66ed074d cancelled 21e7f293's smoke, so that fix was never judged). It now queues instead, but still batch fixes into one push.
11. Docs-only pushes (`*.md`, `docs/`) start no build: all app workflows have `paths-ignore` for them. A commit message with `[skip ci]` skips every workflow (use it for workflow/tooling edits that need no app round).
12. Changing `tools/smoke/app_stall.py`? Run `python3 tools/smoke/test_app_stall.py` first (seconds, no emulator; the smoke job also runs it first). Add a case with the new run's numbers.

### The cold-start gate rule: `tools/smoke/app_stall.py`, changed 2026-11-09

- **Before:** app_ms = worst Choreographer stall per frame, minus the main thread's wait on the first frame's RenderThread draw + swap only when that draw took over 700 ms (the slow-runner software first frame). Pass if app_ms < 700 ms. A fast runner's 400-500 ms draw counted in full (d0f196dc failed at 717 ms with ~215 ms of app work).
- **After:** the first frame's draw + swap wait (`IssueDrawCommandsStart` to `SwapBuffersCompleted`, main blocked) is **always** cut, once: from frame 2's Davey delay when it has one, otherwise from the largest unmatched Choreographer skip. Two checks must both pass:
  1. app-attributable main stall (after the cut) **under 700 ms**;
  2. frame 1's own main-thread work, `SyncQueued - IntendedVsync` (wait for main + layout + record, draw and swap left out), **at most 400 ms**.
- SUMMARY's cold-start line shows both numbers, `... main stall N ms of 700 (... X ms excluded = ...draw + swap), frame 1 own main-thread work M ms of 400 ...`; each failed check is its own FAIL line. `app-stall.json` lists them under `failures`.
13. Releasing a feature-branch build: `release-apk.yml` failed twice on v0.52.3 with `HTTP 403: Resource not accessible by integration` in "Create or update the release" (the repo's Workflow permissions setting is not the cause: the workflow sets `contents: write` itself). Until that is understood, publish by hand: download the `argus-hark-design-test-signed-<sha>` artifact from the Build APK run, then Releases, Draft a new release, the tag, attach the APK, pre-release, Publish.

## When the low-RAM smoke gate fails (fast path, one fix round)

0.52.3 took four rounds (~2 hours) because the first two fixes were guesses. Do this instead:

1. Read the cold-start lines of SUMMARY (GitHub step summary, also `smoke-out/SUMMARY.md` in the artifact): `app-attributable`, `excluded`, `frame 1 own main-thread work`, `frame 1: delay / own work / first frame draw + swap`, and the Davey line (`vsync to main start, layout, record, RenderThread GL, swap`).
2. Split the number: **app_ms = main work (layout + record + vsync-to-start) + whatever render wait was not excluded; frame 1 own = vsync-to-start + layout + record of frame 1.** If `excluded` is 0 while the Davey line shows RenderThread GL, the script missed the first-frame draw (66ed074d); if `layout` is large, it is first composition, and the 400 ms frame-1 check is the one that fails.
3. Replay before pushing: download `smoke-out`, run `python3 tools/smoke/app_stall.py smoke-out/logcat-release.txt <pid>` with your script change, and estimate the code change from the layout ms it removes. Only push when both estimates are under their limits (700 ms stall, 400 ms frame 1) with margin.
4. Usual cause of big first-frame layout: composing UI hidden under a full-screen layer (onboarding, a sheet). Don't compose it. Don't prefetch on `StartWarm`'s thread: it delays class warm-up (21e7f293 made it worse).
5. Batch every fix into one push, then let all checks finish (smoke no longer cancels on a new push, so a second push just queues).

## If you can build locally (other branches)

```
# JDK 17 (or 21), Android SDK 34, Go 1.26 on PATH
# first build the Go libraries exactly as the "Go libraries" step in .github/workflows/mobile-build.yml does
./gradlew :app:testDebugUnitTest --continue
./gradlew :app:assembleRelease
```

On this branch, don't. Let CI compile.

## Low-RAM smoke stall on the ARGUS Home (0.52.3, fix-up 6)

On 2c7ef81d `f_argusHome` failed at 903 ms against the 700 ms gate. The logcat shows `Skipped 56 frames` right after the
Home's first frame, and that frame's Davey line puts 878 ms between `IssueDrawCommandsStart` and `SwapBuffers` (RenderThread,
software GL) while main's part (`HandleInputStart`→`SyncQueued`) is ~115 ms; main's next frame starts 3 ms after
`FrameCompleted`. It was the first HWUI frame in the test process (steps a–e draw no UI), and the forced Canvas pass right
after it, same primitives, stayed clean. Fix-up 5 (10d5bd15) warmed with generic gradients and text: 706 ms, since the Home's first frame still
spent 644 ms in GL on its own programs. Fix-up 6 warms with the ARGUS Home itself (auto renderer, idle then the result card). The 700 ms gate is unchanged, and the Home's real first frame stays gated by the
cold-start check in run-smoke.sh.

## 0.52.3 (`feat/argus-hark-design`): what was checked before the first push

- **Local type-check (allowed on this branch):** kotlinc 2.0.20 / JDK 17 over `app/src/main` + `storeapi` + `app/src/test` +
  `app/src/androidTest` with android.jar and the Compose 1.7 jars, stub `R` / `BuildConfig`, no Compose plugin. Missing
  libraries (media3, work, lifecycle, robolectric, androidx.test …) give ~5,400 errors on `main` itself, so compare the
  error set with the same run on `main` (file + message, line numbers stripped); only new errors matter. It caught a
  local function used before its declaration in ChatScreen (`navNow()` / `go()` in the shared-text effect).
- **Pure tests run locally:** SpaceLogicTest, SetupPlanTest, LegalAssetsTest, SpaceWiringTest, the space-palette case of
  ContrastTest, and the source-scan tests (BackButtonTest, IconOnlyTest, NoSquareTest, NoDeviceModelTest, MoviesLegalTest)
  with stubs for the Compose-only pieces.
- **assets/legal/THIRD_PARTY_NOTICES.md** carries the vega-app attribution link, which `MoviesLegalTest` bans anywhere in
  `app/src/main`. The test allows exactly that file, and only while it is byte-for-byte the repo root's notices
  (LegalAssetsTest pins the copy too). When the root LICENSE.md or THIRD_PARTY_NOTICES.md changes, copy both again.
- **Renames:** ArgusHome* / ArgusSpotlight* tests and checks were replaced, not kept; `run-smoke.sh` still expects
  `OK (7 tests)` (`f_argusHome` became `f_spaceHome`), and the warm-up draws the space shell (same SwiftShader reason as above).
- **Setup downloads on CI:** SetupRunner only starts from the onboarding's setup step or (BlazeApp, 30 s after the first
  frame) for people already onboarded. A fresh emulator install is neither, so no smoke or device check downloads anything.
