# Landscape audit: every screen in all rotations (15 Nov 2026)

Owner goal: "a real usable interface in all rotations". Sizes checked against a phone on its side (~851x393 dp, status
bar ~24 dp, camera cutout ~30 dp on the left or right) and a tablet / open foldable (~1280x800 dp). Upright phones
(~360-430 dp wide) must stay exactly as they were.

Status: **fixed** = changed in this round (pushed `[skip ci]`, not built) · **ok** = already usable, nothing to do ·
**other agent** = owned by the Living / chat-chrome agent · **onboarding agent** = owned by the onboarding agent ·
**open** = still to do.

Shared rules (pure, tested): `ui/common/WindowLayout.kt` + `WindowLayoutTest` (breakpoints 600 / 840 dp wide, short =
under 480 dp tall, readable width 720 dp, two-pane from 700 dp wide, grid columns that keep tile size, sheet caps).
Composables: `ui/common/WindowLayoutUi.kt` (`WindowUi.shape/short/sheetMaxHeight/sheetBody/WithGutter`,
`Modifier.cutoutSafe()`, `Modifier.readableWidth()`, `PaddingValues.withGutter()`). `ui/common/Orientation.kt` belongs
to the Living agent (its `isLandscape()`); this round does not touch it.

## App-wide

| Screen | Problem in landscape | Fix | Status |
|---|---|---|---|
| Manifest | No orientation locks (MainActivity handles `orientation|screenSize`; Video locks only while a video plays full screen, by design) | none | ok |
| Shell chrome (top bar + notch + full-width composer) on Team / Life / Desk / Projects | Stacked top bar (62 dp) + chips + composer (~70 dp) leave ~200 dp for the page | Living agent's rail (`LivingRail`, 0.2.1) only shows in Chat → Living; asked to extend it to every mode (see report) | other agent |
| ChatScreen pane | No display-cutout padding on the shell column | Each of my panes pads the cutout itself (`cutoutSafe`, consumption-aware, so it becomes 0 once the shell pads it) | fixed (per pane) |

## Sheets and dialogs

| Screen | Problem | Fix | Status |
|---|---|---|---|
| `ArgusSheet` (all ARGUS sheets) | ModalBottomSheet can grow under the status bar (M3 1.3.1 has no top cap); bodies without their own scroll (quick actions, New job, Describe, Delete) clip | Sheet capped at window − status bar − 8 dp; the body scrolls between the title and the pinned action row; `bodyMax` / `scroll` params | fixed |
| PlanSheet ("Here's what I'll do") | Fixed 360 dp list + title + switch + actions > 360 dp | List cap = `WindowUi.sheetBody(360.dp)` (360 upright, ~220 on its side), body scrolls | fixed |
| Job sheet | 520 dp body | `sheetBody(520.dp)`; on a wide page the job opens in a side pane instead (below) | fixed |
| Bot editor / Template / New bot / Describe / Manager card | 620 / 520 dp bodies | `sheetBody(...)`, body scrolls | fixed |
| Model picker | Unbounded list | `scroll = false` (its list scrolls under the sticky search); fits under the cap | fixed |
| Team / Life / Chat-drawer quick-action sheets, New sheet, Rename chat | Up to 6 rows + title > 360 dp | Body scrolls (ArgusSheet) | fixed |
| `+` menu (PlusMenu) / Views sheet (Shell.kt) | 480 / 320 dp lists above the composer | `sheetBody(480/320.dp)` | fixed |
| ArgusDialog | 360 dp wide card, short content | none | ok |
| Gallery dialogs (album picker 260 dp, info) | fit | none | ok |

## Modes and pages

| Screen | Problem | Fix | Status |
|---|---|---|---|
| Team (TeamMode) | Rows stretch 850 dp; avatars under the cutout | LazyColumn gutter (rows centred at 720 dp, whole width scrolls) + `cutoutSafe` | fixed |
| Life (LifeMode) | Cards stretch; domain tiles 2 per row at 850 dp | Gutter + cutout; domain tiles 2 upright, 3-4 when wide (`cardColumns`) | fixed |
| Desk (DeskMode) | Display title + chips eat ~110 dp of a ~200 dp strip | Short window: title and segment chips share one row; cutout; link pages at readable width | fixed |
| Desk → Jobs (JobsPage) | Rows stretch; the job opens as a tall sheet | Gutter (FAB stays at the rows' edge); **list-detail**: list 360 dp + detail pane when the page is ≥ 700 dp wide and not tall | fixed |
| Projects list / project page (SpaceProjects) | Full-width cards and fields | Readable width + cutout | fixed |
| Chat mode empty (ChatModeEmpty) | Orb + serif greeting + asks centred in a non-scrolling column clip | Scrolls when it doesn't fit, smaller orb on a short window | fixed |
| Chat history drawer | Opens on the left under the camera cutout | Start cutout inset | fixed |
| Chat thread / Living / composer | (owned elsewhere) | | other agent |
| Old 0.52 space Home (Good morning / Finishing setup / Try asking) | Retired by the owner 2026-11-15: never shown (ChatNav has no Home tab) | Onboarding agent | fixed |

## Settings (You page + every settings page)

| Screen | Problem | Fix | Status |
|---|---|---|---|
| SettingsScreen root (You page, profile, all ~45 pages: Account, Backup & import, Appearance, Voice, Memory, Skills, Routines, Security, …) | Already scrolls, but rows stretch 850-1280 dp and sit under the cutout | Root column `cutoutSafe().readableWidth()` (720 dp, centred): one change covers every page | fixed |
| Backup & import (BackupSection + ATeamBackupLive) | Same | Covered by the root | fixed |
| Sign-in card (AccountPage) inside Settings | Same | Covered by the root; the page itself is the onboarding agent's | fixed (root only) |

## Overlays and full-screen views

| Screen | Problem | Fix | Status |
|---|---|---|---|
| Spotlight (SpaceSpotlight) | Field and results 850 dp wide, under the cutout | Cutout + readable width | fixed |
| Search screen (Shell.kt) | Same | Cutout + readable width | fixed |
| Meeting room | Same | Cutout + readable width | fixed |
| Voice call (CallScreen) | Header + 240 dp orb + caption + 76 dp buttons need ~600 dp: the buttons fall off a 360 dp screen | Short + wide: name and orb on the left, caption / cards / buttons on the right (scrolls) | fixed |
| Gallery (GalleryScreen) | Fixed 3 columns → 280 dp tiles, one row visible; albums fixed 2 | Columns scale with width (`gridColumns`, pinch still sets the upright count); albums 2 → up to 6; cutout | fixed |
| Gallery viewer | Full-bleed pager, bars inset | none | ok |
| Glass window (links), Assist panel, Stage, Design studio | Full-bleed / own BoxWithConstraints sizing; Assist list already compact | none | ok |
| Setup progress / onboarding steps (SpaceOnboarding) | Language and Welcome steps don't fit 360 dp (Welcome has no scroll) | Onboarding agent: Language splits heading + Continue / list when short; Welcome scrolls (min-height column, upright unchanged); Language, Welcome, Sign-in cutout-safe and readable width | fixed |

## Separate activities

| Screen | Problem | Fix | Status |
|---|---|---|---|
| Music: now playing | Full-width square art box (~800 dp tall) pushes the controls a screen away | Short window: art box 60 % of the height (art stays square inside) | fixed |
| Music / Video: store & search grid (MediaUi) | Fixed 2-3 columns → giant tiles | Columns scale with width | fixed |
| Music library / Video library grids | Video uses `GridCells.Adaptive` already | none | ok |
| Video player | Locks to the video's orientation while playing (by design) | none | ok |
| Browser (BrowserActivity) | Has side docks (LEFT / RIGHT toolbar with side insets) and systemBarsPadding | none | ok |
| Phone browser, Site apps, Terminal, Camera | Full-bleed web view / terminal view / camera preview with configChanges | none | ok |

## Still open

- Music now-playing as a true two-pane (art left, controls right) on its side; this round only caps the art.
- Gallery viewer bars and Video store hero (230 dp) are fine but not tuned for 1280 dp.
- Shell chrome on Team / Life / Desk / Projects in landscape: the other agent's rail (requested).
