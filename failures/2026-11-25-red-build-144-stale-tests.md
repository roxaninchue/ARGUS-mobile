# Build #144 red: 14 tests still expected phone control (0.55.18, 2026-11-25)

Status: fixed on branch `fix/gate-green` (awaiting a green Gate, then the 0.55.18 build again)

## What the user saw
Build APK run 144 (run 45544954096, commit 2b411a42, 0.55.18 / vc 227) failed: no APK. Gate run 16 on `feat/phone-1b`
failed the same way, so `main` (37c39497) had a red test suite.

## Versions
Last good: 0.55.17 (build #142). Broken: 0.55.18 build commit 2b411a42 and main after !82. Fixed: `fix/gate-green`.

## How we found it
The app and the tests compiled. `:app:testDebugUnitTest` failed; the names are in the Gate annotations
(`/check-runs/<job id>/annotations`) and in the build's `buildlog-<sha>` artifact:
- From the phone-control removal (!81): ActionProofTest.messagingOnlyClaimsAfterTheCheck, CoreSkillsTest.pinnedBlockHasAllSixWithinBudget,
  PromptBudgetTest.skillsAreLookedUpNotPasted (core skills 6425 > 6400 chars), HaloReplayRequestTest.writePhoneReplayRequests,
  PlaybooksTest.everyBuiltInHasAValidFieldPlaybook (operator self-check down to 3), ToolCapTest x2 (`tap` after open_settings_page),
  ToolRegistryTest x2 (`tap`, 14 declared tools no longer defined), SetupChecklistTest (accessibility step), SettingsAliasTest (42 -> 41 pages),
  SpaceWiringTest (`SettingsScreen(start, a11yOn,`).
- From Argus Phone steps 1 / 1b (!82): PhoneInstancesTest.toolDefinitionsAreCatalogShaped (description over 400 chars),
  PhoneInstancesTest.recoveredNamesNeverOverwrite (`safeName("///")` gave `___`), PhoneUiFixesTest.pipIsWiredIntoBothScreens
  (Agent.kt now imports PhoneBrowser, the test looked for the full name).

## Root cause
Two merges went into `main` before their Gate finished: !81 on "compile green" (its Gate hit the 75-minute cap while
the suite ran serially) and !82 on "merge with skip ci". Many tests read the source as text (parity tests), so removing a
feature or renaming an import breaks tests the compiler never sees. Nothing ran the suite before the build commit.

## Fix
`fix/gate-green`: the tests now describe the app without phone control (each change says 0.55.18), ToolTruth.follow no
longer names `tap`, the operator playbook has a fourth self-check, the task-workflow pinned line is shorter, removed tools
stay declared in ToolRegistry (old bot rules and replays still classify) and are listed in the test's aliases,
`Workspace.safeName` trims underscores, the phone_instances description is under 400 chars.

## Guard
AGENTS.md "Before a merge or a build" (below in Lessons) and docs/GATE.md. The sharded Gate (4 shards + quarantine) now
finishes in about 25 minutes, so waiting for it is affordable.

## Lessons
- A merge waits for a finished, green Gate on the branch head. "It compiles" is not green: most failures here were tests.
- A build commit goes only on a `main` head whose suite is green (the Gate run of the merged branch head).
- Removing a feature: grep `app/src/test` for every removed tool name, class, settings id, string and count, and update
  those tests in the same branch.
- Never add a parameter after a function-type parameter that callers pass as a trailing lambda (step 2's
  `PhoneBrowserTools.run(..., cards)` broke `PhoneBridgeTest`; Gate run 18 caught it on the branch). Add new parameters
  before it.
