# P1 · SH workstream log (branch `overhaul/p1-sh`)

Shell & Design tickets of P1 (0.53.0 "One look, one team"), per `06-team-plan.md` §6.2. This file is the SH agent's
own bookkeeping; the integrator copies results into `06-team-plan.md`, `STATUS.md` and `overhaul.md`.

## How things are verified here (no Gradle, owner rule)
- **Pure files** (no Android imports) + their JVM tests: compiled and run with standalone kotlinc 2.0.21 + JDK 17
  (`junit.jar`/`hamcrest.jar` at repo root).
- **Compose glue**: compile-checked with kotlinc 2.0.21 + the Kotlin Compose compiler plugin
  (`kotlin-compose-compiler-plugin:2.0.21`) against the real Compose BOM 2024.09.00 jars (ui/foundation/material3/
  icons-extended/ui-test-junit4) and Robolectric's android-all 14 as `android.jar`. App symbols outside the files under
  check are stubbed in a scratch file (never committed), so this proves syntax, types and Compose API use, not the
  app's own call sites. Robolectric/shot tests are compile-checked the same way and run first on the owner's "build".
- **Source-scan tests** that compile standalone (IconOnlyTest, NoSquareTest, BackButtonTest, NoDeviceModelTest) are
  run over the whole tree after each ticket.

## Dependency on P0 (P0-SH-01 tokens, P0-SH-02 Lucide/BotOrb)
Not on `main` yet when P1-SH started (main = f9784861). So the kit reads colour/type/shape through ONE adapter,
`ui/argus/kit/KitLook.kt` (`kitLook()`), built from the 04 §2.1 values in `KitSpec.kt`; orbs are slots
(`orb: @Composable () -> Unit`) with a stand-in `KitOrb`; icons are Material Rounded vectors passed as parameters.
When P0 lands: re-point `kitLook()` to `ArgusColors`, swap `KitOrb` for `BotOrb`/`ArgusMark`, swap icons to Lucide.
No P0 file is written on this branch.

## Tickets

### P1-SH-02 Kit components — state: done (pending owner build)
- Commits: df05617d (pure rules + test), see below for the components commit.
- Files: `ui/argus/kit/KitSpec.kt` (pure: palette, sizes, radii, type scale, NeedYou, RowSpec, Pins, Chips, Mention,
  Attach, Progress, CloudChipRule, ButtonSpec), `KitLook.kt` (adapter, KitType, KitShapes, kitSurface/kitChrome),
  `ArgusButtons.kt` (ArgusButton Glass/Primary/Danger M/S, ArgusIconButton Glass/Flat/Avatar/Primary, ArgusField,
  ArgusSearch), `ArgusRows.kt` (KitOrb stand-in, LeadBadge, BotChatRow, PinStrip, MenuRow, SectionHead,
  SettingsGroup, SettingsRow), `ArgusBanners.kt` (NeedYouBanner, EmptyState, ProgressLine, CloudChip),
  `ArgusChips.kt` (ArgusChip, FilterChips, Segmented), `ArgusSheet.kt` (ArgusSheet, MentionPopup, AttachmentBar,
  SheetChoice). Tests: `KitSpecTest` (pure), `KitShotsTest` (Robolectric: 48 dp targets, merged TalkBack lines,
  light/dark x font 100/200 % shots).
- Tests run: KitSpecTest 13/13 pass (contrast AA for every text pair on ground, glass and sheet, both modes);
  kit + KitShotsTest compile clean against Compose; IconOnlyTest, NoSquareTest, BackButtonTest, NoDeviceModelTest pass.
- Notes: NeedYouBanner draws nothing when given null (P1 passes null; P2 wires the Inbox). CloudChip hidden until P7
  (`CloudChipRule.ENABLED = false`).
