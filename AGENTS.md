# For agents working in this repo

Start with **STATUS.md** ("Current state" and "Standing rules") and **HANDOFF.md** (how to build, test, check CI and
commit). **Since 2026-11-23 (Finn): every change goes on its own branch, then a merge request into `main`.**
Direction: docs/PC-REPLACEMENT.md.
**Failures (Finn, 2026-11-24):** every crash, broken flow, red build or bad deploy gets a write-up in `failures/` (template and index in failures/README.md) the day its cause is known.

**Merge rule.** GitLab pushes every branch to the public mirror `roxaninchue/ARGUS-mobile`, where
`.github/workflows/gate.yml` ("Gate": rename guard, do-not-port, design JS tests, `:app:compileDebugKotlin`, then
`:app:testDebugUnitTest`) runs on every branch except `main`. A merge request merges only when the Gate run for its
**head commit** is green. GitLab does not enforce this (its own CI has no minutes this month and is parked), so check
it yourself right before merging:
`curl -s "https://api.github.com/repos/roxaninchue/ARGUS-mobile/actions/runs?head_sha=<sha>"` -> the run named
`Gate` has `conclusion: success`. Failing tests are readable without a sign-in as annotations:
`/repos/roxaninchue/ARGUS-mobile/check-runs/<job id>/annotations` (job id from `/actions/runs/<id>/jobs`).
WIP commits may carry `[skip ci]` (no Gate run); the last commit before the merge must not. Merge with
`PUT /projects/87806466/merge_requests/<iid>/merge` and a `merge_commit_message` ending in `[skip ci]`, so the merge
into `main` starts no APK build until Finn says build.
**Before a merge or a build (2026-11-25, after red build #144):** (1) the branch head's Gate run is *finished* and
green: compiling is not enough, most breakage is in tests that read the source as text; (2) a build commit goes only on a
`main` whose last merged head had a green Gate; (3) when removing a feature, grep `app/src/test` for every removed tool
name, class, settings id, string and count, and fix those tests in the same branch; (4) never add a parameter after a
function-type parameter that callers pass as a trailing lambda. See failures/2026-11-25-red-build-144-stale-tests.md.
Gate runs the unit suite in 4 shards plus a non-blocking quarantine shard (it used to run serially on one 4-core runner, and a hanging test ran it into the cap); how to read a run (seconds = Node checks, ~5 min = compile, 60 min = time cap, usually a hang to quarantine) is in **docs/GATE.md**.

Read **docs/MODEL-FIRST.md** before changing anything the model sees or anything that routes a user's message.
Build the system around the model. No keyword routers that guess intent, no shouting or contradictory prompt rules,
and no second lane answering beside the main one. `ModelFirstTest` guards this.

**Lean tools rule (docs/LEAN-TOOLS.md):** never add tools to every turn. A new feature's tools go in the discoverable
catalog (registered in `Agent.tools()`, found with `find_tools`), not in `LeanTurn.CORE` or the prompt.

Skills and specialists reach the model as shelves (docs/SKILL-SHELF.md): each line is the first sentence of a skill's
description or a specialist's prompt, capped at 10 words after the dash, so write that sentence to say when to use it.

UI behaviour Finn has ruled on (sheets that close only with their X, slow-drag scrolling, the full-screen intro):
docs/UI-RULES.md. Read it before touching a sheet, a scrolling list or the intro.

How a chat turn works today: docs/LEAN-HARNESS.md. Agent loop and background agents: docs/AGENTS.md. Harness rules and
the handoff log: overhaul.md. All docs: docs/README.md.
