# Building ARGUS mobile (and not breaking CI)

This branch is built **only by CI** (owner rule: no local Gradle or kotlinc on `feat/argus-3d-home`).
Every push costs a 15–25 minute CI round, so the cheapest build is the one you check before you push.

## Where builds run

| What | Where |
|---|---|
| Source of truth | GitLab `blazeinchue/BlazeMobile`, branch `feat/argus-3d-home` (push here only) |
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
| `apk` | Build APK (`build.yml`) | `./gradlew testReleaseUnitTest :storeapi:test assembleRelease`, then screenshots with `BLAZE_SHOTS=1 ./gradlew testDebugUnitTest --tests '…ShotsTest*' --tests '…ArgusHomeShotsTest'` |
| `build`, `build-without-youtube` | Mobile build | `./gradlew :app:assembleRelease` (+ release unit tests); the no-YouTube APK must contain no `mediaapps/youtube` classes |
| `unit-tests` | Mobile build | `./gradlew :app:testDebugUnitTest --continue` |
| `shots` | Mobile build | `./gradlew :app:testDebugUnitTest --tests` ShotsTest, Shots375Test, StageShotsTest, MoviesShotsTest, MediaRevampShotsTest, SecretCardsShotsTest, HomeRedesignShotsTest, ComposerShotsTest, Blaze050ShotsTest, ArgusHomeShotsTest |
| `app-template` | Mobile build | builds the android-app-builder skill template |
| `smoke` | Low-RAM smoke gate | `assembleRelease assembleDebug assembleDebugAndroidTest`, then API 31 emulator, 1.5 GB RAM, 192 MB heap |
| `focus` | Home focus check | `assembleDebug assembleDebugAndroidTest`, `ArgusHomeCheck` on the low-RAM emulator |
| `intro` | Home intro check | same, `ArgusSpotlightCheck` |

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
| before 4ad444cc | Release unit tests couldn't start Compose tests | `ui-test-manifest` missing on the release test classpath | Keep `ui-test-manifest` on the test classpath; Compose-only tests (`ArgusHomeShotsTest`, `ShotsTest`) are skipped in Release |
| 4ad444cc | 35 unit-test failures | Blaze → Argus rename: tests expecting old strings/commands | Commands accept both `Argus` and `Blaze`; when renaming, grep `app/src/test` and `app/src/androidTest` for the old string in the same commit |
| 4ad444cc | Skill hash / store catalog tests | `assets/skills/**` edited without resyncing hashes and `store/catalog.json` | Any change under `assets/skills/` → regenerate the hashes and catalog in the same commit |
| 93b65e51 | `compileDebugKotlin`: `ParsedChat` unresolved | A sweeping edit to `ChatJson.kt` deleted a class that was still used | Before deleting a symbol, `git grep -n '\bName\b'` across `app/src` (main, test, androidTest) |
| f2041477 | `compile*UnitTestKotlin`: `AuthReturnTest.kt:40` | A trailing `// comment)` swallowed the call's closing `)` | Never end a line with a `)` inside a comment; run the paren check below |
| 2c7ef81d | `Halo0459Test.memoryGateStopsSmallTalk…` ("hi Argus") | `BrandName.NAME` became "Argus" but `MemoryGate`'s small-talk regexes only knew `blaze`/`halo` | Regexes and word lists that match the assistant's name must include `argus` (grep `blaze\|` in `app/src/main`) |
| 2c7ef81d | `PhonePerfTest.perfAfterIsFasterAndAlwaysVerified` (Release) | Wall-clock p50 bar too tight for the shared runner (7.7 ms vs 7.0 ms) | Timing asserts in unit tests need headroom for noisy runners; prefer ratios with slack |
| 4ad444cc | `ArgusSpotlightCheck` gesture didn't take | Emulator under load drops a swipe | The check retries gestures; don't make on-device checks depend on one gesture |

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

## If you can build locally (other branches)

```
# JDK 17 (or 21), Android SDK 34, Go 1.26 on PATH
# first build the Go libraries exactly as the "Go libraries" step in .github/workflows/mobile-build.yml does
./gradlew :app:testDebugUnitTest --continue
./gradlew :app:assembleRelease
```

On this branch, don't. Let CI compile.

## Open: low-RAM smoke stall on the ARGUS Home

`LowRamSmokeTest.f_argusHome` (and `z_noStallAnywhere`) fail on 2c7ef81d: main thread stalled 903 ms during
"argus home (auto)" against the 700 ms gate (every other phase stays under 75 ms). The sampled main stack shows ART
class verification at the time. This is a real regression in the new Home's first composition on a 1.5 GB device,
not a flaky test: don't raise the limit; profile the first frame (class loading / verification, mesh building on the main thread).
