# P1 · TM + MG workstream log (branch `overhaul/p1-tm`)

> Working log for the Team (TM) + migration (MG) tickets of P1 (0.53.0 "One look, one team"). Kept on this branch only; the integrator folds it into `06-team-plan.md` §6, `STATUS.md` and `overhaul.md`. Branch cut from `main` f9784861.

## How things are verified here (no Gradle, owner rule)
- Pure files + their JVM tests are compiled and run with standalone `kotlinc 2.0.21` on JDK 17, with `junit.jar` + `hamcrest.jar` from the repo root and `org.json:json:20240303` (the repo's own test dependency).
- Until P0-TM-01 lands on `main`, the pure tests compile against a **scratch copy of 05 §9.1** (`Bot`, `Job`, `Row`… verbatim, never committed) standing in for `team/Models.kt`. After P0 is merged in, every test is re-run against the real `Models.kt`.
- Android glue (store singleton, team.db port, BgAgents runner, tool wiring) is checked by hand; Robolectric tests are written to the repo's patterns and run first on the owner's "build".

## Boundaries kept
- P0 files are never written here: `team/TeamDb.kt`, `team/Models.kt`, `team/TeamJson.kt`, `team/TeamSchema.kt`, `agent/engines/*`, `agent/TeamHooks.kt`. `Agent.kt` untouched.
- SH files (`ui/argus/kit`, shell) untouched; TM-03 / TM-07 screens wait on P1-SH-02.
- No build, no CI, no release; every commit title ends with `[skip ci]`.

## Tickets

### P1-TM-01 TeamStore + Bot = persistent Spec — state below
### P1-TM-02 Bot threads on the Row model — not started
### P1-TM-05 Jobs + queue — not started
### P1-TM-04 Bot CRUD + manager tools — not started
### P1-TM-06 Routing v1 — not started
### P1-MG-01 A-team backup import — not started
### P1-TM-03 / P1-TM-07 view-model logic — not started

## Main merges
| When | main commit merged | Notes |
|---|---|---|

## Open questions
