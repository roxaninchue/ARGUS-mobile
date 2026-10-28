# Blaze 0.48.0 RC5 — handoff (2026-10-28 08:40 CAT)

Work was stopped mid-way at the owner's request. This branch holds everything done so far. **None of it has been compiled or tested.**

## Repo, branches, SHAs
- Source of truth: GitLab `blazeinchue/BlazeMobile` (project id 87806466).
  - `feat/home-sequence` = **726d4779** (RC4, verified green). Left untouched.
  - `wip/rc5-handoff` = this branch: feat/home-sequence + one WIP commit (uncompiled RC5 work + this file).
  - MR **!18** (draft, feat/home-sequence → release/0.48.0): https://gitlab.com/blazeinchue/BlazeMobile/-/merge_requests/18 (not updated for RC5).
- Build mirror: GitHub `roxaninchue/Blaze-mobile` (public, scrubbed).
  - `app/home-sequence` and `check/home-sequence` = d2d0f113 (= GitLab 726d4779).
  - `smoke/home-sequence-e` = c9075b31 (gate run on RC4; app code and release dex identical to d2d0f113).
  - RC5 has NOT been pushed to the mirror.
- Local working tree (Hark workspace): `work/blaze` (plain git; local commit 87d16a2 = GitLab 726d4779).
- RC4 test APK: `work/out/blaze-0.48.0-rc4-c9075b31-test-signed.apk` (throwaway key CN=Blaze smoke).

## Build and push method
- There is no local JDK. Build, unit tests, screenshots and the gate all run in mirror GitHub Actions. GitLab CI is out of quota.
- `work/gl_push.py <from> <to> <branch> <start_branch|''> <msgfile>`: commits the local diff to GitLab through the commits API. The token is added on the wire. `git clone` over HTTPS is not authorised.
- `work/gh_push.py <from> <to> <parent-sha> <msgfile>`: creates a mirror commit through the git-data API. Shell credentials are `[{"provider":"github"}]`. It skips scrubbed paths (gateway/, keystore/, HANDOFF*, STATUS.md, some workflows).
- `work/setref.sh <branch> <sha>`: creates or force-moves a mirror branch. Pushing to:
  - `app/**` triggers Mobile build (`mobile-build.yml`: APK, unit tests, shots).
  - `smoke/**` triggers the low-RAM gate (`lowram-smoke.yml`).
  - `check/**` triggers the home intro/focus checks (`home-focus-check.yml`, which includes Rc4ShotsCheck).
- `work/gh_jobs.sh <run_id>`: lists job and step status.
- Test APK: the gate job signs the release APK with a throwaway key (`rel/smoke-signed.apk` in the artifact). Save it as `work/out/blaze-0.48.0-rcN-<sha8>-test-signed.apk`. A phone with the real Blaze must uninstall it first.
- Low-RAM gate (must pass on the final commit):
  - Release cold start with zero crash/ANR and no app-attributable main stall over 700 ms (`tools/smoke/app_stall.py`; the emulator's swiftshader first frame is excluded, which is the approved rule).
  - LowRamSmokeTest 6/6: browse 200 items with concurrent loads, details, 10 s video, music, 30 mocked tool-chat turns with DSML, main thread sampled every 50 ms.
  - The cold-start margin depends on the runner (see item f). If a run fails only on cold start on a slow runner, re-run it and report both results.

## Standing rules
- Never deploy to blazeinchue.pages.dev. Never touch blaze-site or blaze-releases. Never use the production signing key. All of these wait until the owner confirms. Use only the temp/throwaway test key.
- No videos or screen recordings; screenshots only. No Hark/JARVIS branding. The user-facing name is "On-phone brain".
- Stability first: 700 ms main-stall limit, no main-thread I/O.
- Model order: on-phone brain → Nous (:free) → Blaze cloud gateway. Never Jev, System One or NVIDIA models.
- If a loop fails more than 2–3 times on the same issue, stop and report.
- Full rules: `files/uploads/HANDOFF-2026-10-27-*.md` (Hark workspace). Progress log: `work/out/home-sequence-progress.md`.

## What RC4 delivered (726d4779)
- One Blaze look. The old chat screen is removed, and chat happens over the blurred Home (`ChatOverHome`).
- Floating Spotlight, the real account avatar, a 2-column dashboard and keyboard fixes.
- Cloud sync speed and CLI auto sign-in.
- Text-to-speech setup moved off the main thread.
- Checks: Rc4ShotsCheck and the HomeFocusCheck warm-up.
- Results: unit tests 2831/0 failures; 213 shots; gate PASS on attempt 2 (worst app-attributable stall 457 ms; attempt 1 failed at 795 ms on a slow runner with the same dex).

## Open items

### (a) Tools not executing in the new chat over Home — IN PROGRESS (partly coded, uncompiled)
**What happened.** Owner on the A12:
1. "can you control my phone?" → Blaze offered to set it up.
2. "okay!" → Blaze said "…I'll open the accessibility settings now." (no tool call).
3. "okay, do it!" → Blaze said "Opening accessibility settings for you now." Nothing opened and no tool card showed.

**Root cause so far (from reading the code; no device trace).** The new bar sends through the same `Agent.send` path as the old chat. The tool loop is not bypassed. What fails is routing, guards and display:
1. **Chat lane with no tools.** `agent/ChatLane.fits()` accepted "okay, do it!":
   - `smalltalk` matches "okay" and the text is under 40 characters, so the `needsTools` hit on "do it" is ignored.
   - Blaze's previous message ended with "now." rather than "?", so the "short reply to a question" rule did not fire.
   - The lite lane sends NO tools, so the model only said it was opening the page.
   - Lite replies skip every claim guard. The only exit is the `[[TOOLS]]` escape at `Agent.kt` ~L3497.
2. **No guard for "doing it now" promises.** On the full lane, "I'll open the accessibility settings now." had no tool call behind it, and nothing caught it:
   - `ClaimGuard` covers only view nouns in the past tense.
   - `ActionProof` / `ClaimCheck` look for past-tense claims tied to what the user's own message asked; "okay!" asks nothing.
3. **The new chat hides tool activity.**
   - `composer/ChatOverlayLogic.visible()` drops `Role.STEP` messages.
   - `ChatOverHomeContent` (ui/composer/ChatComposer.kt) shows only typing dots. It never shows `Agent.status`, `Agent.taskChip`, `StepPills.current` or step groups, so there are no "Using the browser…" chips.
   - It does not provide `RichSend.Local`, so choice buttons and forms inside replies do nothing.
   - It does not show follow-up chips.
   - Approvals, questions, secure forms and secret cards DO render: `WaitingCards` in ChatScreen.kt is passed as `cards` → `ChatOverHome(bottom=…)`, and the LaunchedEffect opens the chat when one is pending.
4. **Stage cards are invisible.** Stage cards (browser card, timers, mini players; `stage/StageStore`) are drawn only by `StageHost` inside the old chat sheet. That sheet is no longer composed (`if (!newLook) Box(...)` in ChatScreen.kt), so stage tools "succeed" but nothing shows.
5. **Not a cause:**
   - `Views.request` and `SelfSetup.openPage` collectors are outside the removed sheet.
   - `SelfSetup.open` / `SettingsJump` use `startActivity` with NEW_TASK and work from the foreground.

**Done on this branch (uncompiled):**
- NEW `app/src/main/java/com/blaze/assistant/agent/ActionPromise.kt` (pure): `consent()`, `offers()`, `promises()`, `nudge()`, `honest()`. The regexes were sanity-checked in Python against the screenshot texts.
- `agent/ChatLane.kt`: `fits()` returns false for a go-ahead after Blaze offered or announced an action, and for "do it / go ahead / go for it / proceed / carry on / try again".

**Next steps (not done):**
1. `Agent.kt` lite lane (~L3494, `if (lite) {…}`): also rerun on the full lane when `ActionPromise.promises(said).isNotEmpty()`.
2. `Agent.kt` final-reply checks (~L3700, where `calls` is empty and `!routine`):
   - If `turnTools` is empty and `ActionPromise.promises(shown)` is non-empty, nudge once with `RetryGuard.anchor(ActionPromise.nudge(p), lastUserText())`, add `Role.STEP` "Doing what I said", then `continue`.
   - If it still fails, use `ActionPromise.honest()` with `pendingFollowups = listOf(NudgePolicy.TRY_AGAIN)`.
   - Add a `promiseNudged` flag next to `proofNudged`.
3. Chat over Home (`ui/composer/ChatComposer.kt` + `composer/ChatOverlayLogic.kt`):
   - Add rows that include the steps between a user line and the reply: a compact "N steps · last step" chip that expands on tap.
   - Add a live work line next to the typing dots (`StepPills.current` for this chat, else `Agent.taskChip`, else `Agent.status`, else the last live STEP text), using the same logic as `WaitRow.line` in ChatScreen's MessageList.
   - Wrap the list in `CompositionLocalProvider(RichSend.Local provides (if idle) { composer.send(it) } else null)`.
   - Show `FollowupRow(m.followups)` under the last idle reply.
4. Stage: make `StageStore.enabled` false when `HomeRedesign.enabled` is on, so tools fall back to full views and activities (Browser, MusicActivity…). Then update the tests that expect the stage route by setting `HomeRedesign.enabled = false` in setup/teardown:
   - `StageToolsTest` L78–87
   - `StageBrowserCardTest` L56
   - `ViewsSkillsTest` L48
   - `PromptCacheBenchTest` L70
   The alternative is to render StageHost cards in the chat over Home.
5. Blaze reply avatar: `BlazeAvatar()` in ChatComposer.kt draws `R.drawable.ic_stat_blaze` (the white notification silhouette) on a blue disc, which is the "blue droplet" the owner saw. Replace it with the real logo, the same one as `BrandMark`/launcher (ic_launcher_frozen: glass drop in a gold halo on a dark disc): `Box(clip CircleShape, background Color(Mascot.ICON_BG)) { DuoEmblem(size*1.05f, animate = false) }`. Use the vector/Canvas version, not the 432 px PNG, so each bubble does not decode a bitmap on low RAM.
6. Tests to add:
   - `ActionPromiseTest` (screenshot texts → promise; offers / consent / "no" not consent).
   - ChatLane test with Robolectric + `Settings.init(app)`: "okay, do it!" after "I'll open the accessibility settings now." returns false.
   - Robolectric `SelfSetup.open(ctx, "accessibility")` fires `ACTION_ACCESSIBILITY_SETTINGS` (`shadowOf(app).nextStartedActivity`).
   - `ChatOverlayLogic` rows/work-line tests.
   - A ChatOverHomeContent screenshot with a live step chip plus a ConfirmCard (ComposerShotsTest pattern).
7. Smoke: `LowRamSmokeTest.e_thirtyToolChatTurnsWithDsml` is parsing only; it drives no UI. Extend it to launch a ComponentActivity (Rc4ShotsCheck pattern) with `ChatOverHomeContent`, feeding 30 turns of USER → STEP("Using the browser…") → BLAZE (CallScan-cleaned DSML). Assert no "DSML" in the rendered text and keep the 700 ms monitor. Keep `assertEquals(23, calls)`.
8. Possible remaining causes to check later: the on-phone brain or a Nous model not supporting tool calls, and the HedgeRace/FastLane "first answer wins" on tool turns (a known bug in the 10-27 handoff).

### (b) Onboarding interest picker: sub-topics at the top — NOT CODED (analysed)
- File: `ui/HomeBoardUi.kt`, `InterestPicker()` ~L1018–1052.
- Sub-topic sections are drawn in catalogue order (`Interests.catalogue.filter { it.id in chosen }`) under the chips. A newly tapped category's section can land far below the screen.
- Fix:
  - Keep a selection-order list (`var order by remember { mutableStateOf(initialChosen.toList()) }`). Add the id at the front on select; remove it on deselect.
  - Render sections in `order` (newest first) directly under the category chips.
  - Remember the `ScrollState` and, on select, `animateScrollTo` the first section's top (from `onGloballyPositioned`); use `scrollTo` when `!MotionTokens.enabled` (reduce motion).
  - Use `AnimatedVisibility` / `animateContentSize` only when motion is enabled.
- Put the ordering in a pure helper (e.g. `home/InterestOrder.kt`: `select`/`deselect`/`sections`) with a unit test, plus a screenshot test (HomeRedesignShotsTest pattern) after tapping a third category.

### (c) Spotlight "!" distro picker: background install with inline progress — NOT CODED (analysed)
- Today:
  - `ui/composer/SpotlightPanel.kt` L145: picking a not-installed distro calls `onAct(Act.OpenView("terminal"))`, which leaves for the Terminal view.
  - `DistroPicker` (~L201) is an M3 DropdownMenu showing `InstallMark(installed)`.
  - The installed list is `produceState` on IO keyed to `spotlightOpen` (`liveDistros()`).
- Engine: `terminal/Distros.install(id, StopToken)` (suspend, IO). Progress comes from `Distros.status` (`Fetch.mbLine`: "Downloading X… a of b MB", "Unpacking… n files", "Getting Python, git…"), with `Distros.installing` holding one install at a time. For Alpine, `Linux.install` / `Linux.status`. Success = `Distros.installed(id)`.
- Plan:
  - NEW pure `terminal/DistroInstallLogic.kt`: row state Idle / Queued / Running(line, pct?) / Done / Failed(msg), with `pct` parsed from "a of b MB".
  - NEW `terminal/DistroInstaller.kt`: a `StateFlow<Map<id, Row>>`, plus a CoroutineWorker (WorkManager unique work "distro_install", APPEND_OR_REPLACE so installs queue).
    - In the worker, call `setForeground(ForegroundInfo(id, notification with progress, FOREGROUND_SERVICE_TYPE_DATA_SYNC))`. The manifest already merges SystemForegroundService with dataSync. Wrap the call in try/catch.
    - Collect `Distros.status` into the row and the notification, throttled to about 1/s, never on main.
  - DistroPicker:
    - Name tap = select only (remove the OpenView).
    - The trailing download icon is its own clickable → `DistroInstaller.start(ctx, id)`.
    - While running: a 14 dp `CircularProgressIndicator` + "42%"; done → tick (merge with `installed`); failed → error text + "Retry".
  - The "Type a command" row subtitle should say "installing…" while a row is running.
- Tests: the logic unit test, plus a Spotlight screenshot with one row running and one failed.

### (d) Spotlight inline views fully functional — NOT STARTED
The owner wants every Spotlight inline preview to be a full working version of its view, scrollable: interactive terminal with keyboard, music, video, browser, settings. Today `InlinePreview` in SpotlightPanel.kt shows a limited preview (the terminal is the last command's output). Not analysed further.

### (e) Status-bar icons dark in light mode — OPEN
`MainActivity` uses `SystemBarStyle.auto`, so the icons turn dark on the always-dark Home when the phone is in light mode. The fix would be `SystemBarStyle.dark(TRANSPARENT)` while Home is shown, or always now that there is one look. This touches every screen; check the screenshots.

### (f) Gate margin depends on the runner — OPEN
Same dex: 457 ms pass vs 795 ms fail on a slow runner. The main app cost is first-frame layout at ~440–750 ms. Options: defer more first-frame cards, or reduce the work done in Home's first composition.

### (g) Legacy chat code still in source — OPEN
The old chat sheet is wrapped in `if (!newLook)` in `ui/ChatScreen.kt` (~L630–865): MessageList, Composer, HoldToTalkBar, PlusMenu, ViewsSheet, StageHost, themes. It is dead under the new look but still compiled and shipped. Remove it once (a) has moved the needed pieces (step chips, work line, RichSend, follow-ups, stage) into the chat over Home. Mind the tests that reference those composables.

## Files touched on wip/rc5-handoff
- NEW `app/src/main/java/com/blaze/assistant/agent/ActionPromise.kt`
- M `app/src/main/java/com/blaze/assistant/agent/ChatLane.kt`
- NEW `docs/HANDOFF-rc5.md`
