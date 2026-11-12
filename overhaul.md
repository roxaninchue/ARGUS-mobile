# ARGUS Overhaul — Argus-mobile × A-team × Bot HQ

> Living handoff. If you're picking this up mid-way: read **Status** and **Handoff log** first, then the slice docs in `docs/overhaul/`.
> Rules: commit straight to `main`, every commit title ends with `[skip ci]`, no build until the owner says build.

## Goal
One unified ARGUS app (this repo, `argus-mobile`, Kotlin + Compose) that contains:
1. **Everything in Argus-mobile** today (Blaze Android: chat, voice, orb, phone control, terminal, browser, media, memory, life logs, routines…).
2. **Everything in A-team** (`gitlab.com/blazeinchue/a-team`: team of bots as chats, Manager bot, jobs/approvals inline, Compose graph, Ubuntu workspace per bot, cloud computer, connectors, vault, skills, Needle on-device commands, `argus-agents-api`).
3. **Everything in Bot HQ** (`github.com/nickvasilescu/bot-hq`, MIT, from Nick Vasilescu's video "I Stole the Best Part of Every AI Agent… With One Prompt"): Grok-Bot-style texting UI with many bots + voice + X search, Muse-style social connections + editable soul/memory files, Dots-style per-agent computer you can watch/take over, Hermes agent engine, Composio apps with approval cards, routines/tasks/Library, bots talking to bots.

**Design:** A-team's design language is the base. Every feature must be reachable neatly, creatively, and in few taps.
**Vision:** an ecosystem of specialist agents — one per task and per part of the person's life — orchestrated by a Manager.

## Specialist slices (each writes its own doc, then this file is merged)
| # | Slice | Doc | Status |
|---|---|---|---|
| 1 | Bot HQ full feature inventory + port notes | [`docs/overhaul/01-bothq-inventory.md`](docs/overhaul/01-bothq-inventory.md) | done (89 features, 2cdb65b4) |
| 2 | A-team full feature inventory + port notes | [`docs/overhaul/02-ateam-inventory.md`](docs/overhaul/02-ateam-inventory.md) | done (72 features + design language, 086b8f21) |
| 3 | Argus-mobile inventory, architecture & merge map | [`docs/overhaul/03-argus-mobile-map.md`](docs/overhaul/03-argus-mobile-map.md) | done (25-row merge map, 73988433) |
| 4 | Design system + information architecture (A-team look, every feature placed) | [`docs/overhaul/04-design-ia.md`](docs/overhaul/04-design-ia.md) | done (~150-row feature map, 13 wireframes) |
| 5 | Agent ecosystem architecture (life-domain specialists, Manager, soul/memory, bot-to-bot, computers, models) | [`docs/overhaul/05-agent-ecosystem.md`](docs/overhaul/05-agent-ecosystem.md) | done (14 life agents, E0–E8 order, 1a21f133) |
| 6 | Integrated team plan: phases, workstreams, owners, milestones, risks, tests | [`docs/overhaul/06-team-plan.md`](docs/overhaul/06-team-plan.md) | done (30 reconciliations, 12 decisions, 11 phases, 102 tickets, 278-row coverage matrix) |

## UI concept
Early look (not final, can change): Team, Agent chat, Life, Desk.

![ARGUS UI concept](docs/overhaul/ui-concept.png)

## Status
- 2026-11-11: Overhaul kicked off. Sources pulled: argus-mobile@main (0.52.3 / vc 190 + three unreleased rounds), a-team@main (v2.18), bot-hq@HEAD. Specialists 1–5 landed all five slice docs by 20:56 CAT.
- 2026-11-11: **Slice 6 done — the plan is ready.** `docs/overhaul/06-team-plan.md` reconciles the slices (30 items), lists 12 decisions for Finn (each with a recommended answer the plan already assumes), sets 11 phases with versions (0.52.4 → 1.0.0, then 1.1.0), 13 workstreams with owners, a 102-ticket backlog (~336 agent-days), and a coverage matrix that places **all 254 source features** (BH 89 · AT 72 · AM 68 · merge map 25) plus 24 new ones on a phase, a ticket and a UI location — 0 missing.
- **Now:** P0 under way. ✓ P0-TM-01 (MR !40, 2026-11-11) ✓ P0-AG-01 (7f12551d) ✓ P0-SH-01 (21fe3ebb) ✓ P0-SH-02 (ccf1a382) and ✓ P0-API-01 (cd942dad) and ✓ P0-QA-01 (21513181) and ✓ P0-MG-01 (0167d249) and ✓ P0-IL-02 (daa6cbe0) and ✓ P0-IL-03 (60818dab, 2026-11-12) on main. P0 groundwork done except P0-IL-01 (release), which waits for "build".

## Handoff log
Append one line per session: date, who, what changed, what's next.
- 2026-11-11 · Hark · Created this file and the slice table. Next: slice docs land, then slice 6 merges them into the plan below.
- 2026-11-11 · Hark · Slices 1–5 published to docs/overhaul/. Slice 6 now writing 06-team-plan.md and the Unified plan section.
- 2026-11-11 · Hark (Specialist 6) · Published `docs/overhaul/06-team-plan.md` v1 (reconciliation, decisions, phases + versions, workstreams, backlog P0–P10, full coverage matrix, risks, QA plan, A-team migration, pick-up guide) and filled the Unified plan below. Next: Finn answers D1–D12; then dispatch P0 tickets 1–8 in parallel (see "Next 10 tickets").
- 2026-11-11 · Hark · Added UI concept image (docs/overhaul/ui-concept.png), provisional.
- 2026-11-11 · Hark · P0-TM-01 done: `team/` Models.kt (05 §9.1 + DEFAULT_RULES), TeamJson.kt (pure JSON), TeamSchema.kt (team.db v1 = 05 §9.2 DDL, 15 tables; v2 = indexes), TeamDb.kt (SQLiteOpenHelper, FKs on, newer file refused); TeamJsonTest 10/10 + TeamSchemaTest 7/7 pass on JVM, TeamDbTest (Robolectric) written, runs on "build"; nothing wired to UI (a1141b6f, merge b765db55). Next: P0-AG-01.
- 2026-11-12 · Hark · P0-AG-01 done: `agent/engines/BotEngine.kt` (05 §5.2 contract: TurnInput, TurnEvent, EngineCaps, RowSink), `agent/engines/LocalEngine.kt` (LocalEngine on Agent.Session, ToolSubset = core + bot groups + per-tool grants, RowMapper → BH rows message/event/notice/tool-call with seq/updatedSeq), `agent/TeamHooks.kt` (pure seam: identity block, tool subset/refuse, row sink, end), `TeamTools.kt` + `engines/AgentSessionDriver.kt` (Android glue); Agent.kt +7 lines (identity system msg, tool filter, runTool refuse/call/result, add() → row sink, endTurn, teamSession). TeamHooksTest 8/8 + LocalEngineTest 10/10 (fake LLM loop, Agent.kt ≤ 20-line check) + TeamJsonTest 10/10 pass on JVM (kotlinc 2.0.21, JDK 17); Gradle/Robolectric suites not run (no build). Nothing wired to UI. MR !45 closed, landed as 7f12551d (token can't press Merge). Next: P0-SH-01.
- 2026-11-12 · Hark · P0-SH-01 done: `ui/argus/ArgusTokens.kt` (pure: 04 §2 palette dark/light with AA variants, Plain/Orbit/Cobalt-night grounds, glass levels, type scale, space/radius/elevation/motion incl. reduced motion, bot + domain + BH colour maps, M3 roles, ArgusGlassRule, ArgusLookFlag `argus_look` default off), `ArgusColors.kt`, `ArgusType.kt` (Inter/Inter Tight; serif + mono = system until fonts are bundled under IL-02), `ArgusGlass.kt` (Surface/Raised glass, Chrome/Sheet via Haze 1.0.2 with 92 % tint fallback), `ArgusTheme.kt` (LocalArgusColors/Ground, M3 colorScheme + Typography, argusGround / ArgusGround). AA changes vs 04: dark mute #A6A6AE, dark danger text #FF8085, dark cobalt text #86A3FF, light danger/success/waiting text #C0272D/#0D744A/#895C00, light success fill #128A59, `dim` decorative only (+ `meta` token for timestamps), Orbit veil 82 % dark / 95 % light (04 said 60 %; the Earth's limb hits grey 0.67). Haze pulls compose ui/foundation 1.7.5; add Haze (Apache-2.0) to THIRD_PARTY_NOTICES in P0-IL-02. Cobalt-night light variant invented (#E9EDFF→#F4F5F8). SpaceWiringTest: 'ui/argus removed' now checks the old 3D-Home files only. Tests: ArgusTokensTest 13/13 + new ContrastTest case (Compose Color math) green on JVM; Compose files compiled with the Compose compiler against compose 1.7 / material3 1.3 / haze 1.0.2 (app classes stubbed). No Gradle build. MR !46 closed, landed as 21fe3ebb. Next: P0-SH-02.
- 2026-11-12 · Hark · P0-SH-02 done: `tools/gen-lucide.mjs` (A-team icons.js + lucide-static 0.460.0 for the 20 04 §2.7 icons A-team lacks → `ui/argus/icons/LucidePaths.kt`, 96 icons + 3 aliases, sorted, no timestamp; run twice = identical), `icons/Lucide.kt` (cached ImageVectors, A-team stroke-by-size 2.25/2/1.85), `icons/LucideSpec.kt` (semantics, domains, sizes), `ui/argus/OrbGeometry.kt` (pure botSVG numbers: 6 faces + tilts, rings, mixHex colours, states Idle/Working 8 s turn/Waiting/Talking +8 %/Away 40 % grey, status dot, sizes xs–hero, Manager = only cobalt), `BotOrb.kt` (Canvas BotOrb + ManagerOrb drawing the ARGUS A; A-team's manager.png not copied; no BH artwork). Tests: OrbGeometryTest 8/8 + LucidePathsTest 6/6 green on JVM; Compose files compiled with the Compose compiler against compose 1.7; OrbShotsTest (shots-only, light/dark × sizes × states) not run (needs Robolectric). Lucide ISC notice owed in P0-IL-02. MR !47 closed, landed as ccf1a382. Next: P0-API-01.
- 2026-11-12 · Hark · P0-API-01 done: `Cloud.tag` now sends `blaze_context: "full"` on every gateway call (A-team D6; gateway relevance.ts opt-out), always on. `agent/LiveModels.kt` = pure port of A-team models.js v2.13 (Wire names, parseLive, pick, plan, answered, avoidHeader, onFail, 5-min Cache with 6 h max age); `ModelRoute.livePrefer`; `agent/LiveModelsFeed.kt` glue + 5 lines in `LlmClient.call` (background refresh, X-Blaze-Prefer when the turn has no pin, served route from X-Blaze-Route), all behind `live_models` (default off). Finding: gateway 0.8.45+ sends X-Blaze-Provider/Model only in debug, so today `lastServedKey` is always empty (TurnModel never pins, empty-route avoid never fires); with the flag on the X-Blaze-Route token fills it (the gateway takes tokens back in Prefer/Avoid). Tests: LiveModelsTest 9/9 (v29 + v212 cases) + ModelRouteTest green on JVM; CloudTest's new assertion checked via a harness (Cloud.kt compiled with stubs). LlmClient edits not compiled (no build). MR !48 closed, landed as cd942dad. Next: P0-QA-01.
- 2026-11-12 · Hark · P0-API-01 follow-up (owner: "do what's best"): reading the served route from X-Blaze-Route is now always on (`LiveModelsFeed.served`), so TurnModel pins and empty-route avoid work again on the live gateway; the live pick and its label stay behind `live_models`.
- 2026-11-12 · Hark · P0-QA-01 done: `docs/overhaul/specs.md` = spec index, 833 specs (A-team `app/tests/*` PASS lines + log-only checks + Java, A-team `evals/test/*` cases, Bot HQ `tests/e2e.mjs` + `voice-call.mjs`), each mapped to a ticket by `tools/gen-specs.mjs` (file rule → keyword → file default; first pass, ticket owners re-check their rows). A-team `evals/` copied to `evals/` (guard.js + models.js vendored in `evals/vendor/`; only graders/guard/v212 tests + new `specs.test.mjs`). Local: `node --test evals/test/*.test.mjs` 34/34; refusals mock good 63/63, mock bad 0/63; code selfcheck all pass. Live `--suite refusals` against the gateway not run: needs an ARGUS `bz_` token in `ARGUS_TOKEN`. MR !49 closed, landed as 21513181. Next: P0-MG-01.
- 2026-11-12 · Hark · P0-MG-01 done: `docs/overhaul/ateam-export.md` defines `argus-team-export.v1` (bots + look, jobs, settings, profile stated/inferred, bot/team memory, routines, proactivity, optional Manager chats) and maps both it and A-team's Copy backup (`{agents, tasks}`) onto team.db / blaze.db + mem_scope. `team/ATeamExport.kt` (pure: detect, 13 validation rules, faceOf, scopeFor, schedule). Fixtures `app/src/test/resources/ateam/demo-team.{backup,export.v1}.json`, hand-built from A-team `demo()` @7c4b459a (not a phone export). `ATeamExportTest` 6/6 (JVM). Open for owner: imported open jobs land `confirmed = 0`; bot emails dropped; non-Manager chats not imported. MR !50 closed, landed as 0167d249. Next: P0-IL-02.
- 2026-11-12 · Hark · P0-IL-02 done: THIRD_PARTY_NOTICES (root + assets/legal, identical) gain an "ARGUS overhaul" section: Haze 1.0.2 (Apache-2.0), Lucide (ISC, Feather MIT portions; lucide-static 0.460.0), Bot HQ (MIT; design + test names, no artwork), A-team (MIT by owner grant 2026-11-11). `gateway/README.md` gets a read-only stale-copy banner (live gateway: Blaze-agent 0.9.7). `evals/README.md` provenance corrected. LegalAssetsTest gains `overhaulSourcesAreCredited`. Deviations table added under Decisions; D1–D11 recorded as taken. Simple Icons / noVNC not added (not in repo). MR !51 closed, landed as daa6cbe0. Next: P0-IL-03.
- 2026-11-12 · Hark · P0-IL-03 done: `docs/overhaul/do-not-port.md` lists 18 rows (BH-75/76/77/78/86, Bot HQ artwork, BH dead code ×7, BH mode kept on purpose, AT-01, AT-57, AT-67, cactus.wasm), each with reason and replacing ticket. `node tools/check-do-not-port.mjs` checks the 13 grep-able rows over app/src/main, tools, evals: 0 hits after dropping `composio` from a BotAppGrant comment. `node --test tools/check-do-not-port.test.mjs` 2/2. MR !52 closed, landed as 60818dab. Next: P0-IL-01 when the owner says "build".

## Unified plan
Full detail, ticket tables and the coverage matrix: [`docs/overhaul/06-team-plan.md`](docs/overhaul/06-team-plan.md). Inputs: [01 Bot HQ](docs/overhaul/01-bothq-inventory.md) · [02 A-team](docs/overhaul/02-ateam-inventory.md) · [03 Argus-mobile map](docs/overhaul/03-argus-mobile-map.md) · [04 Design + IA](docs/overhaul/04-design-ia.md) · [05 Agent ecosystem](docs/overhaul/05-agent-ecosystem.md).

### In one paragraph
Argus-mobile stays the host (package `com.blaze.assistant`, one `:app` module). A-team's look and team-of-bots-as-chats become the product shape; Bot HQ's multi-agent UX and safety mechanics (rows, in-call approvals, bot-to-bot, rooms, watch/take-over, voice hand-off) are ported natively; Argus-mobile's runtime (agent loop, BgAgents, Orchestra, Mind, phone control, terminal, browser, media) is the engine of **every** bot. Every bot is data — identity + soul + memory + skills + tools + engine + apps + computer + permissions — run by one runtime, with **ARGUS (the Manager)** as the front door routing to **14 life-domain agents** (Tempo, Inbox, Pulse, Ledger, Career, Forge, Hearth, Pantry, Atlas, Circle, Tutor, Clerk, Scout, Studio) that spin up short-lived workers. The app has four modes — **Team · Chat · Life · Desk** — switched by the A-team notch. Zero-budget by default: paid services (Composio, xAI, Orgo) are optional adapters.

### Architecture
```
 ARGUS app (Kotlin/Compose)
 ├ ui/argus  A-team tokens · Lucide · BotOrb · Notch[Team|Chat|Life|Desk] · Composer(@bot /cmd $ #file mic) · Inbox · Voice
 ├ team/     Bot = persistent Spec · Rows · Jobs queue · ManagerTools · TeamBus (hop≤3) · Rooms · 14 Domains
 ├ agent/    BotEngine → LocalEngine (Agent.Session, frozen) · CloudRunEngine · HermesEngine (1.1)
 ├ policy/   Policy.decide(ActionClass, taint, autonomy) → approval/draft/question/connect cards → audit_log
 ├ memory    Mind/blaze.db + mem_scope · SOUL.md / MEMORY.md / USER.md projections
 ├ proactive SignalBus → domain proposers → ARGUS arbiter (caps, quiet hours, one dial) · routines per bot
 ├ computer/ LocalBrowser · Codespaces · self-hosted desk · Orgo (leases)   terminal/ Alpine system + Ubuntu workspace, home+tmux per bot
 └ connect/  argus-connect · MCP client · device OAuth · CloudSync (E2E) · TeamSync          voice/ on-device · Gemini · xAI (opt.)
        │ bz_ token everywhere
        ├─▶ Blaze Gateway (models, /v1/runs, live models, relay)      ├─▶ argus-connect (apps, skills, triggers; Composio optional, server-side)
        └─▶ argus-agents-api v2 (D1, REST+MCP, PATs, approvals, events) ◀─ Desktop / Claude / Cursor     └─▶ cloud desk (confirmed only)
```

### Phases (each shippable; patch per release, .9 rolls to next minor, each big feature = next minor)
| Phase | Version | Ships |
|---|---|---|
| P0 Groundwork | **0.52.4** | the three unreleased rounds on main + invisible seams (team.db, BotEngine, ui/argus tokens, BotOrb, LiveModels, `blaze_context`) |
| P1 One look, one team | **0.53.0** | A-team shell (Team·Chat·Life·Desk), ARGUS as a bot, bot threads, create bots with orbs, Tempo + Inbox, delegation as Jobs, Desk → Jobs, Settings re-tree, A-team backup import |
| P2 Safe hands | **0.54.0** | one policy engine, approval/draft/question/connect cards, Inbox + Need-you, Permissions, Activity (audit), Pause all, A-team guard ports |
| P3 Souls & memory | **0.55.0** | Bot profile, SOUL.md editor, memory scopes, What the team knows, auto-learn, per-bot model policy |
| P4 Apps & skills | **0.56.0** | argus-connect Apps page, per-bot apps/skills/MCP, connector writes behind cards |
| P5 Team talk & life | **0.57.0** | bot-to-bot + Handoff cards, rooms + huddle, 14 domain agents + setup picker, routing v2, Life mode, People graph, Compose graph |
| P6 Proactive team | **0.58.0** | routines per bot, SignalBus + arbiter + one dial, check-ins/brief/Today, pop-up + edge glow, widgets/QS/share/deep links |
| P7 Computers | **0.59.0** | Ubuntu workspace per bot, Terminal tabs, Library, code review, cloud desk leases, Computer viewer + take-over |
| P8 Voice & everywhere | **0.60.0** | voice two-agent calls, X search (optional key), agents-api v2 + sync + remote approvals, MCP relay, A-team v2.20 "Move to ARGUS" |
| P9 ARGUS 1.0 | **1.0.0** | every AM surface on the new look, domain wiring, A-team retired, full release gate |
| P10 Open engines | 1.1.0 | Hermes engine, cloud routines, optional paid adapters, targetSdk 35 |

### Workstreams and owners
| Code | Workstream | Owner | Code | Workstream | Owner |
|---|---|---|---|---|---|
| SH | Shell & Design | Design-system agent | PR | Proactivity & Routines | Proactivity agent |
| TM | Team & Manager | Team-runtime agent | VC | Voice | Voice agent |
| AG | Agents & Memory | Agent-runtime agent | API | API / Gateway & Sync | Platform agent |
| SF | Safety | Safety agent | LF | Life & Phone continuity | Continuity agent |
| CN | Connectors & Apps | Connectors agent | MG | Migration (A-team) | Migration agent |
| CP | Computers & Terminal | Computers agent | QA | QA / Evals | QA agent |
| IL | Integration lead (versions, docs, matrix) | Specialist 6 | | | |

### Decisions for Finn (the plan assumes the recommended answer)
| # | Decision | Recommended |
|---|---|---|
| D1 | One continuous chat with ARGUS + one thread per bot; "new chat" = fresh context with a history drawer | Yes |
| D2 | Persona names (Ledger, Atlas, Pulse…) with the life-area label underneath | Yes |
| D3 | The lead bot is called **ARGUS** (role Manager), undeletable | ARGUS |
| D4 | Alpine stays system; Ubuntu 24.04 opt-in workspace (~800 MB) shared by all bots, a home each | Yes |
| D5 | Third-party apps through argus-connect (credentials server-side); on-phone tokens only for device-bound apps; Composio never in the app | Yes |
| D6 | Automatic models; per-bot Depth + optional own Gemini key; no model-id picker | Yes |
| D7 | Hermes engine after 1.0 (1.1.0) | Defer |
| D8 | Cloud desk: Codespaces default; self-hosted desk on your PC (Desktop link) second; Orgo optional | Yes |
| D9 | Cloud routines with the phone off: after 1.0 | Defer |
| D10 | Retire A-team after migration (v2.20 "Move to ARGUS", archived at 1.0) | Retire |
| D11 | Confirm reversing the 0.51 "Halo merged" single-persona decision (bots are personas on one engine) | Confirm |
| D12 | Gateway work (relay, voice token, x-search) goes to `Blaze-agent`; we need access or an owner there | Give access |

**Answered (Finn, 2026-11-12: "anything I haven't answered go with what you see fit"):** D1–D11 taken as recommended above. D12: no write access granted yet, so gateway work stays on branches in Blaze-agent, never its `main`.

### Deviations from this plan and calls taken (Hark, P0, owner: "go with what you see fit", 2026-11-12)
Each line is a place the work differs from this file or 06-team-plan, or a choice the plan left open. Change any of them by saying so; each names where to undo it.

| Ticket | What the plan said | What was done | Why / where to undo |
|---|---|---|---|
| Process | MRs merged after CI | No CI; pure tests run locally; landed by cherry-pick to `main`, MR closed with a note (the token can't press Merge); every commit `[skip ci]` | Owner rule 2026-11-12 |
| P0-SH-01 | Orbit veil 60% | 82% dark / 95% light | At 60% the Earth's limb in argus_space.webp breaks AA text contrast. `ArgusTokens.orbitVeil` |
| P0-SH-01 | Spec colours as given | AA-adjusted variants, a new `meta` token, an invented cobalt-night light variant | AA contrast; 04 had no light Cobalt night. `ArgusTokens.kt` |
| P0-SH-01 | Haze `hazeSource` / `hazeEffect` | Haze 1.0.2 API (`haze` / `hazeChild`); pulls Compose 1.7.5 | Haze 1.0.2 is the version pinned; newer Haze renamed the API. `ArgusGlass.kt` |
| P0-SH-01 | Instrument Serif + JetBrains Mono | Not bundled: system fallback | Not bundled yet; when they are, add their OFL notices. `ArgusType.kt` |
| P0-SH-02 | All icons from A-team `icons.js` | 76 from A-team, 20 from lucide-static 0.460.0 (ISC) | A-team lacked them. `tools/gen-lucide.mjs` |
| P0-SH-02 | Manager sphere = `manager.png` | Cobalt redraw in code with the ARGUS "A" | Keeps the orbs vector, no raster asset. `ui/argus/BotOrb.kt` ManagerOrb |
| P0-API-01 | `blaze_context: "full"` (flag not specified) | Sent on every gateway call, unconditional | Pure gain; gateway ignores unknown fields. `Cloud.kt` |
| P0-API-01 | Served model from `X-Blaze-Model` | Gateway 0.8.45+ sends it only in debug builds; the `X-Blaze-Route` token is read instead, **always on** (45d4be86). Same-model pinning and empty-route avoid now run for everyone; not yet tried on a device | Owner "do what's best". `agent/LiveModelsFeed.kt served()` |
| P0-API-01 | Live model pick | Behind flag `live_models`, default off | Needs a device check first. `agent/LiveModels.kt` |
| P0-QA-01 | Spec index per ticket | 833 specs mapped by file rule, then keyword, then file default (first pass; `By` column). P1-SH-03 and P7-CP-03 hold large default buckets | Ticket owners recheck their rows. `tools/gen-specs.mjs` |
| P0-IL-02 | Add a LICENSE to a-team before copying its code (K16) | Owner granted instead (2026-11-11, P0-PLAN): A-team code may be copied under MIT; a-team not edited. The grant is recorded in THIRD_PARTY_NOTICES under ARGUS overhaul | Add a LICENSE to a-team only if you want it public |
| P0-QA-01 | Live eval run | Not run: needs an ARGUS `bz_` token in `ARGUS_TOKEN`. Mock and self-check runs pass | Run when a token is given |
| P0-QA-01 | guard.js / models.js shared | Vendored in `evals/vendor/` until P2-SF-07 | |
| P0-MG-01 | Fixtures from the A-team Demo team | Built by hand from `demo()` @7c4b459a at a fixed clock, not exported from a phone | Swap in a real Copy backup when one is to hand |
| P0-MG-01 | (open) | Imported open jobs land `confirmed = 0`: nothing starts by itself | `docs/overhaul/ateam-export.md` §4.2 |
| P0-MG-01 | (open) | Bot emails dropped; only the Manager's chat is imported | §4.1, §4.6 |
| P0-IL-02 | Notices for Simple Icons (CC0) and noVNC (MPL-2.0) | Not added: neither is in the repo yet | Added by the ticket that bundles them |
| P0-IL-03 | `tools/check-do-not-port.sh` | `tools/check-do-not-port.mjs` (Node, like the other tools) + a node test; also removed `composio` from a `team/Models.kt` comment (D5) | Portable regex and a testable rule list |
| Tests | Full test suite green | Not run: no Gradle build until "build". Unverified: TeamSchemaTest, TeamDbTest, OrbShotsTest, SpaceWiringTest, ThemeEverywhereTest, full ContrastTest and CloudTest, Android compile of Agent.kt, AgentSessionDriver.kt, LlmClient.kt, LiveModelsFeed.kt | Runs with P0-IL-01 |

### Next 10 tickets (all of P0; 1–8 in parallel)
| # | Ticket | Owner |
|---|---|---|
| 1 | ✓ P0-TM-01 `team/` + team.db schema + pure models — done, merged to main (MR !40, b765db55) | TM |
| 2 | ✓ P0-AG-01 `BotEngine` + `LocalEngine` seam (Agent.kt +≤20 lines) — done, on main (7f12551d; Agent.kt +7) | AG |
| 3 | ✓ P0-SH-01 `ui/argus` A-team tokens behind `argus_look` — done, on main (21fe3ebb) | SH |
| 4 | ✓ P0-SH-02 Lucide generator + `BotOrb` + Manager sphere — done, on main (ccf1a382) | SH |
| 5 | ✓ P0-API-01 `blaze_context:"full"` + `LiveModels` — done, on main (cd942dad) | API |
| 6 | ✓ P0-QA-01 spec index + A-team evals harness in `evals/` — done, on main (21513181) | QA |
| 7 | ✓ P0-MG-01 `argus-team-export.v1` format + fixtures — done, on main (0167d249) | MG |
| 8 | ✓ P0-IL-02 repo hygiene, notices, ask for a-team LICENSE — done (daa6cbe0; A-team MIT grant recorded) | IL |
| 9 | ✓ P0-IL-03 do-not-port register — done (60818dab) | IL |
| 10 | P0-IL-01 release 0.52.4 (when the owner says build) | IL |
