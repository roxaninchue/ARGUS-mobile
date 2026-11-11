# P0 Handoff — ARGUS unified app (Groundwork, 0.52.4)

You are picking up Phase 0 of the ARGUS overhaul. The planning is finished; no code has been written yet. Your job is to lay the foundation: tickets 1–9 below. Ticket 10 (the release) waits until the owner says "build".

## 1. What this project is
Argus-mobile (Kotlin/Compose, package `com.blaze.assistant`, currently 0.52.3) absorbs every feature of:
- **A-team** (GitLab `blazeinchue/a-team`, project 88676660, v2.18): Android WebView app + Cloudflare Worker `argus-agents-api`. **Its design language is the target look.**
- **Bot HQ** (`github.com/nickvasilescu/bot-hq`, MIT): multi-agent "team" app. Mostly a design source; it needs a server with Chrome, so little code ports directly.

The result is one app: an ecosystem of specialist agents (one per task and life area), led by a Manager bot named ARGUS.

## 2. Where everything is
- Repo: GitLab project **87806466** (argus-mobile), branch `main`. Private; you need your own GitLab access.
- Master handoff: `overhaul.md` at repo root (Status + Handoff log). Read it first.
- Plan docs in `docs/overhaul/`:
  - `01-bothq-inventory.md` (89 Bot HQ features)
  - `02-ateam-inventory.md` (72 A-team features + design language)
  - `03-argus-mobile-map.md` (merge map, architecture)
  - `04-design-ia.md` (design system, Team/Chat/Life/Desk IA, wireframes)
  - `05-agent-ecosystem.md` (agent model, 14 life-area agents, memory, safety, API v2; team.db schema in §9.2)
  - `06-team-plan.md` (phases, full ticket backlog, coverage matrix, risks, QA, pick-up guide in its last section)
  - `ui-concept.png` (provisional UI concept; look can change)

Read order: `overhaul.md` → `06-team-plan.md` §1–§6 → the slice doc for the ticket's workstream (SH → 04; TM/AG → 05; API → 02 §J + 05 §6.4/§9.3; MG → 02 §H9/§M + 06 §10).

## 3. Rules that never bend
1. Commit straight to `main`. **Every commit title ends with `[skip ci]`.**
2. **No Gradle build, no CI, no release until the owner says "build".** Check code by hand and by running pure tests with standalone `kotlinc` where possible.
3. Never change `applicationId`, signing, `keystore/`, or the rotate-key CI step.
4. Never alter the shared `memories` schema (side tables only). Keep deployed wire names (`blaze-*`, `X-Blaze-*`).
5. `Agent.kt` (~5.8k lines, already hit "Method too large") may grow by **≤ 20 lines total**, only for the TeamHooks seam.
6. No `@Ignore`, no skipped tests, no threshold changes (700 ms stall gate, 7 low-RAM smoke tests, PhonePerfTest).
7. Never copy Bot HQ / Grok artwork (not MIT). Use A-team orbs only. Ask for a LICENSE on a-team before copying its code (P0-IL-02).
8. Never put a secret in code, logs, sync, or commits.
9. Pure Kotlin first (rules in pure files with JVM tests), Android glue beside them. Update affected source-scan/screenshot tests in the same commit.
10. The `gateway/` folder in argus-mobile is a stale 0.3.0 copy. The live gateway (0.9.x) lives in the separate Blaze-agent repo; don't edit gateway code here.

## 4. P0 tickets
Order: 1–8 can run in parallel; 9 any time; 10 only on "build".
Dependencies: P0-AG-01 needs P0-TM-01; P0-SH-02 needs P0-SH-01.

| # | Ticket | Workstream |
|---|---|---|
| 1 | P0-TM-01 team.db + pure models | TM |
| 2 | P0-AG-01 BotEngine + LocalEngine seam | AG |
| 3 | P0-SH-01 ui/argus tokens (behind `argus_look`, default off) | SH |
| 4 | P0-SH-02 Lucide icons + BotOrb + Manager sphere | SH |
| 5 | P0-API-01 `blaze_context:"full"` + LiveModels | API |
| 6 | P0-QA-01 spec index + evals harness | QA |
| 7 | P0-MG-01 A-team export format | MG |
| 8 | P0-IL-02 repo hygiene + third-party notices | IL |
| 9 | P0-IL-03 do-not-port register | IL |
| 10 | P0-IL-01 release 0.52.4 (**wait for "build"**) | IL |

### Ticket detail (from 06-team-plan.md §6)
| Ticket | Scope | Files | Done when | Deps | Size | Covers |
|---|---|---|---|---|---|---|
| **P0-IL-01** | Release 0.52.4: bump versionName/versionCode (0.52.4 / 191), CHANGELOG + STATUS + about.md entries for the three unreleased rounds | `app/build.gradle.kts, CHANGELOG.md, STATUS.md, app/blaze-docs/about.md` | Owner says build → CI 9/9 green on GitHub mirror; versions.json updated by release flow; no applicationId/signing change | — | S | AM-15 |
| **P0-IL-02** | Repo hygiene: gateway/ README 'read-only stale 0.3.0 copy, live gateway in Blaze-agent'; THIRD_PARTY_NOTICES for Bot HQ (MIT), Lucide (ISC), Simple Icons (CC0), Haze (Apache-2.0), noVNC (MPL-2.0, later); ask owner to add a LICENSE to a-team before code is copied | `gateway/README.md, THIRD_PARTY_NOTICES.md, app/src/main/assets/legal/` | LegalAssetsTest/MoviesLegalTest updated and green; notices list every new third-party source | — | S | AM-63 |
| **P0-IL-03** | Do-not-port register: document and verify absence of BH dead/stub code (subagents.ts as-is, draft emitter-less UI, stubs, unused tables, Grokbot branding, Grok artwork), BH server-only auth (Composio Google sign-in, ALLOWED_EMAILS, dev/owner links), AT removed Connections/gmail helpers, AT no-Gradle build, AT cactus.wasm | `docs/overhaul/do-not-port.md` | Every not-ported feature listed with reason + superseding ticket; grep check in CI-free script | — | S | BH-75, BH-76, BH-77, BH-78, BH-86, AT-01, AT-57, AT-67 |
| **P0-TM-01** | team/ package + team.db (raw SQLite like cache.db) with 05 §9.2 schema (bots, bot_soul_versions, bot_apps, rows, jobs, job_steps, bot_threads, routines, suggestions, proactivity, approvals, policies, spend_ledger, audit_log, screens) + versioned migrations; pure models Bot/Job/JobStep/Row/BotMessage/Routine/Suggestion/Approval/MemScope | `team/TeamDb.kt, team/Models.kt, team/TeamJson.kt` | TeamDbTest (Robolectric) creates + migrates; JSON round-trip tests; nothing wired to UI | — | M | BH-83 |
| **P0-AG-01** | BotEngine interface + LocalEngine wrapping Agent.Session through a TeamHooks seam (identity, tool subset, row sink) | `agent/engines/BotEngine.kt, agent/engines/LocalEngine.kt, agent/TeamHooks.kt, agent/Agent.kt (≤20 lines)` | LocalEngineTest emits Row events from a fake LlmClient; Agent.kt line delta ≤ 20; existing agent tests green | P0-TM-01 | M | BH-11, AM-14 |
| **P0-SH-01** | ui/argus tokens: ArgusColors/Type/Shapes/Motion/Glass/Elevation (04 §2), argusGround (Plain/Orbit/Cobalt night), AA light variants, Material3 colorScheme mapping, Haze glass with low-RAM/API<31 fallback; behind setting `argus_look` (default off in P0) | `ui/argus/ArgusTheme.kt, ArgusTokens.kt, ArgusType.kt, ArgusGlass.kt, app/build.gradle.kts (Haze)` | ContrastTest extended to 3 grounds × glass levels; ArgusTokensTest; ThemeEverywhereTest still green | — | M | AM-13 |
| **P0-SH-02** | Lucide generator (reads a-team app/www/icons.js → ui/argus/icons/Lucide.kt, ~90 ImageVectors, stroke-by-size) + BotOrb Canvas (6 faces Orbit/Tilt/Steep/Pure/Twin/Polar, any colour, OrbState Idle/Working/Waiting/Talking/Away) + Manager cobalt 'A' sphere (ArgusMark) | `tools/gen-lucide.mjs, ui/argus/icons/Lucide.kt, ui/argus/BotOrb.kt` | OrbShotsTest light/dark × sizes; IconOnlyTest/NoSquareTest green; no BH artwork | P0-SH-01 | M | BH-03, AT-13 |
| **P0-API-01** | Client gateway fixes: send blaze_context:"full" on direct gateway calls (AT D6); port a-team models.js → agent/LiveModels.kt (GET /v1/models/live, 5-min refresh, lane pick, X-Blaze-Prefer/Avoid, follow fallback headers) behind flag | `agent/Cloud.kt, agent/LiveModels.kt, agent/ModelRoute.kt` | CloudTest asserts body field; LiveModelsTest ports a-team evals/test model cases | — | M | AT-29, AT-36, AM-27 |
| **P0-QA-01** | Spec index: map every A-team Playwright PASS line (app/tests/*), A-team evals/test case and Bot HQ e2e step (tests/e2e.mjs, voice-call.mjs) to a ticket ID; copy a-team evals/ harness into evals/ (Node, no deps) pointed at the gateway | `docs/overhaul/specs.md, evals/` | Every spec has a ticket; `node evals/run.mjs --suite refusals` runs against gateway | — | M | BH-85, AT-65, AT-66 |
| **P0-MG-01** | Define argus-team-export.v1.json (bots + look, jobs, settings, profile items stated/inferred, bot/team memory, routines, optional Manager chats) and the A-team backup JSON mapping | `docs/overhaul/ateam-export.md` | Format reviewed; fixtures from A-team Demo team checked in under app/src/test/resources/ | — | S | — |

## 5. Decisions already assumed
The owner hasn't answered these yet; the plan assumes the recommended answer. None of them block P0.
- D1 One ARGUS chat + one thread per bot, history drawer — yes
- D2 Persona names (Ledger, Atlas…) with life area underneath — yes
- D3 Lead bot is ARGUS (Manager), undeletable — yes
- D4 Alpine stays system Linux; optional shared Ubuntu workspace — yes
- D5 Third-party apps via argus-connect, credentials server-side; no Composio in app — yes
- D6 Automatic models, no picker, optional own Gemini key — yes
- D7 Hermes after 1.0 — defer
- D8 Cloud desk: Codespaces first, then own PC, Orgo optional — yes
- D9 Phone-off routines after 1.0 — defer
- D10 Retire A-team after migration — yes
- D11 Reverse the 0.51 single-persona decision — confirm
- D12 Gateway work needs write access to Blaze-agent — **not granted yet**; gateway-side work is view-only

## 6. Key findings to keep in mind
- Bot HQ has no social-connections code; design it fresh.
- Bot HQ's SOUL.md is generated, not truly editable.
- Bot HQ's approval gate only covers Composio writes; put its look on top of Argus's existing confirm gate, not instead of it.
- Don't embed Hermes on the phone; keep the native agent loop.
- A-team's v2.19 branch (chat drawer) is not on main.
- A-team ships under a different package and signing key, so no in-place upgrade; migration is by export/import (P0-MG-01 → P1-MG-01).
- Open: Needle runtime choice (Cactus SDK, wasm in WebView, or existing offline model); whether A-team connector tokens live on server or phone.

## 7. When a ticket is done
1. Append "✓ <commit>" to the ticket title in `06-team-plan.md` §6.
2. Update the coverage matrix "How" column if it changed.
3. Add a line to `STATUS.md` (unreleased round section).
4. Add one line to the `overhaul.md` Handoff log: `date · who · ticket(s) · what changed · what's next`.

If blocked (missing decision, gateway access, failing spec), log it in the Handoff log with the ticket ID and move to the next unblocked ticket. Never guess an owner decision beyond the defaults above.

When P0 is done, P1 starts with P1-SH-01 (shell) and P1-TM-01 (TeamStore) in parallel.
