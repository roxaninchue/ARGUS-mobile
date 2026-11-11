# P3 execution plan — Souls & memory (0.55.0)

> Working plan for the P3 session started 2026-11-11 22:50 CAT (Hark, P3 engineer, at Finn's request). Scope stays `06-team-plan.md` §6.4. Same owner rules as P0 (`P0-HANDOFF.md` §3): no Gradle build / CI / release / version bump until "build", every commit title ends `[skip ci]`, never alter the shared `memories` table (side tables only), `Agent.kt` untouched by P3.

## Owner answers (Finn, 2026-11-11)
- D1–D12 at the plan's recommended answers. **D6:** Automatic models everywhere; per-bot **Depth** + optional **own Gemini key**; **no model-id / provider picker anywhere** (P3-SH-01 Engine tab has neither).
- a-team: P3-MG-01's "Export for ARGUS" goes on branch `overhaul/p3-export-for-argus` in `blazeinchue/a-team` (never its `main`), MR left open.

## Branching
P0 is landing on `main`, P1 on `overhaul/p1*`, P2 on `overhaul/p2*` at the same time, so P3 never commits to `main` or to their branches.
- Integration branch **`overhaul/p3`** (cut from `main` f9784861). One branch per ticket **`overhaul/p3-<ticket>`** (e.g. `overhaul/p3-ag-01`), cut from `overhaul/p3`, merged back into it.
- The GitLab token here can commit (REST commits API) but **cannot merge MRs and has no git-over-https**, so "merge" = the same tree change committed onto `overhaul/p3` with title `Merge overhaul/p3-<ticket> into overhaul/p3 [skip ci]` (ticket branches keep their own commit). Earlier-phase branches are merged the same way: a local 3-way merge (base = the real `merge_base`), the result committed onto `overhaul/p3` naming the merged SHAs. A later git merge of `overhaul/p3` into `main` sees identical hunks on both sides and resolves them cleanly; anything else is listed below.
- Merged in so far (see the table at the end for SHAs): `overhaul/p1-tm`, `overhaul/p1-sh`. Re-checked between waves.

## Verification without a build (owner rule: no Gradle, no CI)
- Toolchain in the sandbox: Temurin JDK 17.0.21 (aarch64) + standalone kotlinc 2.0.21; test classpath = `junit 4.13.2`, `hamcrest-core 1.3`, `org.json 20240303`, `sqlite-jdbc 3.46.1.3` (all the repo's own test dependencies, from Maven Central).
- **Pure files + their JVM tests are compiled and run** with kotlinc (`kotlinc <pure srcs> <tests> -cp … -d out && java … org.junit.runner.JUnitCore <Tests>`); blaze.db tests run on sqlite-jdbc through the repo's `store/JdbcSql`, exactly like `BlazeDbTest`.
- `team/Models.kt` (P0-TM-01) is **not on any branch yet**: P1-TM compiles against a scratch copy of 05 §9.1, and so does P3 (never committed). After P0 lands, every test is re-run against the real file.
- Android / Compose glue (Mind/Memory wiring, Cloud.kt, screens) is checked by hand; Robolectric / shots tests are written to the repo's patterns and run first on the owner's "build".

## Waves
| Wave | Tickets | Why |
|---|---|---|
| 1 | P3-AG-01 scopes · P3-AG-03 SOUL.md · P3-AG-06 ModelPolicy · P3-AG-02 migration · P3-AG-04 projections + forget · P3-AG-05 learn | pure/core, depend on P1-TM-01 (merged) and P0 seams |
| 2 | P3-API-01 sync · P3-MG-01 import (+ a-team export) | need AG-01/AG-02 |
| 3 | P3-SH-01 Bot profile · P3-SH-02 What the team knows | need AG-03/04/06 + P1 kit |

## Decisions taken here (plan-consistent; recorded for reconciliation)
- **mem_scope guard column.** 05 §9.2 DDL + `mem_created` (equals `memories.created`, the `mind_mem` trust rule) so a `memories.id` reused after a Desktop/CLI delete never inherits a private scope. Rows are pruned on open. `memories`, its triggers/FTS and `user_version` are byte-identical (MemScopeTest.desktopSchemaIsUntouched + BlazeDbTest unchanged).
- **Default scope** for a line with no row: pinned → `user:core`, else `team` (05 §9.2 migration rule), applied at read time so Desktop-written lines are visible at once; P3-AG-02 writes the rows.
- **A-team kinds → scopes:** name/style/rule → `user:core`; person → `user:people`; place → `user:places`; date → `user:dates`; goal → `user:goals`; project → `user:projects`; preference → `user:preferences`; note → `user:notes`.
- **Private lines** (sensitivity 1, and every line in a private domain health/money/admin/health:mind) are read only by their owner and explicit `share:` tags; the Manager sees counts only (`MemScopes.hiddenCounts`).
- **Wiring seam.** Per-bot prompts are assembled through P0-AG-01 `TeamHooks` (not on any branch yet); P3 exposes pure builders (`MemScopeStore.promptBlocks`, `PromptParts.forBot`) that TeamHooks calls. `Agent.kt` is not touched by P3.

## Status
| Ticket | State | Branch / commit |
|---|---|---|
| P3-AG-01 | in progress | `overhaul/p3-ag-01` |
| P3-AG-03 | wave 1 | |
| P3-AG-06 | wave 1 | |
| P3-AG-02 | wave 1 | |
| P3-AG-04 | wave 1 | |
| P3-AG-05 | wave 1 | |
| P3-API-01 | wave 2 | |
| P3-MG-01 | wave 2 | |
| P3-SH-01 | wave 3 | |
| P3-SH-02 | wave 3 | |

## Merges from other branches into `overhaul/p3`
| When (CAT) | Branch @ SHA | Result on overhaul/p3 | Notes |
|---|---|---|---|
| 2026-11-11 23:05 | `overhaul/p1-tm` @ d5d7e149 + `overhaul/p1-sh` @ 74adabdc | 11013361 | clean (both cut from main f9784861) |

## Tests run here
| Test | Count | Result |
|---|---|---|
