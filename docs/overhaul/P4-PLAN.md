# P4 execution plan — Apps & skills (0.56.0)

> Working plan for the P4 session started 2026-11-11 22:58 CAT (Hark, for Jack). Scope stays `06-team-plan.md` §6.5.
> Owner answers (2026-11-11): integration branch `overhaul/p4`, never `main`; compiling and running tests in the sandbox
> is allowed (no CI, no release, no APK); argus-connect work is **code only, no deploy**; P3 is not on GitLab yet, so
> P3-dependent pieces get a thin draft that P3 replaces; D1–D12 = the recommended answers.
> Same commit rules as P0 (`P0-HANDOFF.md` §3): every commit title ends `[skip ci]`; Agent.kt grows as little as possible.

## Branching
Integration branch `overhaul/p4` (from `main` f9784861). P0/P1/P2/P3 work is merged in as it lands, with P4 fixes on top.
Merge to `main` only after the earlier phases are on `main` and the owner reviews.
Server work for P4-API-01 goes to branch `overhaul/p4` in `blazeinchue/argus-connect` (no deploy).

## Seams while earlier phases are still landing
| Needs | From | Until it lands |
|---|---|---|
| `ActionClass` / `Policy.decide` | P2-SF-01 | `connect/ConnectRisk.kt` maps every connector/MCP tool to the 05 §9.1 `ActionClass` names; writes go through the agent's existing confirm gate. Swap to `Policy.decide` when P2 lands. |
| `Bot` / `BotAppGrant` / team.db `bot_apps` | P0-TM-01, P1-TM-01 | `connect/AppGrants.kt` holds the same shape (appId, source, tools → ALLOW/ASK/NEVER) as a pure model with a JSON form identical to `bot_apps.tools`; a 5-line adapter maps `team.BotAppGrant` once it exists. |
| Bot profile (tabs host) | P3-SH-01 | `ui/team/profile/AppsTab.kt` + `SkillsTab.kt` are self-contained composables taking a bot id; P3's profile mounts them. |
| ARGUS kit (rows, chips, sheet) | P1-SH-02 (`overhaul/p1-sh`) | Apps page uses Material3 + the kit's shapes where they exist; restyled when the kit is on `main`. |

## Waves
| Wave | Tickets | Why |
|---|---|---|
| 1 | P4-CN-01 ArgusConnect client · P4-CN-03 connector tools + cap 64 · P4-CN-05 skills on argus-connect · P4-API-01 triggers server | need nothing from P1–P3 |
| 2 | P4-CN-06 MCP per bot · P4-CN-07 triggers client · P4-CN-02 Apps & connectors page | need wave 1 |
| 3 | P4-CN-04 Apps + Skills tabs, Connect return resumes the turn · P4-QA-01 | needs P3 profile (draft until then) |

## Status
| Ticket | State | Branch / commit |
|---|---|---|
| P4-CN-01 | wave 1 | |
| P4-CN-03 | wave 1 | |
| P4-CN-05 | wave 1 | |
| P4-API-01 | wave 1 | |
| P4-CN-06 | wave 2 | |
| P4-CN-07 | wave 2 | |
| P4-CN-02 | wave 2 | |
| P4-CN-04 | wave 3 (draft on P3 seam) | |
| P4-QA-01 | wave 3 (phone checklist needs a device; evals in sandbox) | |
