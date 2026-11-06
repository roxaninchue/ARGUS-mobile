# Handoff: ARGUS living 3D Home (branch `feat/argus-3d-home`) — IN PROGRESS

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
- **No local gradle / kotlinc** (Gaby's rule). Only reading + static checks: `glslangValidator` for the shaders,
  tree-sitter-kotlin syntax parse (`work/ktparse.py` in the Hark workspace; it has false positives on ~17 % of existing
  files, so compare before/after), and grep for every signature / call site.
- Push through the GitLab commits API (git over HTTPS doesn't work): Hark workspace `work/push_argus.py "<msg>"` diffs
  the local copy (`cmp/gl`) against the branch and sends create/update/delete actions.

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

## Left (file level)
1. Text that still mentions living backgrounds / themes: `ui/Views.kt` (look view desc), `agent/Commands.kt` (look),
   `composer/SelfMap.kt` (setting:wallpaper → remove, look desc), `agent/Awareness.kt` line ~170, `ui/SettingsSheet.kt`
   page subtitle, `agent/SelfKnowledge.kt` ("Theme" in the avatar menu), `app/blaze-docs/about.md`.
2. `ui/BackRule.kt`: drop `themePicker` from Shell + front().
3. Tests to update: BackRuleTest (themePicker, backToChat needles), BubbleTonesTest, ChatUi20Test, ChatUi20ShotsTest,
   StageShotsTest (`blazePalette(dark, null)`), ContrastTest (scene parts), PhoneContrastTest, ThemeEverywhereTest;
   delete LivingThemesTest, LivingLogicTest, ThemeScenesTest; new ArgusLookTest, ArgusHomeLogicTest, SpotlightGroupsTest,
   ArgusHomeShotsTest (Robolectric, Canvas mode), ArgusHomeWiringTest (source scans). Grep every test that scans an edited
   source file for needles (WaveB4NoLinuxTest, HideModels19Test, ThinkingLabelTest, ChatUi20Test, DesignNavTest,
   WaitRowTest, LazyStartTest, SpotlightSearchTest uses its own map).
4. androidTest: ArgusHomeCheck (scene live through states, 50 ms monitor, 700 ms limit, screenshots), ArgusSpotlightCheck
   (pull open/close ×3); LowRamSmokeTest + `f_argusHome` (gate count 6 → 7 in tools/smoke/run-smoke.sh). Replace
   HomeFocusCheck / HomeIntroCheck / Rc4ShotsCheck (old Home).
5. GitHub workflows: triggers for `feat/argus-3d-home` in build.yml, mobile-build.yml (+ test-signed APK artifact, + the
   new shots class), lowram-smoke.yml, home-focus-check.yml / home-intro-check.yml (→ the Argus checks); release-apk.yml is
   manual (workflow_dispatch).
6. Version 0.52.2 / 189 in `app/build.gradle.kts`; CHANGELOG.md + STATUS.md entries; README Home section; this file.
7. Draft MR "0.52.2: ARGUS living 3D Home + pull-down Spotlight (replaces Living Themes)".

## How to resume
1. Get the branch tree (GitLab API, project 87806466, branch `feat/argus-3d-home`) or the Hark workspace copy `cmp/gl`.
2. Continue the "Left" list top to bottom; after each chunk push with `work/push_argus.py` and update this file.
3. Before handing off: grep for `LivingThemes|LivingLogic|LivingBackground|ThemeTint|ThemeScenes|WeatherLook|ThemePicker|
   themeOn\(|ThemedScreen|screenGround|Look\.theme` across `app/src` (main, test, androidTest) — must be empty.

## Other branch in flight (not ours)
`feat/argus-identity-signing` (updated 2026-11-06 09:15) changes build.gradle.kts, both GitHub build workflows, README,
about.md and Update*Test. Expect merge conflicts in those files; resolve at MR time.
