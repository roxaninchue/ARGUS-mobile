# Handoff: ARGUS × Hark design (0.52.3 / versionCode 190)

Branch `feat/argus-hark-design` on GitLab `blazeinchue/BlazeMobile` (project 87806466), created from `main` @ b074f2ba
(0.52.2 / 189). Draft MR into `main`: "0.52.3: ARGUS × Hark design, space view, one-screen setup, no model picker".
Never merge it without Gaby; MR !26 (`feat/argus-identity-signing`, owns `applicationId`) is not touched.

## Goal (Gaby's decisions)

The ARGUS app takes the **Hark app's design** with ARGUS's own palette, background and icon:

- **Layout from Hark:** a top bar with a round button on the left, a centred **Home | Chat | Projects** segmented pill
  and the round avatar on the right; large rounded dark cards with **serif display headlines**; a floating **"Message"**
  composer pill at the bottom; a profile / settings page of **grouped cards with icon + label + chevron rows**.
- **ARGUS palette:** Obsidian #000000, Argus Blue #0056FF, Electric Blue #1286FF, Steel #9AA0B3, White #FFFFFF.
- **Background:** the Earth-from-space "space view" (`res/drawable-nodpi/argus_space.webp`), under a scrim in dark mode;
  the light mode is the light ARGUS ground with a soft blue limb.
- **App icon:** the metallic ARGUS mark (all densities, adaptive + monochrome, Play Store PNG).
- **Onboarding:** a welcome with the ARGUS logo and "Intelligence beyond the interface", sign-in only if not signed in
  (skippable), then **one setup screen** where everything downloads by itself with one overall progress bar.
- **Settings decluttered;** **model selection removed** from the UI (automatic routing, ModelChoice always Auto).
- Version **0.52.3 / 190**.

## The reference screenshots, in words

(Gaby's uploads `1000173702…`, `…703`, `…704`, `…705`; all from the Hark app, dark theme on a dark blue-grey gradient.)

1. **Projects / chat (…702):** top bar = round "≡" button, pill "Home | Chat | **Projects**" (selected segment is a
   white pill with dark text), round avatar "G". Below: a large rounded dark card with a serif headline ("ARGUS 3D Home
   handoff") and body text, then the chat: date separators, an image bubble, the user's bubbles (dark grey, right),
   "Read" receipts, and the floating "Message Hark" pill with + and mic at the bottom.
2. **Chat (…703):** the same bar with a search glyph on the left and **Chat** selected; assistant messages as dark rounded
   bubbles on the left, white pill action chips ("Connect Google" with the G logo) under some of them.
3. **Home (…704):** a "+" round button, **Home** selected; stacked large dark cards: "hark can do more" (serif) with a
   Google sign-in button inside, an action card ("Push the unpushed … to your GitLab branch"), a weather card with inner
   tiles (Mist 4°C, Sunset 6:06 PM, "Share your location" button), then a full-width pill "+ Create a new panel" and the
   Message pill.
4. **Profile / settings (…705):** no left button; the name "Gaby Alarcon" big, the e-mail under it; a plan card ("hark
   pro" in serif italic, "Upgrade" white pill, "Usage and plan ›"); a card with Profile, Scheduled Tasks, Accounts, Wallet;
   a card with Security, System Preferences, Submit Feedback, About; each row icon + label + chevron with hairlines; a
   floating "Search" pill at the bottom.

## What was built (file level)

### New: `app/src/main/java/com/blaze/assistant/ui/space/`
- `SpaceLogic.kt` (pure): `SpacePalette`, `SpaceTabs` (tabs, round button per tab, avatar initial, selected colours),
  `SpacePillFit`, `SpaceGreeting` (time-aware serif headline, next-hour refresh), `SpaceAnswer` (latest answer card),
  `SpaceHomeCards` (card order), `SpaceProfile` (rows → settings page ids), `SpaceSettings` (System Preferences rows,
  Back parents, the tree, pages removed from Settings), `SpaceOnboarding` (WELCOME → SIGN_IN → SETUP), `SpaceProjectRows`,
  `SpaceFeedback`, `SpaceTags` (test tags), `SpaceImage` (space image sampling).
- `SpaceTheme.kt`: `SpaceLook` (dark / light), `SpaceType` (serif headlines), shapes, `SpaceImageCache`, `SpaceBackdrop`,
  `SpaceCard`, `SpaceHeadline`, `SpacePillButton`, `SpaceChip`, `SpaceRoundButton`, marks.
- `SpaceTopBar.kt`: round button + segmented pill + avatar (tap = profile, long-press = light / dark), Private pill.
- `SpaceHome.kt`: `SpaceNav` (chat requests from MainActivity), `SpaceHomeState`, `SpaceHomeScreen`, progress bar.
- `SpaceSpotlight.kt`: Spotlight over everything (from the round button / the profile's Search pill).
- `SpaceProfile.kt`: `SpaceProfileScreen` (root of Settings), `SpaceSystemPage`, `SpaceFeedbackPage` (share sheet).
- `SpaceOnboarding.kt`: `ArgusLogo`, `SpaceOnboardingScreen`, `SpaceSetupScreen` (one bar + every item's status).
- `SpaceProjects.kt` (this session): `SpaceProjectsScreen`, the Hark-style Projects list and project page (same callbacks
  as the old ProjectsPane: create, open, chats, instructions, brief with its 600-token cap, rename / delete chat, delete).
- `SpaceLegal.kt` (this session): `LegalAssets` (assets/legal file list, reader, markdown → plain text).

### New: `setup/SetupPlan.kt` (pure) + `setup/SetupRunner.kt`
Everything that downloads (Linux, its tools, skills, backup brain, voice model, quick commands) as one weighted list;
order, Wi-Fi rules, back-off, retry, resume (only settled results are saved). `SetupRunner.start` from the onboarding's
setup step; **`SetupRunner.later`** (this session) is queued by `BlazeApp` after the first frame and starts what is left
30 s later (`SetupPlan.LATER_MS`) for people past onboarding (`SetupPlan.startLater`).

### Changed
- `ui/ChatScreen.kt`: SpaceBackdrop + SpaceTopBar on every tab, Home tab = `SpaceHomeLayer`, Projects =
  `SpaceProjectsScreen`, Spotlight layer, onboarding + setup sheet, avatar → `settingsPage = ""` (profile),
  `SettingsScreen(…, onTab, onSearch)`. This session: the shared-text effect used `navNow()` / `go()` before they were
  declared (a compile error); it now calls `SpaceNav.showChat()`.
- `ui/SettingsSheet.kt` (this session): `SettingsScreen(start, a11yOn, onOpenAccessibility, onDismiss, onTab, onSearch)`;
  root = `SpaceProfileScreen` under the top bar (no round button); pages under `BackTitle`; Back follows
  `SpaceSettings.PARENT` (a page opened directly closes Settings); new `system` and `feedback` pages; `ai` shows only
  `BrainCard`; `privacy` = Security + `BankModePage`; old ids `router` / `backup_brain` → `ai`, `advanced` / `settings` →
  the profile. Page lists renamed to match the rows (Profile, Accounts, Wallet, Security, Usage and plan…).
- `ui/Setup.kt`, `ui/SetupColumns.kt` (this session): SetupFlow / SetupRow / the permission checklist / SetupGateScreen
  removed; ComeBack, BrainCard, BrainStatus, PermissionsPage, UpdatesPage, AboutPage kept; About gets `LicencesSection`.
- `ui/SimplePages.kt` (this session): LookPage without the tilt switch and the "Home stays dark" sentence.
- `ui/Shell.kt` (this session): ProjectsPane removed (StatusChip, ProjectBadge, ProjectChatEmpty, ChatRow stay).
- `BlazeApp.kt` (this session): `StartLater.add("setup.SetupRunner.later")` (guarded by safe start).
- `MainActivity.kt`: notification / form taps → `SpaceNav.showChat()`.
- `agent/BackupBrain.kt`: `initialized()`. `composer/ModelChoice.kt`: always Auto. `composer/SelfMap.kt`,
  `SpotlightSearch.kt`, `SpotlightGroups.kt`, `ui/composer/ChatComposer.kt`, `SpotlightPanel.kt`: model picker gone.
- `app/build.gradle.kts`: 0.52.3 / 190. `app/blaze-docs/about.md`: Home and Settings map rewritten for the new UI.
- Resources: `drawable-nodpi/argus_space.webp`, `argus_logo.webp`, `ic_launcher_foreground.png`,
  `ic_launcher_monochrome.png`; `mipmap-*/ic_launcher(.round).webp`; adaptive XML updated; `ic_launcher-playstore.png`;
  `drawable/ic_launcher_background.xml` updated; `assets/legal/LICENSE.md` + `THIRD_PARTY_NOTICES.md` (copies of the root).

## Everything removed
- `ui/argus/` (ArgusGl, ArgusHome, ArgusHomeLogic, ArgusSceneView, ArgusSpotlight): the 3D orb Home, its GL renderer,
  pull-down Spotlight, parallax. (`ui/ArgusMark.kt`, `ui/ArgusLook.kt`, `ui/ArgusBrand.kt` stay.)
- Settings: `SettingsList`, `AdvancedList`, `AiPage` (brain switches), `ui/OfflineBrainSection.kt`,
  `ui/BackupBrainSection.kt`; the `advanced` and `backup_brain` pages (their prefs keep their defaults).
- Setup: `SetupFlow`, `SetupRow`, the `Need` checklist, `SetupGateScreen` (`setup/SetupGate.kt` stays: Permissions page,
  backup before the first run of a build).
- Model picker: ModelPill, ModelPicker, `SelfMap.MODELS`, `Act.PickModel`, `Kind.MODEL`, `Icon.MODEL`.
- `ProjectsPane` (Shell.kt); the "Tilt the Home scene" switch; `res/drawable/ic_launcher_foreground.xml`,
  `ic_launcher_monochrome.xml`, `mipmap-*/ic_launcher*.png` (replaced by the webp / nodpi PNGs, no duplicates).
- Tests deleted (they tested deleted code): `ui/argus/ArgusHomeLogicTest.kt`, `ui/ArgusHomeWiringTest.kt`,
  `ui/ArgusHomeShotsTest.kt`; androidTest `smoke/ArgusHomeCheck.kt`, `smoke/ArgusSpotlightCheck.kt`;
  `tools/smoke/run-argus-spotlight.sh`.
- **Not removed (on purpose):** the old Home board code (`HomeIntro.kt`, `HomeBoardUi.kt`, `HomeGridUi.kt`,
  `HomeRedesign`), per BUILDING.md: it is unreachable and its deletion is a separate, careful follow-up.

## Tests
- New: `ui/space/SpaceLogicTest` (21), `setup/SetupPlanTest` (9), `ui/space/LegalAssetsTest` (3),
  `ui/space/SpaceWiringTest` (11), `ui/SpaceShotsTest` (shots, 10), androidTest `SpaceHomeCheck`, `SpaceOnboardingCheck`.
- Changed: `BackRuleTest` (backToChat keys: `composer.closeSpotlight()`, `switchTab(ChatNav.HOME)`), `ContrastTest` (the
  space palette in both modes, cards laid over black and over the brightest scrimmed space view), `ThemeEverywhereTest`
  (Projects' switch lives in SpaceProjects; the chat's Look & feel menu item → the System Preferences › Appearance row and
  the avatar long-press), `ShotsTest` (settingsList = the profile page, onboarding = the setup screen, ProjectsPane →
  SpaceProjectsScreen), `MoviesLegalTest` (allows `assets/legal/THIRD_PARTY_NOTICES.md` only while it is identical to the
  root notices), `LowRamSmokeTest` (warm-up draws the space shell; `f_argusHome` → `f_spaceHome`; still 7 tests).
- Earlier in this branch: Blaze050ShotsTest, ComposerShotsTest, SpotlightSearchTest, SpotlightGroupsTest.
- No `@Ignore`, no skips, no `allow_failure`, no thresholds or gates changed (700 ms stall gate, 7 smoke tests, PhonePerfTest).

## CI (GitHub mirror `roxaninchue/ARGUS-mobile`, all workflows trigger on `feat/argus-hark-design`)
| Check | Workflow | What it verifies here |
|---|---|---|
| `apk` | Build APK (build.yml) | release unit tests + `:storeapi:test` + assembleRelease, shots (`ShotsTest*`, `SpaceShotsTest`), test-signed APK artifact `argus-hark-design-test-signed-<sha>` (throwaway key) |
| `build`, `build-without-youtube` | Mobile build | release APK (+ no-YouTube variant), release unit tests |
| `unit-tests` | Mobile build | every debug unit test (SpaceLogicTest, SetupPlanTest, LegalAssetsTest, SpaceWiringTest, …) |
| `shots` | Mobile build | Robolectric screenshots incl. SpaceShotsTest |
| `app-template` | Mobile build | skill template build (unchanged) |
| `smoke` | Low-RAM smoke gate | cold start + LowRamSmokeTest (7 tests, `f_spaceHome`), 700 ms gate |
| `focus` | Home focus check | `SpaceHomeCheck` (dark + light shell, every screen, screenshots in `space/`) |
| `intro` | Home intro check | `SpaceOnboardingCheck` (welcome → setup → Continue with real taps, `space-onboarding/`) |
Mobile build also uploads `apk-test-signed`; the smoke gate keeps `rel/smoke-signed.apk`. No release key is used anywhere.

## BUILDING.md notes (pre-empted)
- Memory settings untouched; Release excludes `**/*ShotsTest*` (covers SpaceShotsTest) and the compose-rule tests.
- SwiftShader first-frame stall: the smoke warm-up now draws the new shell itself (space image decode included).
- MemoryGate / name regexes untouched (no assistant-name change). PhonePerfTest bar untouched.
- No `assets/skills/**` change, so skill hashes and `store/catalog.json` need no resync.
- Rename fallout: every ArgusHome* / ArgusSpotlight* / ProjectsPane / SetupFlow / SettingsList / model-picker symbol was
  grepped across main, test and androidTest (none left outside comments).
- New pre-empted failure: `MoviesLegalTest.wholeAppNamesNoThirdPartyProviderRepo` failed on the shipped notices copy
  (verified locally with main's version of the test); fixed as described above.

## How it was checked here (no Gradle, owner's rule)
- kotlinc 2.0.20 / JDK 17, whole `main` + `storeapi` + `test` + `androidTest` against android.jar + Compose 1.7 jars,
  error set diffed against the same run on `main` b074f2ba: no new errors in main code; the remaining new errors are
  missing test libraries (androidx.test, robolectric, compose ui-test) in the new test files, the same kind main has.
- Run on JDK 17 with JUnit 4.13: SpaceLogicTest, SetupPlanTest, LegalAssetsTest, SpaceWiringTest, the space-palette
  ContrastTest case (45 tests) and BackButtonTest, IconOnlyTest, NoSquareTest, NoDeviceModelTest, MoviesLegalTest (10).
- Not run: anything Compose / Robolectric / on a device (CI's job), and the Compose compiler plugin's own checks.

## Left / how to resume
1. Let CI run the nine checks on the branch head; read failures as BUILDING.md says (first `e:` line).
2. Review the screenshots (shots branch / artifacts, `space/` and `space-onboarding/` from the device checks).
3. Try the test-signed APK on a phone (uninstall the real ARGUS first: different signature).
4. Follow-ups: delete the old Home board code; decide on Upgrade in the plan card (the profile shows the plan line only).
5. To push more: `python3 work/push_hark.py "message" [--dry]` from the ARGUS Mobile project (diffs `cmp/overhaul` against
   the branch, create / update / delete actions through the commits API; new paths only under its allow list).
