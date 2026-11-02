# Blaze 0.52.0 handoff (branch `feat/blaze-0.52`)

Base: `main` @ 64280448 (0.51.0 merged). versionName 0.52.0, versionCode 187.
Pushed in chunks via the GitLab commits API (work/gl_push.py). This file is updated with every chunk.

## Scope
1. Background agents (Hark-style): Blaze always keeps talking; work goes to background agents shown as one
   "Working · N steps · 1m 2s ›" pill each; results post back as replies. No other status bubbles/banners.
2. Parallel, conflict-free multi tool calling (resource locks, dependency detection, per-call timeouts, ordered results).
3. Blueprint-driven nested orchestration (DAG dataflow; APK / research / media pipelines; tests with fake tools).
4. Spark / Flare / Inferno tiers mapped to real gateway route ids (gateway change on `feat/tiers-0.52` in Blaze-agent, not deployed).
5. Rich reply blocks (gallery, link cards, document card, collapsible sections; existing chart/table/images kept).
6. Version, CHANGELOG, docs/AGENTS.md.

## Status
(see the table at the bottom; updated per chunk)

| Feature | Status |
|---|---|
| Version bump 0.52.0 / 187 | done |
| ToolScheduler (parallel tools) | core + tests written (orchestra/ToolScheduler.kt, KeyedLocks.kt); 34 orchestra unit tests pass (standalone kotlinc harness work/kq.sh); not yet wired into Agent.kt |
| Orchestrator + pipelines | core + tests written (orchestra/Blueprint.kt, Orchestrator.kt, Pipelines.kt); tests pass (fake tools: children start before siblings finish, one writer per file, retry/escalate, cancellation cascade); not yet wired |
| Background agents rework + pill UI | not started |
| Removal of blocking states | not started |
| Model tiers | gateway: Blaze-agent branch feat/tiers-0.52 @3094b9d (0.8.61, src/tiers.ts, 633/633 tests, NOT deployed); app: ModelChoice.routes/forRole + TiersTest (app already sent blaze_family; the gateway ignored it until this branch) |
| Rich blocks | not started |

## Next
Start with the pure cores (ToolScheduler, Orchestrator) + unit tests, then wire into Agent.kt.
