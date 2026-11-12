# P1-TM-04 (Bot CRUD) — WIP handoff

Branch `p1-tm-04-wip`, cut from main @ fad00679 on 2026-11-12 (Hark). Stopped mid-ticket by the owner ("stop coding and testing, push what you have"). **Not on main. Not done. Nothing wired to the UI yet.**

## Done (on this branch)
Groundwork for the editor's A-team fields and the team tools. Nothing calls it yet.

| File | Change |
|---|---|
| `team/TeamSchema.kt` | team.db **v3**: `ALTER TABLE bots ADD COLUMN email TEXT NOT NULL DEFAULT ''` and the same for `portfolio`. Steps still only add. |
| `team/Models.kt` | `Bot` has two new fields at the end, `email` and `portfolio` (both default `""`). |
| `team/TeamStore.kt` | COLS, read (cols 24/25), upsert carry email + portfolio. |
| `team/TeamJson.kt` | botMap / bot() carry email + portfolio (missing keys read as `""`). |
| `team/Bot.kt` | `MANAGER_PORTFOLIO` (A-team PORTFOLIO, in our words); `manager()` sets email "" + that portfolio; `lockedDiff` also locks email + portfolio (A-team v2.16c). An existing Manager row from v2 is "repaired" once by `ensure()` (portfolio filled). |
| `team/BotThreads.kt` | `Change.Roster(botId)` + `rosterChanged(botId)`, so the Team screen can reload after a create/edit/delete. |
| `agent/engines/LocalEngine.kt` | `ToolSubset.SELF = {update_agent, note_team}`: every bot may use them whatever its groups (A-team BASE_TOOLS); a NEVER grant still removes them. `identity()` adds `Email:` / `Portfolio:` lines when set, and a `Mode: teammate …` line (gives BH's unused `mode` its meaning, BH-NOTE in do-not-port.md). |
| test `team/TeamSchemaTest.kt` | VERSION 3, bots columns + `email portfolio`, new `v2FileGetsEmailAndPortfolio`, steps may be `ALTER TABLE … ADD COLUMN … DEFAULT`, `steps(1)` range fixed. |
| test `team/ArgusUpgradeTest.kt` | version pin 2 → 3 ("two bot columns only"). |
| `tools/jvmtest/` | The local JVM test kit used here: compile/run scripts and stubs (see below). Not part of the app. |

## Not done
1. **`team/ManagerTools.kt`** (not started; design below).
2. **`agent/ToolRouter.kt`**: Group `"team"` (agents_list, agent_create, agent_update, agent_delete, update_agent, note_team), regex on bot / bots / team / roster / agent / hire / rename / role / portfolio / note for the team.
3. `JobTools.agent_status`: reuse it, don't duplicate. Extend it so no `agent` = the whole team (A-team).
4. `team/JobsRuntime.kt`: `TeamHooks.tools` becomes one object over JobTools + ManagerTools. Android glue: ChatLog = `Agent.messages.value` when `chatId == Agent.chatId.value`, else `Agent.teamSession(chatId).msgs` (user messages + `at`). TeamNotes = `Memory.add("Team note: …")`; Undo deletes by id through `Mind.dbOrWait()` (never `Memory.forget`, which matches substrings). Undo bar = `UndoCenter.offer`. A pending delete goes to a StateFlow the shell watches.
5. `ui/team/BotEditorModel.kt` (pure) + `ui/team/BotEditor.kt` (Compose): New bot sheet (Blank / From a template / Describe it), template picker (Specialists.builtIn via `BotRules.workerTemplate`, A-team demo bots, BH "Personal assistant" (mode personal) / "Teammate" (mode teammate)), Describe sheet (sends ARGUS: "Draft a new bot for me: <text>. Suggest its name, role, look and instructions, show me the draft, and create it with agent_create once I say yes."), editor sheet (Name, Label, Email, Portfolio, Instructions = SOUL via `TeamStore.saveSoul`; Look = 6 `BotOrb` faces × 8 `ArgusTokens.BOT_COLORS` + any colour via `ui/ColorWheel.kt ColorWheelPicker`; Reports to; Effort Segmented low/medium/high/xhigh → `engine.model.effort`; Mode Segmented personal/teammate). The Manager gets a read-only card, no Delete.
6. `ui/ChatScreen.kt`: `teamNav.newBot` / `teamNav.editSoul` (now toast "That comes in the next update"), a "Bot" row in the top bar's New sheet, and the Delete / Keep sheet for a pending `agent_delete`. Call `threads.rosterChanged` after saves.
7. Tests: `ManagerToolsTest`, `BotEditorModelTest`. Docs: 06 tick, overhaul Now/log/Deviations, STATUS.

### ManagerTools design (pure, `team/ManagerTools.kt`)
- `BotDraft(name, label, email, portfolio, instructions, face: Int?, color hex, reportsTo, effort, mode, toolGroups = {web, memory, jobs}, skills)`. Limits: name 60 (BotRules), label 60, email 120 (simple `x@y.z` or empty), portfolio 600, instructions 4000. Effort in low/medium/high/xhigh ("max" → xhigh). Mode in personal/teammate. Face 0..5 (names Orbit, Tilt, Steep, Pure, Twin, Polar in OrbFace order; the test checks them against OrbFace). Colours = A-team COLORS (the test checks them against ArgusTokens.BOT_COLORS). reportsTo is the Manager or an existing bot: not itself, no cycle.
- `TeamEdits(store)`: `create(draft, by)` = insert + saveSoul when there are instructions; `update(id, draft, by)` refuses the Manager and saves the soul only when it changed. The editor and the tools share these.
- `ManagerTools(team, jobs, chat: ChatLog, notes: TeamNotes, clock, onAsk(Pending?), onUndo(label, undo), changed)`. `run(chatId, botId, name, args)` returns plain text, failures start with `Error:` (JobTools style). Caller = botId ?: manager.
  - Only the Manager may use agents_list / agent_create / agent_update / agent_delete. Anyone else gets "only Argus can use …".
  - `agent_update` and `update_agent` refuse the Manager (locked). `update_agent` works on yourself, or with `for:` on a bot that reports to you; fields name / role / email / portfolio.
  - `agent_delete`: the first call sets pending `(chatId, botId, at)`, calls onAsk and answers "NOT deleted yet. Ask the user plainly … call again with confirm: true only after they say yes in their next message". With `confirm: true` it deletes only when the **first** user message after `at` matches A-team's YES regex (`^\s*(yes|y|yeah|yep|yup|sure|ok|okay|confirm(ed)?|do it|go ahead|delete (it|them|him|her)|please do|yes please)\b`, case-insensitive). If that message is not a yes, it clears pending and nothing is deleted. Pending lasts 25 min. `confirmByButton(botId)` / `keep(botId)` cover the card. Never the Manager. A domain bot is switched off, not removed (TeamStore.delete).
  - `note_team`: notes.add, then onUndo("Saved to team notes", remove).
- Source port: A-team `app/www/manager.js` t_agent_* (lines ~395–500), `chat.js` update_agent (~640), `guard.js` schemas (~483). Read them with `curl -s "https://gitlab.com/api/v4/projects/88676660/repository/files/app%2Fwww%2Fmanager.js/raw?ref=main"`.

## Tests
- **Last green run (before the final edits):** 174 run / 172 OK in one JVM run of the regression set. The 2 failures were the version pins, fixed afterwards in TeamSchemaTest / ArgusUpgradeTest. The set: TeamStore, TeamJson, TeamSchema, JobsQueue, DueWords, ATeamExport, RowStore, BotThreads, ArgusUpgrade, LocalEngine, TeamHooks, TeamModel, KitSpec, ArgusTokens, OrbGeometry, LucidePaths, ShellSpec, StepTool, ThreadModel, ChatDrawer, ManagerChats. The baseline on pristine main was 173/173.
- **Not re-run after the last edits:** the version-pin fixes, ToolSubset.SELF, the identity lines, `Change.Roster`. Re-run first.
- Never run here: SpecialistStoreTest (needs SpecialistMatch), LifeModelTest, SettingsAliasTest, ChatNavTest / BackRuleTest, TeamDbTest (Robolectric), shots, `node tools/check-do-not-port.mjs`, `node --test evals/test/*.test.mjs`, Gradle, CI, device.
- ManagerToolsTest and BotEditorModelTest don't exist yet.

## How to continue (commands)
```
# toolchain (not in the repo): kotlinc 2.0.21 + JDK 17
curl -sL -o kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip && unzip -q kotlinc.zip
# the compose plugin must be the Maven one; the jar bundled with kotlinc is refused:
#   org/jetbrains/kotlin/kotlin-compose-compiler-plugin/2.0.21 (Maven Central)
# test jars (Maven Central): junit 4.13.2, hamcrest-core 1.3, org.json 20240303, sqlite-jdbc 3.46.1.3, kotlinx-coroutines-core/test-jvm 1.8.1
# compose jars (Google Maven, classes.jar from the -android AARs): runtime/runtime-saveable/ui/ui-graphics/ui-unit/ui-text/ui-geometry/ui-util/
#   foundation/foundation-layout/animation/animation-core 1.7.5, material3 1.3.1, material-ripple 1.7.5, collection-jvm 1.4.4,
#   annotation-jvm 1.8.1, lifecycle-* 2.8.6, activity(-compose) 1.9.2, haze-android 1.0.2 (Maven Central); android.jar API 34
```
`tools/jvmtest/` has `env.sh` (paths: edit them), `c.sh` (plain compile), `cc.sh` (Compose compile), `t.sh` (JUnit run), `full.sh` (regression set: compile + run), `base.txt` / `ui.txt` / `ut.txt` (file lists), and `stubs/` (Settings, Playbooks, Web, Designs, Skills, AndroidControl, DevTools, ChatMeta, BuildConfig, R, GlassTokens, drawArgusArcs/Mark, ThemeMode, TeamGraph, CrashLog). These are test-only stand-ins; ChatStore / Specialists' deep deps snowball without them. Run `bash tools/jvmtest/full.sh` after fixing the paths in env.sh. Add new main files with `EXTRA=…` and new tests with `EXTRAT=…`.

Then: `node tools/check-do-not-port.mjs` (0 hits) and `node --test evals/test/*.test.mjs` (34 pass). When everything is green, land on main (commits API, titles end `[skip ci]`), then the docs commit.

## Deviations so far (add to overhaul.md Deviations when landing)
| What the plan said | What was done | Why / where to undo |
|---|---|---|
| (P1 tickets kept team.db at v2) | team.db v3 adds `bots.email` / `bots.portfolio` | The editor needs A-team's email and portfolio, and no column held them. `TeamSchema.V3` |
| Manager's portfolio (not specified) | Locked, set to A-team's PORTFOLIO line in our words; a v2 Manager row is repaired once on open | A-team locks name, role, email and portfolio. `BotRules.MANAGER_PORTFOLIO` |
| update_agent / note_team per bot | In every bot's subset whatever its groups (`ToolSubset.SELF`) | A-team BASE_TOOLS. `LocalEngine.kt ToolSubset` |
| Bot mode personal/teammate (BH: unused) | "teammate" adds one identity line (take handed work, share with note_team); personal adds nothing | do-not-port BH-NOTE. `LocalEngine.identity` |
| ATeamExport drops bot emails (P0-MG-01) | Unchanged; there is a column for them now | P1-MG-01 can keep them |
| (planned) team notes | AM memory lines prefixed "Team note: " until mem_scope (P3-AG-01) | P3-AG-02 can migrate by prefix |

Next unblocked ticket after P1-TM-04 (per 06): P1-TM-06 (routing, needs TM-05 ✓), P1-TM-07 (Desk, needs TM-05 ✓) and P1-MG-01 (needs MG-01 ✓ + TM-05 ✓) are all unblocked already.
