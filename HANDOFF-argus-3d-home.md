# Handoff: ARGUS living 3D Home (branch `feat/argus-3d-home`) — CODE COMPLETE, NEVER COMPILED BY GRADLE, DRAFT MR OPEN

Target release **0.52.2 (versionCode 189)**, from `main` @ e8341100 (0.52.1 / 188). Owner: Gaby ("yes main and yea replace").
Merge request into `main` must be a **Draft**; never merge.

## Goal
Replace the old Home + the Living Themes feature with the ARGUS "living 3D" Home (portrait take on the reference board):
a real-time glossy black orb with the ARGUS "A", translucent metal rings turning slowly with blue light streaks, a glowing
pedestal with ripples, all reacting to state (idle breathing, listening + mic level, thinking, speaking). Dark obsidian
scene, Argus Blue #0056FF / Electric #1286FF, white + Steel #9AA0B3 text.

## Decisions (made, keep them)
- **The ARGUS Home replaces the old Home and the Living Themes.** No left nav rail.
- **Profile avatar top right** (opens the existing account / settings menu); ARGUS mark + wordmark top left.
- **Floating notch top centre**: drag down (spring, rubber band) or tap → the improved Spotlight sheet (built on
  composer/SpotlightSearch.kt, Fuzzy.kt, SelfMap.kt). Groups: Apps, Actions, Settings, Chats & memory, Web; Recent +
  Suggested before typing; keyboard focused on open; dismiss by pulling up, tapping the scrim or Back.
- **Rendering**: OpenGL ES 3 in a GLSurfaceView (no new dependency, procedural meshes + small shaders, additive halo as
  cheap bloom). Draws on request only (Choreographer), capped 60 fps, **30 fps on ≤ 4.5 GB RAM or battery saver**, paused
  when covered / in background. **Canvas fallback** when GLES3 is missing, on Android low-RAM-flag or < 2.5 GB phones, or
  after any GL/shader failure; **static** Canvas frame with reduce motion. Gyro parallax optional (Settings › Look & feel,
  off by default, never in battery saver / reduce motion).
- The Home scene is always dark (the brand scene); the rest of the app keeps light / dark.
- Ask from the Home bar → stays on Home, the orb thinks, a **result card floats over the pedestal** with the reply
  (ArgusAnswer); "Open in chat ›" or the bar's up chevron shows the chat. Questions / approvals / forms / secure fields,
  Spotlight's Chat / history / Ask, notifications and shares bring the chat up. Back from the chat lands on the Home.
- Old Home board code (HomeBoardUi / HomeGridUi / HomeIntro / HomeFocus / HomeSky … and HomeRedesign) is **left in the
  tree but unreachable** from navigation (deleting ~4k lines + their tests blind, with no compiler, was too risky). Follow-up.
- Old prefs (`look_theme`, `look_bg`, `look_music`, `look_still`, `look_photo`, `look_strength`, `nav_style`) are left
  harmless (not read). The Swipe navigation setting UI is removed (no Home under the chat any more).

## How CI works now (important)
- **GitLab CI credits are used up**: don't rely on / poll GitLab pipelines. `.gitlab-ci.yml` is left alone.
- Gaby syncs this branch to GitHub and runs the **.github/workflows** strict suite there (unit tests, screenshot tests,
  test-signed APK, low-RAM smoke gate, home checks). Workflows are being updated to trigger on `feat/argus-3d-home`.
- **No local gradle** (Gaby's rule). Only reading + static checks: `glslangValidator` for the shaders,
  tree-sitter-kotlin syntax parse (`work/ktparse.py` in the Hark workspace; it has false positives on ~17 % of existing
  files, so compare before/after), and grep for every signature / call site.
  2026-11-06 session: the PURE test classes were compiled and run with a standalone kotlinc 2.0.20 + JDK 17 (aarch64)
  against copies of the pure sources (+ tiny stubs for Settings / Views / CrashLog …), nothing written to the repo:
  ArgusHomeLogicTest + SpotlightGroupsTest + ArgusLookTest 49/49 OK, ArgusHomeWiringTest 8/8 OK. Compose / GL /
  androidTest files only got a kotlinc syntax pass (no syntax errors; unresolved symbols expected without the classpath).
- Push through the GitLab commits API (git over HTTPS doesn't work). 2026-11-06: `work/push_gl.py "<msg>" [paths]` in the
  BlazeMobile ARGUS 3D Home project workspace (local copy `work/src`, a git repo whose HEAD tracks the branch head,
  checked against `.remote_head` before every push).

## Done (pushed)
- `ui/argus/ArgusHomeLogic.kt` (pure): ArgusScene (state machine, per-state look, easing, rings), ArgusGreeting,
  ArgusFps (renderer + fps policy), ArgusPull (notch drag + spring maths), ArgusParallax, ArgusAnswer (result card).
- `ui/argus/ArgusGl.kt`: ArgusRenderer (GLES3), ArgusMesh (pure meshes), ArgusConfigChooser (4x MSAA when 60 fps).
  Shaders validated with glslangValidator (ES 300, compile + link).
- `ui/argus/ArgusSceneView.kt`: GL view with lifecycle + Choreographer frame cap; Canvas fallback `drawArgusScene`.
- `ui/argus/ArgusHome.kt`: ArgusHomeScreen, ArgusHomeBus (is the Home in front), ArgusDevice, tokens, prompt bar,
  greeting bubble, answer card. `ui/argus/ArgusSpotlight.kt`: the pull-down panel.
- `composer/SpotlightGroups.kt` (pure): grouping, phone apps, chat hits, suggestions, recents.
- Living Themes removed: deleted `ui/LivingThemes.kt, LivingLogic.kt, LivingBackground.kt, LivingThemesDraw.kt,
  ThemePicker.kt, ThemeSceneImages.kt`, `assets/theme-scenes/` (13 webp, ~880 KB). New `ui/ArgusLook.kt` keeps what
  stays (LookLogic colour maths / light-dark-auto / accents, Look accent, BubbleTones single ARGUS tone, LightDark).
  Rewired: Theme.kt (`blazePalette(dark, lookAccent)`), AppTheme.kt (ThemeSwitch = light/dark switch, no themes),
  TermPalette (`of(dark)`), terminal theme menu → light/dark, DuoGlow fixed colours, Contrast (SceneVeil gone),
  SimplePages (Look & feel = theme mode, accent, tilt parallax, Stage; Navigation choice removed), Music / Video /
  Gallery / Settings / Search / Projects / Design grounds, MediaUi, MoviesUi, HomeBoardUi.
- ChatScreen wiring: ArgusHomeLayer over the shell; Back → Home (BackRule homeRoot, swipeHome = Home visible);
  /home, Home tab, open_view home, Back out of Music/Video → Home; avatar menu "Themes" → "Look & feel".
  MainActivity: EXTRA_HOME always shows Home; notification / form intents show the chat.

## Done 2026-11-06 (pushed, commits 68dacf1a … see the MR)
1. ~~Text mentions~~, ~~BackRule themePicker~~, old tests updated / deleted (earlier sessions).
2. ArgusHome.kt: the greeting hour is a parameter (`hour: Int? = null`, pinned by tests) else read from the clock through
   `ArgusGreeting.hourOf / hourNow` and re-read at every full hour (`ArgusGreeting.msToNextHour`, half-hour zones ok);
   no `Calendar` read in the composable any more. `ArgusHomeTags` (ArgusHomeLogic.kt) on every part: root, scene, mark,
   avatar, notch, greeting, answer (+ dismiss), prompt (+ field, mic, send), scrim, spotlight (+ field, handle).
3. Unit tests: `ui/argus/ArgusHomeLogicTest` (scene, looks, easing, breath, rings, greeting + clock, fps / renderer
   policy, pull + spring, scrim, parallax, answer card, meshes, tags), `composer/SpotlightGroupsTest` (grouping, order,
   caps, Recent / Suggested, phone apps, chat lines + memory row, snippet, "!" / "@" modes, top row, recents store),
   `ui/ArgusLookTest` (mix, light / dark / auto, accents, parseAccent, LightDark, ARGUS tones, no theme machinery).
4. `ui/ArgusHomeShotsTest` (Robolectric NATIVE, BLAZE_SHOTS=1, 360x800 dp hdpi, Canvas mode, hour pinned): idle, morning
   greeting, listening / thinking / speaking (state swapped in one composition), answer card (+ tap opens chat), working
   card, Spotlight open (+ query "set" → SETTINGS group, handle closes), notch tap opens, reduce-motion still frame.
   Pixel asserts are deliberately loose (obsidian edge, blue beam under the orb). `ui/ArgusHomeWiringTest` (source scans
   of ChatScreen / MainActivity / BackToChat / SimplePages + BackRule's pure rule; old Home entry points unreachable).
5. androidTest: `smoke/ArgusHomeCheck` (GL forced, Canvas forced, device's pick; every state 3 rounds + result card;
   50 ms monitor, 700 ms limit, warm-up excluded; screenshots → files/argus) and `smoke/ArgusSpotlightCheck` (real
   injected touches: pull the notch, type, tap scrim; tap notch + Back; pull down + pull the field row up; 3 rounds,
   round 1 warm-up; screenshots → files/argus-spotlight) replace HomeFocusCheck / HomeIntroCheck / Rc4ShotsCheck
   (deleted, with tools/smoke/run-intro.sh → new tools/smoke/run-argus-spotlight.sh). LowRamSmokeTest `f_argusHome`
   (auto + Canvas, states, Spotlight open / query / close ×2); run-smoke.sh expects `OK (7 tests)`.
6. Workflows: build.yml (push trigger, ArgusHomeShotsTest in its Screenshots step, key rotation never on this branch,
   test-signed APK artifact `argus-3d-home-test-signed-<sha>`), mobile-build.yml (trigger, test-signed APK artifact
   `apk-test-signed`, ArgusHomeShotsTest in the shots job), lowram-smoke.yml (trigger), home-focus-check.yml
   (trigger, runs ArgusHomeCheck, uploads app-debug.apk), home-intro-check.yml (trigger, runs ArgusSpotlightCheck via
   run-argus-spotlight.sh, artifact spot-out), release-apk.yml (still manual; new inputs `artifact` + `prerelease`).
7. Version 0.52.2 / 189; CHANGELOG + STATUS entries; this file; Draft MR opened (never merge).

## Left
1. **First real compile** on the GitHub mirror (Gaby syncs; strict suite). Nothing in ui/argus, ChatScreen wiring, the
   shots test or the device checks has ever been through the Kotlin compiler with the real classpath: expect a round of
   compile fixes (likely spots: Compose API overloads in ArgusHome / ArgusSpotlight, the `avatar` lambda types in the
   tests, `SystemBarStyle.dark`, `Box(Modifier.testTag(...)) { avatar(...) }` inside the top Row).
2. Review ArgusHomeShotsTest PNGs; tighten the pixel asserts once real frames exist. Watch the low-RAM gate's new
   `f_argusHome` (7 tests) and the two device checks on the emulator; ArgusSpotlightCheck's injected drags (notch
   y = status bar + 25 dp, pull-up from the field row at x = 40 dp) may need tuning on the emulator.
3. README Home section (skipped to limit conflicts with feat/argus-identity-signing; README still describes no Living
   Themes, so nothing is wrong there, just missing).
4. Phone tests: A07-class 4 GB (GL at 30 fps), a GLES3-less / < 2.5 GB phone (Canvas), battery saver, Remove animations,
   tilt parallax, Back from chat / Music / Video, notification taps, shares.
5. Follow-up: delete the unreachable old Home board code (HomeBoardUi, HomeGridUi, HomeIntro, HomeFocus, HomeSky …,
   HomeRedesign) and its tests once the Argus Home is green.
6. `tools/ci/builds-repo/mobile-build.yml` (an older export copy) was left untouched.

## How to resume
1. Get the branch tree (GitLab API, project 87806466, branch `feat/argus-3d-home`; `repository/archive.tar.gz?sha=<head>`
   works where git over HTTPS doesn't) or the workspace copy `work/src`.
2. Continue the "Left" list top to bottom; after each chunk push with `work/push_gl.py` and update this file.
3. Before handing off: grep for `LivingThemes|LivingLogic|LivingBackground|ThemeTint|ThemeScenes|WeatherLook|ThemePicker|
   themeOn\(|ThemedScreen|screenGround|Look\.theme` across `app/src` (main, test, androidTest) — must be empty.

## Other branch in flight (not ours)
`feat/argus-identity-signing` (updated 2026-11-06 09:15) changes build.gradle.kts, both GitHub build workflows, README,
about.md and Update*Test. Expect merge conflicts in those files (this branch now also bumps versionCode / versionName in
build.gradle.kts and edits build.yml + mobile-build.yml); NOT resolved here, resolve at MR time with the owner.
