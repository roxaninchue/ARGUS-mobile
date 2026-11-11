# 06 · Integrated team plan — ARGUS overhaul

> Slice 6 of the ARGUS overhaul (see `overhaul.md`). Owner: Specialist 6 (integration lead / engineering program manager). 2026-11-11.
> Inputs, all read in full: [`01-bothq-inventory.md`](01-bothq-inventory.md) (BH-01…BH-89), [`02-ateam-inventory.md`](02-ateam-inventory.md) (AT #1…#72 + design language L), [`03-argus-mobile-map.md`](03-argus-mobile-map.md) (inventory a.1–a.9, merge map c.1 #1–25, constraints d.1), [`04-design-ia.md`](04-design-ia.md) (design system, Team/Chat/Life/Desk IA, ~150-row feature → location table, 13 screen specs), [`05-agent-ecosystem.md`](05-agent-ecosystem.md) (agent model, routing, 14 domain agents, memory scopes, engines, API v2, safety, proactivity, data models, E0–E8).
> Status: **v1 complete**. Nothing here is built; owner rules hold throughout: commit straight to `main`, every commit title ends with `[skip ci]`, **no build until the owner says build**.

## Contents
0. How to read this plan
1. Reconciliation: where the slices disagree, and the default we take
2. Decisions for Finn
3. Target architecture (one picture)
4. Phases and milestones (with versions)
5. Workstreams and owners
6. Task backlog per phase (ticket IDs)
7. Full coverage matrix (every feature of slices 1, 2, 3 → phase + ticket + UI location)
8. Risks and mitigations
9. Test and QA plan (real arm64 phone, evals)
10. Migration plan for A-team users
11. How to pick this up

---

## 0. How to read this plan

- **Ticket ID** = `P<phase>-<workstream>-<nn>`, e.g. `P2-SF-03` = phase 2, Safety, ticket 3. Workstream codes are in §5.
- **Effort** (one specialist agent or engineer, focused): **S** ≤ 1 day · **M** 2–3 days · **L** 4–7 days · **XL** > 1 week (split before starting).
- **Feature IDs** in the coverage matrix: `BH-nn` = slice 1, `AT-nn` = slice 2 feature index #nn, `AM-nn` = slice 3 inventory row (numbered in §7.3, in the order of 03 §a), `MM-nn` = slice 3 merge-map row c.1 #nn, `NEW-nn` = things slices 4/5 designed that exist in none of the three repos.
- **UI location** uses slice 4 names: modes **Team · Chat · Life · Desk** (Notch switcher), **Thread**, **Bot profile → tab**, **You/Settings → page**, **Inbox**, **Voice mode**, **Computer viewer**, **Spotlight**.
- Paths are relative to `app/src/main/java/com/blaze/assistant/` unless they start with a repo folder.

---

## 1. Reconciliation: where the slices disagree, and the default we take

Every conflict found across slices 1–5, with the recommended default and why. Items marked **→ Finn** also appear in §2 because they change product behaviour or cost; everything else is decided here and the plan proceeds on it.

| # | Conflict | What the slices say | Default we take | Why |
|---|---|---|---|---|
| R1 | **Build order: design first vs team core first** | 04 §7.1 starts with `ui/argus` tokens + shell; 05 §11.2 starts with E0 team core; 03 §d.4 puts the A-team look *last* (step 10) | **Both in the first big release.** P0 lays tokens, `BotOrb`, `team.db` and the `BotEngine` seam invisibly; P1 (0.53.0) ships the A-team shell *and* Team core together. Then follow 05's order (E1 policy → E2 memory → apps → E3/E4 team talk + domains → E5 proactivity → E7 computers → E8 voice/sync). | A Team mode without bots is empty and bots without the shell have nowhere to live. Shipping the look last (03) would mean re-skinning every new screen twice. Safety (E1) must precede proactivity, cloud and connectors (05 rule kept). |
| R2 | **Ubuntu vs Alpine** | AT: Ubuntu 24.04 per bot; AM: Alpine default; 03: Alpine system + Ubuntu optional workspace; 04 [Q1]: one runtime | **One proot runtime, two rootfs roles.** Alpine stays the *system* distro (3.5 MB, run_code, git, BlazeRouter). Ubuntu 24.04 is the opt-in *workspace* distro (Wi-Fi, arm64 only, ~800 MB) with **one shared rootfs and a home + tmux window per bot**. Without Ubuntu, bot homes live in Alpine. **→ Finn** (storage). | Keeps the APK and first run small, keeps AT's real dev workspace for bots that need it, never one rootfs per bot (800 MB each). |
| R3 | **Connectors: argus-connect vs on-phone OAuth vs Composio** | AT/03/05: argus-connect is the backend; AM: tokens on phone; BH: Composio | **argus-connect is the one backend for third-party SaaS** (keys server-side, multi-device). On-phone `Connectors.kt` only for device-bound apps (Spotify playback, Classroom, GitHub device flow, Accessibility/SMS/calendar provider). MCP client kept for user servers. **Composio only as an optional server-side provider inside argus-connect**, never in the app. **→ Finn** (privacy model change). | One catalog, one approval path, nothing paid required (05 principle 5). |
| R4 | **One continuous chat vs many chats** | AM: one mind (no chat list since 0.24); AT v2.19: saved Manager chats + drawer; BH: one thread per bot + "new conversation" generation bump; 03: main thread stays one mind | **Chat mode stays one continuous mind with the Manager. Every bot has its own thread.** "New chat" (✎) is a BH generation bump (fresh context, memory kept); AT's drawer is ported as a **history drawer over generations + Projects**, FTS-searchable. **→ Finn.** | Keeps AM's "one mind" promise and memory continuity, gives AT users the drawer they had, and BH's fresh-context button. |
| R5 | **Persona names and roster** | 04: 10 leads (Vita, Ledger, Chief of Staff, Hearth, Muse, Tutor, Atlas, Studio, Journal, Hand); 05: 14 domain agents (Tempo, Inbox, Pulse, Ledger, Career, Forge, Hearth, Pantry, Atlas, Circle, Tutor, Clerk, Scout, Studio) | **05's 14 agents are canonical; 04's 10 domains become *Life areas* (tiles/hues) that group them:** Health {Pulse} · Money {Ledger, Scout} · Work {Career, Inbox, Tempo, Forge} · Home {Hearth, Pantry, Clerk} · People {Circle} · Learning {Tutor} · Travel {Atlas} · Create {Studio} · Mind {Manager-owned: life logs, Time Capsule, memory graph, wellbeing via Pulse `health:mind`} · Phone {Manager-owned; Operator worker does the taps}. Display = persona name with the label underneath ("Ledger · Money"). **→ Finn** (names vs plain labels). | 05 owns the agent model; 04 owns presentation. Areas keep the Life grid at 10 calm tiles while the agent roster stays fine-grained. |
| R6 | **Manager name** | AT: "Manager" (locked); 04: Manager shown as the ARGUS cobalt "A"; AM: the assistant is "Argus" | **The lead bot is named "ARGUS", label "Manager", gold Lead badge, fixed id `manager`, undeletable, core soul locked + editable Standing rules.** **→ Finn.** | The product's own name on the front door; "Manager" stays as the role. |
| R7 | **Seeded roster on first run** | 04: empty team → "Build your team"; 05: seed Manager + Tempo + Inbox, others via setup picker; AT: Demo team | **Seed ARGUS + Tempo + Inbox**; "Set up my life team" (3 questions + picker) offers the rest; **Forge pre-proposed for Finn**; Demo team kept in the empty state and Settings → Team. | 14 bots on day one overwhelms (05 §3.1); three gives a useful team immediately. |
| R8 | **Model choice per bot** | BH: provider/model picker; AT: no picker, live list + own Gemini key per bot; AM 0.52.3: picker removed, always Auto | **Automatic by default everywhere**; Bot profile → Engine shows *Depth* (Spark/Flare/Inferno ≈ Insight/Oracle/Panoptes) and, under Advanced, an **own-key provider (Gemini)** pinned model with AT cool-down. No free-text model id. `/v1/models/live` adopted under the hood (`LiveModels`). | Honours the owner's 0.52.3 decision while keeping AT's per-bot Gemini users whole. |
| R9 | **Hermes in v1** | 01/03: optional remote adapter only; 05: `HERMES` engine kind in E7 | **Not in 1.0.** `BotEngine` interface lands in P0 so Hermes is pluggable; `HermesEngine` ships in **1.1.0** after the MCP relay (P8) exists. **→ Finn.** | Python/server-side, duplicates the native loop; needs the relay first. |
| R10 | **Self-hosted / cloud computer** | BH: Orgo screen per bot; AT: Orgo or Codespaces with lease; 05: self-hosted desk image first; 01: phone first | Provider order: **LocalBrowser (phone, take over/hand back) → Codespaces (60 free core-h/mo) → self-hosted desk (Docker: Xvfb + fluxbox + Chrome + x11vnc + noVNC, BH box scripts) on Finn's PC via Desktop link or a free VM → Orgo (optional, cost chip)**. Never start without a COMPUTE_PAID card; AT lease rules everywhere; never bring back the on-phone XFCE desktop. **→ Finn** (where the self-hosted desk runs). | Zero-budget default, keeps BH's watch/take-over UX. |
| R11 | **Skills sync backend** | AM: blaze-auth `/v1/skills`; AT: argus-connect `/v1/me/skills` (+ Polish, originals) | **argus-connect**; one-time migration in `SkillSync`; bundled `assets/skills` stay the offline seed; SKILL.md format everywhere, matched on demand (not all inlined, unlike BH). | Per-user, Polish and originals already there. |
| R12 | **Memory store and sync path** | AM: Mind/blaze.db; AT: local profile + bot/team notes; BH: MEMORY.md/USER.md files; 03/05 agree | **Mind/blaze.db is the store; `mem_scope` side table (never alter `memories`); SOUL/MEMORY/USER.md are editable projections; memory syncs only through E2E `CloudSync`, never `argus-agents-api`.** | Desktop/CLI share blaze.db; Worker is not E2E. |
| R13 | **Bot cap and concurrency** | BH: 4 bots max (Orgo screens); AT: one stream at a time; AM: BgAgents RAM cap | **No bot cap; concurrency cap** = `AgentBudget.cap()` (4/3/2/1 by RAM), one running job per bot, cloud runs capped at 3 gateway-side. | Bots are idle data; only running jobs cost RAM. |
| R14 | **Tool cap in the prompt** | AT: 128 (connectors ≤96); 05: 64 + on-demand groups | **64 in the prompt, connected apps first, other groups via `load_tools`**. | Free models degrade with long tool lists; ToolRouter already loads on demand. |
| R15 | **Halo/Duo reversal** | AM 0.51 merged Halo into Argus ("no separate persona for now"); BH/AT reintroduce many personas | **Bots are personas on one engine.** Halo Studio becomes Bot profile → Soul → Personality; Duo/DuoAll code is generalised into Rooms. **→ Finn** (confirm the reversal). | Required by the brief; one runtime avoids the old two-companion cost. |
| R16 | **Top-level navigation** | AM: Home \| Chat \| Projects pill; AT: Bots \| Chat notch; 03: Home \| Chat \| Bots | **04's four modes Team · Chat · Life · Desk** via Notch + swipe + `/team /chat /life /desk`; no bottom tab bar. Projects move to Desk (surfaced in Life). | Gives life-domains (Life) and work collections (Desk) homes without crowding threads. |
| R17 | **Look details** | AT monochrome glass + Lucide; AM Space view + Argus Blue + Material Rounded | **A-team look** (`ui/argus` tokens from 04 §2), **cobalt only for the Manager/brand**, Lucide in new UI, Space backdrop kept as the optional *Orbit* ground; light `--mute` darkened to `#64646C` and AA text variants (04 §5.2). Orb-only avatars; **no Bot HQ / Grok artwork** (not MIT). | Brief: A-team design is the base; accessibility needs the AA fixes. |
| R18 | **Voice backend** | BH: xAI realtime; AM: on-device + Gemini voice; 05: on-device/gateway default | **On-device default**, Gemini Live with user key, **xAI realtime optional (BYO key, gateway-minted 300 s token, never a key on the phone)**; BH two-agent pattern adopted on AM CallScreen. | Zero-budget; keeps BH's best voice UX. |
| R19 | **X search** | BH: xAI `x_search` (paid); 05: gateway `site:x.com` default | **Default: web search `site:x.com` with an honest "limited without an X key" label; full `x_search` when the user adds an xAI key.** | No good free X API. |
| R20 | **Cloud routines (phone off)** | 05 §8.3 proposes Cron Trigger → `/v1/runs` | **Deferred to 1.1.0**; v1 routines run on the phone (AlarmManager + WorkManager backstop). **→ Finn.** | Needs agents-api v2 + CloudRunEngine first; adds server cost. |
| R21 | **A-team app future** | AT K3/M9: different package + key; 03 c.3 #8: retire-and-migrate vs companion | **Retire and migrate**: A-team v2.20 "Move to ARGUS" (export + sync push), ARGUS imports; A-team archived at 1.0. **→ Finn.** | Two apps doubles support; package/key prevent in-place update. |
| R22 | **Stage (AM chat playground)** | 04 [Q4] keep or retire | **Keep, behind Chat mode ⋯ → Edit stage**, re-skinned in P9; revisit after 1.0 usage. | No cost to keep; power users rely on it. |
| R23 | **Life-logs QS tile** | 04 [Q5] privacy review | **No QS tile for recording in 1.0**; Life logs start from the Mind area or `/lifelog`, with the existing ongoing notification. | Mic toggles from the lock screen need a privacy review. |
| R24 | **Repo `gateway/` folder** | 03: stale 0.3.0 copy; live gateway in Blaze-agent | **Mark read-only now (README), delete at 1.0.** All gateway asks (relay, `/v1/x-search`, voice token mint, `blaze_context`) are filed against `blazeinchue/Blaze-agent`. | Avoids edits in the wrong place. |
| R25 | **argus-agents-api v1 → v2** | AT: KV doc, shared token; 05: D1, bz_ auth, delta sync, approvals, events | **v2 in P8, moved into `argus-mobile/agentsapi/`**; v1 kept for self-hosted single-user. | KV races and one global token are real bugs. |
| R26 | **Approvals model** | BH: Allow/Always/Don't inside the tool call; AM: ConfirmGate taint + CommandPolicy; AT: risky regex + yes cards | **One `Policy.decide`** (05 §7.1 order) with BH's in-call cards + 25-min expiry, AM's taint rule, AT's proactive-never-acts rule. Port BH UX on top of AM's gate, never instead of it. | BH gates only Composio writes; regression risk otherwise. |
| R27 | **Background jobs** | AT `bgjobs.js` WIP unwired; AM `JobKeeper` done | **Use AM `JobKeeper`/`TaskWorker`**; port only AT's "resume the same chat on completion" ping and the detached-supervisor pattern. | Don't port dead code. |
| R28 | **Needle runtime** | AT: cactus.wasm in WebView; AM: native `libneedle.so` | **Native `FastLane`**; drop the 37 MB wasm; regenerate `needle-ft` data with team commands (`route_to`, `set_routine`, `remember`). | APK size, no WebView. |
| R29 | **Domain colours** | 04 adds life-domain hues; AT colour only for meaning | Hues only as a 3 dp tick and tile icon; bot orb colour is the identity. | Keeps the monochrome calm. |
| R30 | **targetSdk 35** | 03 d.2: needed for Play, Android 15 dataSync cap | **After 1.0** (separate release), unless Play distribution is wanted earlier. | Avoids mixing platform churn into the overhaul. |

---

## 2. Decisions for Finn

Short list; each has a recommended answer that the plan already assumes. A "no" changes only the tickets named.

| # | Decision | Recommended answer | If you choose otherwise |
|---|---|---|---|
| D1 | Chat model: one continuous mind with ARGUS + one thread per bot, "new chat" = fresh context with a history drawer (R4) | **Yes** | Many independent Manager chats → rework `P1-SH-04`, `P1-TM-02` |
| D2 | Bot names: persona names (Ledger, Atlas, Pulse…) with the label underneath, renamable (R5) | **Yes, names + label** | Plain labels ("Money") → copy change in `P5-AG-01` only |
| D3 | The lead bot is called **ARGUS** (role "Manager"), undeletable, core soul locked (R6) | **ARGUS** | "Manager" → string change |
| D4 | Linux: Alpine stays system; Ubuntu 24.04 opt-in workspace (~800 MB, Wi-Fi, arm64) shared by all bots with a home each (R2) | **Yes** | Ubuntu by default → bigger first run in `P7-CP-01` |
| D5 | Third-party apps through **argus-connect** (credentials server-side); on-phone tokens only for device-bound apps; Composio never in the app (R3, R11) | **Yes** | Keep on-phone tokens → drop `P4-CN-01` server path, privacy copy changes |
| D6 | Models: Automatic everywhere; per-bot Depth + optional own Gemini key; no model-id picker (R8) | **Yes** | Full picker → add Engine provider/model UI in `P3-SH-01` |
| D7 | Hermes engine: after 1.0 (1.1.0), not in v1 (R9) | **Defer to 1.1** | In v1 → pull `P10-AG-01` into P8 (+~2 weeks) |
| D8 | Where the self-hosted cloud desk runs: your PC via Blaze Desktop link first, an always-free VM second; Codespaces is the zero-setup default (R10) | **Codespaces default, PC second** | VM first → infra ticket in `P7-API-01` |
| D9 | Cloud routines that run with the phone off: after 1.0 (R20) | **Defer** | In v1 → add a P8 ticket on agents-api Cron + `CloudRunEngine` |
| D10 | Retire A-team after migration (v2.20 "Move to ARGUS", archived at 1.0) (R21) | **Retire** | Keep as companion → maintain both; `P9-MG-01` dropped |
| D11 | Confirm the multi-persona reversal of the 0.51 "Halo merged" decision (R15) | **Confirm** | Single persona → the whole team model is off; stop here |
| D12 | Gateway work goes to `blazeinchue/Blaze-agent` (relay, voice token, x-search, `blaze_context`); we need write access or an owner there (R24) | **Yes, give access** | Without it P7 take-over, P8 voice/xAI and MCP relay slip |

---

## 3. Target architecture (one picture)

```
┌──────────────────────────── ARGUS app · com.blaze.assistant (one :app module, Kotlin + Compose) ─────────────────────────────┐
│  ui/argus (A-team tokens, Lucide, BotOrb, kit)                                                                               │
│  Shell: TopBar(me·search·bell·+) · Notch[Team|Chat|Life|Desk] · Composer(+ · @bot · /cmd · $ · #file · mic) · Inbox · Voice  │
│        Thread / Bot profile / Computer viewer / Settings (open by name)                                                      │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  team/   Bot (persistent Spec) · TeamStore · Rows · Jobs queue · ManagerTools · TeamBus(send_to_bot, hop≤3) · Rooms · Domains │
│  agent/  BotEngine ─┬─ LocalEngine (Agent.Session, frozen size) ─ lanes · guards · skills · FastLane(Needle)                 │
│                     ├─ CloudRunEngine (/v1/runs)          └─ HermesEngine (1.1, via MCP relay)                               │
│  policy  Policy.decide(ActionClass, taint, autonomy, per-tool rules) → cards (approval·draft·question·connect) → audit_log    │
│  memory  Mind/blaze.db + mem_scope · SOUL/MEMORY/USER.md projections · chat tier · cache tier                                 │
│  proactive SignalBus → domain proposers → Manager arbiter (caps, quiet hours, dial) · routine/ (cron·event·checkin·watch)    │
│  computer/ Lease + providers {LocalBrowser, Codespaces, SelfHostedDesk, Orgo} · terminal/ (Alpine system + Ubuntu workspace,  │
│            home + tmux window per bot) · browser/ (per-bot profiles, take over)                                              │
│  connect/ ArgusConnect · Mcp client · device OAuth · CloudSync(E2E) · TeamSync   voice/ (on-device · Gemini · xAI opt.)      │
│  unchanged: phone/ browser/ media/ mediaapps/ movies/ notify/ life/ device/ stage/ setup/ update/ appstore/ siteapps/ link/  │
│  storage: blaze.db (shared w/ Desktop, side tables only) · team.db (new) · cache.db · SecretVault/Keystore                   │
└───────────────┬──────────────────────────┬──────────────────────────┬──────────────────────────┬──────────────────────────┘
                │ model calls              │ app tools / skills        │ roster·jobs·routines·     │ confirmed only
                ▼                          ▼                           ▼ approvals·events          ▼
   Blaze Gateway (Blaze-agent)    argus-connect                 argus-agents-api v2 (D1,     Cloud desk: Codespaces /
   /v1/chat /v1/runs              /v1/me/tools|execute|skills   REST + MCP, PATs)            self-hosted Docker / Orgo
   /v1/models/live, relay WS      (Composio optional, server)   ← Desktop, Claude, Cursor    VNC via gateway relay
                └──────────────── all authenticated with the ARGUS bz_ token from blaze-auth ────────────────┘
```

---

## 4. Phases and milestones (with versions)

**Versioning rule (repo, STATUS.md / about.md):** every release bumps the patch (0.52.3 → 0.52.4); after a patch of .9 the next release rolls over to the next minor (0.52.9 → 0.53.0); a new big feature takes the next minor directly. `versionCode` +1 per release. Current: **0.52.3 / vc 190** plus three unreleased rounds on `main`. Each phase below is **one big feature = one minor**, shippable on its own; fixes between phases are patch releases (0.53.1, 0.53.2…). Releases happen only when the owner says build.

| Phase | Version (vc) | Name | Ships (user-visible) | Exit criteria | Slice refs |
|---|---|---|---|---|---|
| **P0** | **0.52.4** (191) | Groundwork | The three unreleased rounds already on `main` (one look, icons + 90 % size, reply ledger, proactivity dial, pinned facts / Holding, Spotlight fix). New code is invisible: `team.db`, models, `BotEngine` seam, `ui/argus` tokens + Lucide + `BotOrb` behind a flag, `LiveModels`, `blaze_context: "full"`. | CI 9/9 green; low-RAM smoke 7/7 ≤ 700 ms; `Agent.kt` grows ≤ 20 lines; TeamDb/LiveModels/Orb tests green. | 03 d.4 #1, 04 §7.1 #1, 05 E0 (data) |
| **P1** | **0.53.0** (192) | One look, one team | A-team shell (Notch, Team·Chat·Life·Desk, composer, chips), Team mode, ARGUS as a Bot, bot threads, create/edit bots with orbs, seeded Tempo + Inbox, delegation as Jobs running on BgAgents, Desk → Jobs, Chat mode re-skinned with history drawer, Settings re-tree with aliases, A-team backup import (bots + jobs). | 05 E0 exit: ARGUS + 2 domain bots chat and delegate jobs on-device; every old settings id opens; shots matrix reviewed; real-phone P1 checklist passed. | 04 §7.1 #2–3, 05 E0, 03 c.1 #1–3, #14, #23 |
| **P2** | **0.54.0** (193+) | Safe hands | One policy engine; approval/draft/question/connect cards in threads; Inbox (bell + Desk); Need-you banner; Permissions page; Activity (audit log); Pause all; per-bot notifications with Approve/Deny; A-team guard ports (completeness gate, claim checks, fence repair, verified done). | Policy table tests (≥ 60 cases) + ported A-team guard evals green; no ConfirmGate/CommandPolicy regression; refusals suite ≥ A-team baseline. | 05 E1, 01 BH-23–28, 02 C3/E1 |
| **P3** | **0.55.0** | Souls & memory | Bot profile (Overview, Soul with SOUL.md editor, Memory, Engine); memory scopes; "What the team knows"; stated/inferred + auto-learn with Undo; per-bot model policy; A-team memory/profile import. | `memories` schema hash unchanged; projections round-trip; forget verified by search; scoped sync green. | 05 E2, BH-05/67/88, AT H6/H7 |
| **P4** | **0.56.0** | Apps & skills | Apps & connectors page on argus-connect; per-bot Apps + Skills tabs; connector tools with write approvals; Connect cards that resume the turn; skills on argus-connect with Polish; per-bot MCP. | No third-party token on the phone for argus-connect apps; tool cap 64 enforced; connector e2e on phone. | AT H2–H4, BH-35/49/72, 03 c.1 #8 #10 |
| **P5** | **0.57.0** | Team talk & life | Bot-to-bot (`send_to_bot`, Handoff cards), groups/rooms + huddle, subagents/workers, 14 domain agents + setup picker, routing v2 + Needle team commands, Life mode (areas, domain pages), People graph, roster + Compose graph. | 05 E3+E4 exits; routing eval ≥ 90 %; hop/loop tests; RAM within cap on 4 GB phone. | 05 E3/E4, BH-45/46/57–59, AT C7, 04 §4.2/4.6/4.12 |
| **P6** | **0.58.0** | Proactive team | Routines per bot (cron/event/check-in/watch) with Desk → Routines; SignalBus + Manager arbiter + one Proactivity dial; check-ins, morning brief, Today strip, weekly review; pop-up + edge glow + haptics; widgets, QS tiles, share-to-bot, deep links, launcher shortcuts. | Closed-app routines fire on a real phone across Doze/reboot; caps and quiet hours hold in tests. | 05 E5, BH-40–43/61–63, AT I1–I4, 04 §3.4D |
| **P7** | **0.59.0** | Computers | Ubuntu workspace with a home + tmux window per bot; Terminal tabs per bot; file tools; Library; code review gate; per-bot browser profiles with take over/hand back; cloud desk providers with leases; LiveScreenCard + Computer viewer; VNC take-over via gateway relay; self-hosted desk image. | Lease stops with the app killed; take-over round trip on a phone; proot on A07/A12 class; storage shown. | 05 E7 (minus Hermes), AT G1–G7, BH-50–56/65 |
| **P8** | **0.60.0** | Voice & everywhere | Voice two-agent calls on every bot + Voice mode; per-bot voices; X search (optional key); argus-agents-api v2 + TeamSync + PATs + remote approvals; on-device MCP relay (off by default); CloudRunEngine; A-team v2.20 "Move to ARGUS". | Fake-mic call test passes on device; two-device sync conflict tests; MCP client (Claude/Cursor) sees the team. | 05 E6/E8, BH-69–71, AT H8/J1–J3 |
| **P9** | **1.0.0** | ARGUS 1.0 | Every remaining AM surface on the new look; AM features wired to their domain bots; A-team retired; full evals + real-phone matrix + accessibility audit; docs. | Release gate in §9 all green; coverage matrix rows all "done". | all |
| P10 | 1.1.0 | Open engines (post-1.0) | HermesEngine, cloud routines, Composio adapter surfacing, subscription sign-in in proot (ToS-flagged), targetSdk 35. | — | 05 E7 (Hermes), R9/R20/R30 |

Version arithmetic note: if more than nine fix releases are needed inside a phase (e.g. 0.53.9), the rollover rule moves the next release to the next minor and the following phases shift by one minor; keep the phase names, not the numbers, as the stable reference.

---

## 5. Workstreams and owners

Each workstream is owned by one specialist agent (a dispatched role) with a named package scope. The integration lead (Specialist 6) owns sequencing, version bumps, CHANGELOG/STATUS/HANDOFF, this plan and the coverage matrix.

| Code | Workstream | Owner (specialist agent role) | Package / repo scope | Phases with most load |
|---|---|---|---|---|
| **SH** | Shell & Design | Design-system agent | `ui/argus/**` (new), `ui/space/**` (retire), `ui/team/**`, `ChatScreen.kt` (shell part), Settings tree | P0, P1, P3, P5, P6 |
| **TM** | Team & Manager | Team-runtime agent | `team/**` (new): Bot, TeamStore, Rows, Jobs, ManagerTools, TeamBus, Rooms; `orchestra/` hooks | P0, P1, P5 |
| **AG** | Agents & Memory | Agent-runtime agent | `agent/engines/**`, `agent/Mind*`, `memory/**`, `mem_scope`, souls, domain templates `assets/domains/**`, model policy | P0, P3, P5, P8 |
| **SF** | Safety | Safety agent | `policy/**` (new), `ConfirmGate`, `CommandPolicy`, guards, cards, Inbox, audit, SpendGuard | P2 (then reviews every phase) |
| **CN** | Connectors & Apps | Connectors agent | `connect/ArgusConnect.kt`, `connect/Mcp*`, `connect/SkillSync.kt`, `ToolRouter` caps; repo `argus-connect` | P4, P8 |
| **CP** | Computers & Terminal | Computers agent | `terminal/**`, `computer/**` (new), `library/**` (new), `browser/` profiles, review gate; `desk/` image | P7 |
| **PR** | Proactivity & Routines | Proactivity agent | `routine/**`, `proactive/**`, SignalBus, arbiter, brief | P6 |
| **VC** | Voice | Voice agent | `voice/**` (+ `voice/xai/`), CallScreen, Voice mode | P8 |
| **API** | API / Gateway & Sync | Platform agent | `agentsapi/` (new, from a-team `api/`), `connect/CloudSync*`, `TeamSync`, `LiveModels`, gateway asks in `Blaze-agent` | P0, P3, P7, P8 |
| **LF** | Life & Phone continuity | Continuity agent | everything AM that stays: `phone/ browser/ media/ mediaapps/ movies/ notify/ life/ device/ stage/ bank/ appstore/ siteapps/ link/ update/` — re-skin + domain wiring | P5, P9 |
| **MG** | Migration | Migration agent | A-team import (`team/ATeamImport.kt`), a-team repo export + v2.20, settings aliases | P0, P1, P3, P8, P9 |
| **QA** | QA / Evals | QA agent | `app/src/test/**`, shots, `evals/` (ported A-team harness), real-phone checklists | every phase |
| **IL** | Integration lead | Specialist 6 | versions, CHANGELOG, STATUS, HANDOFF, `docs/overhaul/**` | every phase |

Rules for every workstream: new capability lives in new files/packages (never grow `Agent.kt` beyond the seam); pure logic in pure files with JVM tests; update the matching source-scan/screenshot tests in the same commit; no `@Ignore`, no threshold changes; one ticket = one or a few commits, each revertable, title ending `[skip ci]`.

---

> **Partial publish:** sections 6–11 (backlog, coverage matrix, risks, QA, migration, pick-up guide) are being written and will land in the next commit.
