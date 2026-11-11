# P5 execution plan — Team talk & life (0.57.0)

> Working plan for the P5 session started 2026-11-11 23:05 CAT (Hark, for Argus/Finn). Scope stays `06-team-plan.md` §6.6.
> Owner answers (2026-11-11): integration branch `overhaul/p5`, never `main`; compiling and running JVM tests in the sandbox
> is allowed (no CI, no release, no APK, no version bump); D1–D12 = the recommended answers; where an earlier phase has not
> landed, a thin seam that the earlier phase replaces; items that need a real phone or model training are skipped and logged.
> Same commit rules as P0 (`P0-HANDOFF.md` §3): every commit title ends `[skip ci]`; Agent.kt untouched.

## Branching
- Integration branch `overhaul/p5`, cut from `overhaul/p3` @ 980de1cd (contains p1-tm, p1-sh, p3-ag-01, p3-ag-03), then
  `overhaul/p2` @ db85a554 merged in (MR !36 → 87d9baf6; adds P1-SH-02 KitSpec, P2-SF-01 policy, P2-SF-05 audit).
- One branch per ticket `overhaul/p5-<ticket>` cut from `overhaul/p5`, merged back with a real git merge (MR, source kept).
- Earlier-phase branches are re-merged between waves when they move.

## Seams while earlier phases are still landing
| Needs | From | Until it lands |
|---|---|---|
| `team/Models.kt` (Bot spec, 05 §9.1) | P0-TM-01 | P5 uses `team/Bot.kt` (P1-TM-01) types; any missing 05 §9.1 field is added P5-local in the ticket's own file. |
| `BotEngine` / `TeamHooks` | P0-AG-01 | TeamBus/Rooms take a `BotRunner` fun-interface (botId, prompt, budget) → reply; TeamHooks wires it later. |
| Router entry (`route_to`) | P1-TM-06 | `team/Router.kt` exposes a pure `Router.route(...)`; the P1 ARGUS turn calls it once P1-TM-06 lands. |
| Notch / Life tab host | P1-SH-05 (P1-SH-01 shell) | Life/Setup/Roster/Graph/People screens are self-contained composables taking callbacks; the shell mounts them. |
| ARGUS kit | P1-SH-02 | merged (on this branch). |

## Waves
| Wave | Tickets | Why |
|---|---|---|
| 1 | P5-TM-01 TeamBus · P5-AG-01 Domain catalog | roots of the dependency graph |
| 2 | P5-TM-02 Rooms/huddle · P5-TM-03 Workers · P5-TM-04 Routing v2 · P5-SH-01 Setup + roster · P5-SH-03 Life mode v2 · P5-SH-04 People | need wave 1 |
| 3 | P5-SH-02 Compose graph · P5-QA-01 evals | need SH-01 / everything |

## Skipped here (need a device or training) — logged for the owner
- P5-TM-04: regenerating Needle fine-tune data is done (data files only); the fine-tune run itself is not.
- P5-QA-01: RAM on a 4 GB phone with 14 bots; Robolectric/Compose shots tests are written but run first on the owner's "build".

## Status
| Ticket | State | Branch / commit |
|---|---|---|
| P5-TM-01 | wave 1 | |
| P5-AG-01 | done — 14 domain templates (domain.json/SOUL/PLAYBOOK/skill) in 10 Life areas, `team/Domains.kt` catalog + create-from-template, private Pulse/Ledger/Clerk; 33 JVM tests green; log `P5-AG-01-LOG.md` | `overhaul/p5-ag-01` @ ed0d9907 |
| P5-TM-02 | wave 2 | |
| P5-TM-03 | wave 2 | |
| P5-TM-04 | wave 2 | |
| P5-SH-01 | wave 2 | |
| P5-SH-03 | wave 2 | |
| P5-SH-04 | wave 2 | |
| P5-SH-02 | wave 3 | |
| P5-QA-01 | wave 3 | |
