# P2 execution plan — Safe hands (0.54.0)

> Working plan for the P2 session started 2026-11-11 22:39 CAT (Hark, at Finn's request). Scope stays `06-team-plan.md` §6.3. Same owner rules as P0 (`P0-HANDOFF.md` §3): no Gradle build/CI/release until "build", every commit title ends `[skip ci]`.

## Branching
P0 is landing on `main` and P1 on `overhaul/p1*` at the same time, so P2 does not commit to `main`. Integration branch `overhaul/p2`; one branch per ticket `overhaul/p2-<ticket>` merged into it. P0/P1 work is merged into `overhaul/p2` as it lands, with P2 fixes on top. Merge to `main` after P1 is on `main` and the owner reviews.

## Waves
| Wave | Tickets | Why |
|---|---|---|
| 1 | P2-SF-01 policy engine · P2-SF-07 guard ports · P2-SF-05 audit + SpendGuard core (+ SF-04 pure) · P2-SF-06 kill switches | depend only on P0 models / existing agent code |
| 2 | P2-SF-02 cards · P2-SF-04 Permissions page · P2-SF-03 Inbox + Need-you | need SF-01 and P1 thread/kit components (P1-SH-02/03) |
| 3 | P2-SF-08 per-bot notifications · P2-QA-01 safety evals | need SF-03 / all |

## Status
| Ticket | State | Branch / commit |
|---|---|---|
| P2-SF-01 | wave 1 | |
| P2-SF-07 | wave 1 | |
| P2-SF-05 | wave 1 | |
| P2-SF-04 | pure part wave 1, page wave 2 | |
| P2-SF-06 | wave 1 | |
| P2-SF-02 | wave 2 | |
| P2-SF-03 | wave 2 | |
| P2-SF-08 | wave 3 | |
| P2-QA-01 | wave 3 | |
