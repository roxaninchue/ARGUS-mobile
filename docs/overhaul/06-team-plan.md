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

## 6. Task backlog per phase

Each ticket is sized for one specialist agent; IDs are stable (never renumber; add `-b` suffixes for splits). **Covers** lists the feature IDs from §7 the ticket delivers. Deps use ticket IDs; `P5-*` means all of that phase's build tickets.

### 6.0 Load by workstream and phase (tickets / ≈ agent-days)

| WS | P0 | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 | P9 | P10 | Total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SH Shell & Design | 2 / 5 | 6 / 24 | — | 2 / 8 | — | 4 / 16 | 2 / 8 | — | — | — | — | 16 / 61 |
| TM Team & Manager | 1 / 2.5 | 7 / 23.5 | — | — | — | 4 / 19 | — | — | — | — | — | 12 / 45 |
| AG Agents & Memory | 1 / 2.5 | — | — | 6 / 18 | — | 1 / 5.5 | — | — | 1 / 2.5 | — | 1 / 5.5 | 10 / 34 |
| SF Safety | — | — | 8 / 29 | — | — | — | — | — | — | — | — | 8 / 29 |
| CN Connectors & Apps | — | — | — | — | 7 / 20.5 | — | — | — | 1 / 1 | — | 1 / 2.5 | 9 / 24 |
| CP Computers & Terminal | — | — | — | — | — | — | — | 9 / 31.5 | — | — | 1 / 1 | 10 / 32.5 |
| PR Proactivity & Routines | — | — | — | — | — | — | 5 / 18.5 | — | — | — | 1 / 2.5 | 6 / 21 |
| VC Voice | — | — | — | — | — | — | — | — | 2 / 8 | — | — | 2 / 8 |
| API API/Gateway & Sync | 1 / 2.5 | — | — | 1 / 2.5 | 1 / 5.5 | — | — | 1 / 5.5 | 3 / 13.5 | — | — | 7 / 29.5 |
| LF Life & Phone continuity | — | — | — | — | — | — | — | — | — | 3 / 9 | — | 3 / 9 |
| MG Migration | 1 / 1 | 1 / 2.5 | — | 1 / 2.5 | — | — | — | — | 1 / 2.5 | 1 / 1 | — | 5 / 9.5 |
| QA QA/Evals | 1 / 2.5 | 1 / 2.5 | 1 / 2.5 | — | 1 / 2.5 | 1 / 2.5 | 1 / 2.5 | 1 / 2.5 | 1 / 2.5 | 1 / 5.5 | — | 9 / 25.5 |
| IL Integration lead | 3 / 3 | — | — | — | — | — | — | — | — | 1 / 2.5 | 1 / 2.5 | 5 / 8 |
| **All** | **10 / 19** | **15 / 52.5** | **9 / 31.5** | **10 / 31** | **9 / 28.5** | **10 / 43** | **8 / 29** | **11 / 39.5** | **9 / 30** | **6 / 18** | **5 / 14** | **102 / 336** |

Effort weights: S = 1, M = 2.5, L = 5.5 agent-days. With 4–6 specialist agents in parallel a phase takes roughly its agent-days ÷ 4, plus one owner build/review round.

### 6.1 P0 · 0.52.4 · Groundwork

_Invisible foundations + ship the unreleased rounds already on main._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P0-IL-01** ✓ d35d26f7 | Release 0.52.4: bump versionName/versionCode (0.52.4 / 191), CHANGELOG + STATUS + about.md entries for the three unreleased rounds | `app/build.gradle.kts, CHANGELOG.md, STATUS.md, app/blaze-docs/about.md` | Owner says build → CI 9/9 green on GitHub mirror; versions.json updated by release flow; no applicationId/signing change | — | S | AM-15 |
| **P0-IL-02** ✓ daa6cbe0 | Repo hygiene: gateway/ README 'read-only stale 0.3.0 copy, live gateway in Blaze-agent'; THIRD_PARTY_NOTICES for Bot HQ (MIT), Lucide (ISC), Simple Icons (CC0), Haze (Apache-2.0), noVNC (MPL-2.0, later); ask owner to add a LICENSE to a-team before code is copied | `gateway/README.md, THIRD_PARTY_NOTICES.md, app/src/main/assets/legal/` | LegalAssetsTest/MoviesLegalTest updated and green; notices list every new third-party source | — | S | AM-63 |
| **P0-IL-03** ✓ 60818dab | Do-not-port register: document and verify absence of BH dead/stub code (subagents.ts as-is, draft emitter-less UI, stubs, unused tables, Grokbot branding, Grok artwork), BH server-only auth (Composio Google sign-in, ALLOWED_EMAILS, dev/owner links), AT removed Connections/gmail helpers, AT no-Gradle build, AT cactus.wasm | `docs/overhaul/do-not-port.md` | Every not-ported feature listed with reason + superseding ticket; grep check in CI-free script | — | S | BH-75, BH-76, BH-77, BH-78, BH-86, AT-01, AT-57, AT-67 |
| **P0-TM-01** ✓ a1141b6f | team/ package + team.db (raw SQLite like cache.db) with 05 §9.2 schema (bots, bot_soul_versions, bot_apps, rows, jobs, job_steps, bot_threads, routines, suggestions, proactivity, approvals, policies, spend_ledger, audit_log, screens) + versioned migrations; pure models Bot/Job/JobStep/Row/BotMessage/Routine/Suggestion/Approval/MemScope | `team/TeamDb.kt, team/Models.kt, team/TeamJson.kt` | TeamDbTest (Robolectric) creates + migrates; JSON round-trip tests; nothing wired to UI | — | M | BH-83 |
| **P0-AG-01** ✓ 7f12551d | BotEngine interface + LocalEngine wrapping Agent.Session through a TeamHooks seam (identity, tool subset, row sink) | `agent/engines/BotEngine.kt, agent/engines/LocalEngine.kt, agent/TeamHooks.kt, agent/Agent.kt (≤20 lines)` | LocalEngineTest emits Row events from a fake LlmClient; Agent.kt line delta ≤ 20; existing agent tests green | P0-TM-01 | M | BH-11, AM-14 |
| **P0-SH-01** ✓ 21fe3ebb | ui/argus tokens: ArgusColors/Type/Shapes/Motion/Glass/Elevation (04 §2), argusGround (Plain/Orbit/Cobalt night), AA light variants, Material3 colorScheme mapping, Haze glass with low-RAM/API<31 fallback; behind setting `argus_look` (default off in P0) | `ui/argus/ArgusTheme.kt, ArgusTokens.kt, ArgusType.kt, ArgusGlass.kt, app/build.gradle.kts (Haze)` | ContrastTest extended to 3 grounds × glass levels; ArgusTokensTest; ThemeEverywhereTest still green | — | M | AM-13 |
| **P0-SH-02** ✓ ccf1a382 | Lucide generator (reads a-team app/www/icons.js → ui/argus/icons/Lucide.kt, ~90 ImageVectors, stroke-by-size) + BotOrb Canvas (6 faces Orbit/Tilt/Steep/Pure/Twin/Polar, any colour, OrbState Idle/Working/Waiting/Talking/Away) + Manager cobalt 'A' sphere (ArgusMark) | `tools/gen-lucide.mjs, ui/argus/icons/Lucide.kt, ui/argus/BotOrb.kt` | OrbShotsTest light/dark × sizes; IconOnlyTest/NoSquareTest green; no BH artwork | P0-SH-01 | M | BH-03, AT-13 |
| **P0-API-01** ✓ cd942dad | Client gateway fixes: send blaze_context:"full" on direct gateway calls (AT D6); port a-team models.js → agent/LiveModels.kt (GET /v1/models/live, 5-min refresh, lane pick, X-Blaze-Prefer/Avoid, follow fallback headers) behind flag | `agent/Cloud.kt, agent/LiveModels.kt, agent/ModelRoute.kt` | CloudTest asserts body field; LiveModelsTest ports a-team evals/test model cases | — | M | AT-29, AT-36, AM-27 |
| **P0-QA-01** ✓ 21513181 | Spec index: map every A-team Playwright PASS line (app/tests/*), A-team evals/test case and Bot HQ e2e step (tests/e2e.mjs, voice-call.mjs) to a ticket ID; copy a-team evals/ harness into evals/ (Node, no deps) pointed at the gateway | `docs/overhaul/specs.md, evals/` | Every spec has a ticket; `node evals/run.mjs --suite refusals` runs against gateway | — | M | BH-85, AT-65, AT-66 |
| **P0-MG-01** ✓ 0167d249 | Define argus-team-export.v1.json (bots + look, jobs, settings, profile items stated/inferred, bot/team memory, routines, optional Manager chats) and the A-team backup JSON mapping | `docs/overhaul/ateam-export.md` | Format reviewed; fixtures from A-team Demo team checked in under app/src/test/resources/ | — | S | — |

### 6.2 P1 · 0.53.0 · One look, one team

_A-team shell + Team core (05 E0 exit)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P1-SH-01** ✓ 4f57ad61 | Shell: Scaffold with ArgusTopBar (me · search · bell · +), NotchSwitcher (Team·Chat·Life·Desk, spring, pull-further = Voice), HorizontalPager modes, Composer (+ · text · mic↔send↔stop, 5-line grow, imePadding), ChipsRow, Toast host; replaces SpaceTopBar + tabs; SpaceBackdrop becomes the Orbit ground; `argus_look` default on | `ui/argus/kit/*, ui/ChatScreen.kt (shell), agent/ChatNav.kt, ui/BackRule.kt, ui/space/SpaceTopBar.kt` | 4 modes reachable by notch, swipe and /team /chat /life /desk; BackRuleTest updated; no layout jump on keyboard (shot); low-RAM smoke ≤700 ms | P0-SH-01, P0-SH-02 | L | BH-07, BH-08, BH-20, AT-02, AT-03, AT-07, AT-28, AM-01, AM-07, AM-08, AM-13, MM-23, NEW-01 |
| **P1-SH-02** ✓ e8ab9dac | Kit components: BotChatRow, PinStrip, NeedYouBanner (static until P2), MenuRow, SettingsGroup, Segmented/FilterChips, ArgusSheet, ArgusButton/IconButton/Field/Search, MentionPopup (@ / # ), AttachmentBar, EmptyState, ProgressLine, CloudChip (hidden until P7) | `ui/argus/kit/*` | KitShotsTest light/dark × font 100/200 %; 48 dp targets; TalkBack merged semantics | P0-SH-01 | M | AT-10 |
| **P1-SH-03** ✓ 8cbc5f18 | Thread components: ThreadIntro (orb xl, role, engine pill), UserBubble, AgentText (no bubble; AM Md/RichBlocks inside), SystemNote, ActivityLine + step list, WorkChip with AT grouped steps, ToolCard + Undo, JobCard, OkCard, FileCard (basic), HandoffCard; restyle AM rich replies, code blocks, tables | `ui/team/thread/*, ui/RichReplies.kt (style only), agent/StepPill.kt` | ThreadShotsTest; ActivityLine liveRegion Polite; raw tool/model names never shown (StepPill sanitiser test) | P1-SH-02 | L | BH-13, BH-14, BH-18, BH-19, BH-21, AT-26, AT-27, AM-10, MM-14, NEW-05 |
| **P1-SH-04** ✓ b9481c6f | Chat mode: AM main conversation on the new look — serif greeting, Manager sphere, suggested asks, private (ghost) toggle, ✎ new chat = generation bump, history drawer over generations + Projects with FTS (ChatIndex), unseen dot, rename/pin/delete + Undo (AT talks.js rules); reply keeps streaming into its own chat | `ui/ChatScreen.kt, ui/team/ChatDrawer.kt, agent/ChatIndex.kt, agent/ChatStore.kt` | Existing ChatScreen/MessageList tests green; ChatDrawerTest (grouping Today/Yesterday/7 days/Older, undo); edge-swipe uses system gesture exclusion | P1-SH-01, P1-TM-02 | L | BH-16, AT-04, AT-05, AM-03 |
| **P1-SH-05** ✓ 60e5beb6 | Life mode v1: serif day line, Today strip from SchedulePage, AM Home panels (weather, latest answer, suggested asks), Projects; domain tiles show 'Set up my life team' until P5 | `ui/team/LifeMode.kt, ui/space/SpaceHome.kt (retired), ui/SchedulePage.kt` | LifeShotsTest; SpaceHome cards still reachable | P1-SH-01 | M | AM-02 |
| **P1-SH-06** ✓ cefd11ad | Settings re-tree (04 §4.11): You page + Team / You & phone / System groups, floating search pill, every old AM page id aliased (SpaceSettings.PARENT, about.md), Appearance (Match phone/Dark/Light + grounds + accent off), Handedness, Reduced motion | `ui/space/SpaceProfile.kt → ui/argus/settings/*, ui/SettingsSheet.kt, app/blaze-docs/about.md` | SettingsAliasTest: every pre-0.53 id opens a page; open-by-name from chat works | P1-SH-02 | M | BH-09, AT-08, AT-09, AT-40, AT-48, AT-68, AM-05, AM-06, AM-54, MM-24, NEW-17 |
| **P1-TM-01** ✓ b862f9c2, 849f7002 | TeamStore + Bot = persistent Spec: extend Specialists.Spec (soul, avatar, colour, memoryScope, workspace, voice, managedBy, modelPref); ARGUS seeded as fixed id `manager`, locked identity, undeletable (UI + store refuse, re-seed if missing/corrupt); Tempo + Inbox seeded; 10 built-in specialists become WORKER templates | `team/TeamStore.kt, team/Bot.kt, agent/Specialists.kt` | TeamStoreTest ports AT manager.js ensure/insert/canDelete/locked cases; delegate to specialists still works | P0-TM-01 | M | BH-01, BH-02, AT-14, AM-17, MM-01, MM-02 |
| **P1-TM-02** ✓ 702c41a8 | Bot threads on the BH Row model (seq/updatedSeq, generation, kinds message/tool-call/card/event/notice), BotThreads repo with unread/preview/last activity; ARGUS thread bridges the existing AM ChatStore (no data move); per-bot reply streams via LocalEngine | `team/Rows.kt, team/BotThreads.kt, agent/ChatStore.kt (bridge)` | RowStoreTest (upsert by seq, generation bump); upgrade test: AM chat history intact | P0-AG-01, P1-TM-01 | L | BH-06, BH-10, BH-12, BH-16, BH-81, AT-11, MM-01 |
| **P1-TM-03** ✓ 160ebca1 | Team mode screen: team name + sub line (bots · jobs today · overdue), domain chips (All until P5), PinStrip, ARGUS row (gold Lead), bots/groups by recency, Unassigned row, All jobs; empty state 'Build your team' + Demo team; long-press quick sheet (Open, Pin, Memory, Edit soul, Delete) | `ui/team/TeamMode.kt` | TeamShotsTest; pin/unpin persists; Manager delete action absent | P1-SH-02, P1-TM-01 | M | BH-06, AT-06, AT-11, AT-56, MM-14 |
| **P1-TM-04** ✓ e0ac8f21 | Bot CRUD: New bot (Blank / From template / Describe it → ARGUS drafts, shows approval later in P2), editor sheet (name, label, email, portfolio, instructions, look 6 faces × 8 colours + any colour, reports-to, effort, mode personal/teammate); team tool group (agents_list, agent_create, agent_update refuses manager, agent_delete asks first, agent_status, update_agent self-edit, note_team with Undo) | `team/ManagerTools.kt, ui/team/BotEditor.kt, agent/ToolRouter.kt` | ManagerToolsTest; delete needs a yes in the next message or the button | P1-TM-01 | M | BH-01, BH-04, BH-89, AT-12, AT-15, AT-16, MM-02 |
| **P1-TM-05** ✓ cee55865 | Jobs: Job model + queue on BgAgents/TaskWorker (deps waits-on, one running job per bot, user messages first, runaway guard 12/10 min, stale runs → failed on start, duplicate add_job dropped), tools add_job/done_job/update_job/agent_delegate/retry_job + BH create_task/update_task as JobSteps, due-word parser, failure log + backoff 1/5/15 min with plain-words network errors; completion posts in the bot thread + summary to the requester | `team/Jobs.kt, team/JobQueue.kt, team/DueWords.kt, agent/BgAgents.kt (hook)` | JobsQueueTest ports a-team jobs.js cases; a job finishes with the app closed (TaskWorker); parallel across bots within AgentBudget | P1-TM-02 | L | BH-44, AT-15, AT-17, AT-18, AT-21, AM-16, MM-03 |
| **P1-TM-06** ✓ 2d3f2be8 | Routing v1: @mention / name routing from Team composer and ARGUS chat, delegate = Job + HandoffCard ('Atlas is on it'), Needle fast path for AT's five commands via native FastLane (exact → Needle → gateway → rules), 'Here's what I'll do' preview sheet (toggle), CMDISH in threads, `$ cmd` runs in the shell (Alpine until P7) | `team/Router.kt, agent/FastLane.kt, agent/FastActions.kt, ui/team/PlanSheet.kt` | RoutingTest; command fallback order test; offline add-job works | P1-TM-05 | M | AT-30, AT-31, AT-38, AM-30, MM-20 |
| **P1-TM-07** | Desk v1: segmented Inbox (placeholder) · Jobs · Routines (AM SchedulePage) · Library (AM Downloads/Gallery links) · Projects (moved from top tabs) · Computers (Terminal + Browser entries); Jobs filters Needs you/Today/Tomorrow/Upcoming/Overdue/Done/All/By bot/Unassigned + search + FAB + job sheet (log, Retry) | `ui/team/DeskMode.kt, ui/team/JobsPage.kt, ui/space/SpaceProjects.kt` | DeskShotsTest; tabular numbers; Projects behaviour unchanged | P1-TM-05 | M | BH-64, AT-17, AM-04 |
| **P1-MG-01** | A-team backup import (Settings → Sync & devices → Backup → Import A-team backup): bots (fields, look → orb face/colour, Gemini model → per-bot pin note), jobs (status, deps, due, priority), nickname/theme | `team/ATeamImport.kt, ui/argus/settings/BackupPage.kt` | ATeamImportTest on the Demo-team fixture; idempotent re-import | P0-MG-01, P1-TM-05 | M | AT-56 |
| **P1-QA-01** | P1 QA: shots matrix (light/dark × Plain/Orbit/Cobalt × font 100/200 %) for every new screen via Robolectric + @Preview fixtures (BH ?fixtures idea); low-RAM smoke stays 7 tests; real arm64 phone checklist P1 (§9) | `app/src/test/**/ShotsTest*, tools/smoke/` | All shots reviewed by owner; smoke OK (7 tests); checklist signed | P1-SH-01…P1-TM-07 | M | BH-22, AM-68 |

### 6.3 P2 · 0.54.0 · Safe hands

_Policy engine, cards, Inbox, audit, kill switches (05 E1)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P2-SF-01** | ActionClass on every tool (ToolRegistry declaration: READ, WRITE_LOCAL, WRITE_REMOTE, SEND, SPEND, DELETE, SETTINGS, CREDENTIAL, COMPUTE_PAID, PHYSICAL) + Policy.decide merging ConfirmGate taint, Modes, CommandPolicy, Mcp Ask/Always/Off, AT RISKY regex and policies table; approvals inside the tool call; 25-min expiry; new user message → skipped; restart expires | `policy/Policy.kt, policy/ActionClass.kt, agent/ConfirmGate.kt, agent/CommandPolicy.kt, agent/ToolRouter.kt` | PolicyTest (≥60 table cases incl. tainted SEND, proactive SPEND never auto, bot-chain lowest permission); ConfirmGateTest/CommandPolicyTest unchanged and green | P1-TM-05 | L | BH-23, BH-80, AM-21, NEW-09 |
| **P2-SF-02** | Cards: ApprovalCard (Allow / Always allow / Don't allow; pending→approved/denied/expired/skipped), DraftCard (every SEND: Send/Edit/Discard), QuestionCard (ask_user ≤6 options or free reply, ends the turn), ConnectCard (request_app_connection; resumes with '[System] connected X'), OkCard wired; one card at a time per bot (ToolScheduler `user` claim) without stalling other bots | `ui/team/cards/*, policy/Cards.kt, orchestra/ToolScheduler.kt` | CardsShotsTest; CardFlowTest (expiry, skip, deny → 'do not retry') | P2-SF-01, P1-SH-03 | L | BH-23, BH-26, BH-27, BH-28, BH-47, BH-48, BH-89, MM-03, NEW-24 |
| **P2-SF-03** | Inbox: bell sheet + Desk → Inbox (All/Approvals/Questions/Connect/Failed/Suggestions), urgency sort, bulk within one bot + kind, swipe-approve only low risk, biometric for money/delete; NeedYouBanner + bell dot + (later) widget/QS/badge read ONE needs-you query | `ui/team/Inbox.kt, policy/NeedsYou.kt` | InboxTest; NeedsYouTest single source; decisions write back into the owning thread | P2-SF-02 | M | AT-19, NEW-12 |
| **P2-SF-04** | Permissions page (Settings → Privacy → Approvals & permissions): standing allows per tool × bot with 'Ask again', autonomy per bot (Assist/Act/Autopilot), SpendGuard monthly cap (default 0) + per-transaction max, send scope, Autopilot exceptions listed | `ui/argus/settings/PermissionsPage.kt, policy/SpendGuard.kt` | PermissionsTest; cap 0 → every spend asks | P2-SF-01 | M | BH-25, NEW-09 |
| **P2-SF-05** | audit_log (hash-chained) for every non-read action and decision + spend_ledger + Settings → Activity (per bot / team, export) | `policy/Audit.kt, ui/argus/settings/ActivityPage.kt` | AuditChainTest detects tampering; args redacted by SecretGuards | P2-SF-01 | M | BH-79, AM-58, NEW-10 |
| **P2-SF-06** | Kill switches: instant Stop (AT stopP semantics: abort stream, cancel commands; tmux wait-for added in P7), Pause bot, Pause all agents (Notch long-press + QS later), StopResume 'Continue where you stopped', step budget 40 + 'Go on' WorkChip | `policy/Kill.kt, agent/StopResume.kt, ui/team/thread/WorkChip.kt` | StopTest; Pause all holds job queue + routines until resumed | P2-SF-01 | M | BH-15, AT-24, AT-69, NEW-11 |
| **P2-SF-07** | A-team guard ports as pure Kotlin: CompletenessGate (no 'done' without a passing build/test after last edit), checkRun rules (destructive paths outside /root /tmp, fork bombs, raw devices, sudo strip, curl|sh and force-push warnings, servers/`cmd &` detached with log), StepGuard 3rd repeat, unbacked-claim patterns into ClaimGuard, fence-repair cases into TextCalls, filler drop, interrogation bounce, verified done_job file checks (HTML/JSON/JS/py/bash) into ToolVerify | `agent/guards/Completeness.kt, agent/guards/CheckRun.kt, agent/ClaimGuard.kt, agent/TextCalls.kt, agent/ToolVerify.kt` | All a-team evals/test guard cases green as JUnit; terminal/code eval suites ≥ A-team v2.18 baseline | P0-QA-01 | L | AT-20, AT-25, AT-32, AT-34, AT-69, AT-70, AT-71, AM-20, MM-03 |
| **P2-SF-08** | Per-bot notifications: MessagingStyle channel per bot with orb bitmap Person icon, inline reply, Approve/Deny actions, clean 160-char summary (AT NOTIF.summary), in-app notification history behind the bell | `notify/Notifier.kt, notify/BotNotify.kt` | NotifyTest summary cases; tap opens the thread (argus://bot/<id>) | P2-SF-03 | M | AT-60, MM-23 |
| **P2-QA-01** | Safety evals: refusals (63), prompt-injection → send/spend scenarios, approval expiry, chain permissions; real phone: approve from notification + biometric | `evals/suites/safety/*` | Suites green at baseline; phone checklist signed | P2-SF-01…08 | M | — |

### 6.4 P3 · 0.55.0 · Souls & memory

_Scopes, SOUL/MEMORY/USER files, Bot profile (05 E2)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P3-AG-01** | mem_scope side table in blaze.db (never alter `memories`) + scope-aware prompt blocks (pinned user:core → SOUL → relevant notes over visible scopes → delegation lines → chat tier → cache TOCs); visibility rule (reports-to, private domains, share tags); team notes (AT team memory) | `agent/Mind.kt, agent/Memory.kt, memory/MemScope.kt, store/BlazeDb.kt (side table only)` | MemScopeTest; Desktop schema hash test unchanged; prompt ≤ budgets | P1-TM-01 | L | AT-53, AM-31, AM-32, MM-11, NEW-08 |
| **P3-AG-02** | Memory migration: existing memories (pinned → user:core, else team); A-team profile kinds/stated → user:<kind>; A-team bot/team notes → bot:<id>/team (from import file) | `memory/MemMigration.kt` | MemMigrationTest idempotent, no row lost | P3-AG-01 | M | — |
| **P3-AG-03** | SOUL.md per bot (filesDir/bots/<id>/SOUL.md; headings Who/How/Own/Standing rules/Boundaries; locked sections; version + 20-version history + Undo), cache-stable prompt order (Now: line last), regenerate-from-profile + reset | `team/Soul.kt, agent/PromptParts.kt` | SoulParseTest; PromptOrderTest (no clock above memory) | P3-AG-01 | M | BH-05, BH-30, MM-11 |
| **P3-AG-04** | Projections MEMORY.md / USER.md rendered from the DB and parsed back (line = memory, § separator), diff shown on parse failure; Forget = delete from memories, mem_scope, FTS, projections, sync blob + verify by search | `memory/Projections.kt, agent/MemoryDelete.kt` | ProjectionRoundTripTest; ForgetVerifyTest | P3-AG-01 | M | BH-67, BH-88 |
| **P3-AG-05** | Memory writing: stated/inferred flag, autoLearn detectors (RULE_START/PREF/REM) with 'Saved to memory' card + Undo, memory_propose + ARGUS accept rules (allergy = requirement auto-accept), standing rules into the relevant SOUL 'Standing rules', secrets refused (AT SECRET + SecretGuards), narrowness rule in prompts | `memory/Learn.kt, agent/MemoryGate.kt, agent/Learn.kt` | LearnDetectTest ports manager.js autoLearn cases; secret lines dropped with a notice | P3-AG-03 | M | AT-54, NEW-08 |
| **P3-AG-06** | Per-bot ModelPolicy (lane, tier Spark/Flare/Inferno, pinned own-key Gemini model with AT 429 cool-down, effort → ThinkBudget, zeroRetention) wired through Cloud.kt / LiveModels; legacy Providers kept hidden | `team/ModelPolicy.kt, agent/Cloud.kt, agent/ThinkBudget.kt, agent/KeyRing.kt` | ModelPolicyTest; gateway default unchanged when policy is Automatic | P0-API-01 | M | BH-31, AT-37, AM-26, AM-28, AM-29, MM-19 |
| **P3-SH-01** | Bot profile screen: hero orb + status line + 4 action discs (Message, Call, Watch, Pause) + tabs Overview (upcoming, tasks, library count, suggested, New conversation, Compact, Make primary off by default, Delete) · Soul (fields, look, SOUL.md editor with preview/revert/history, Personality sliders from HaloStudio, language mix, boundaries) · Memory (search, private notes add/edit/forget, team notes, Holding, conversation meter, Consolidate, files) · Engine (Automatic, Depth, own key, status, fallback note); other tabs link to later phases | `ui/team/profile/*, agent/HaloStudio.kt (reuse), ui/HoldingPage.kt` | ProfileShotsTest; Manager tab shows locked core greyed | P3-AG-03, P3-AG-04, P3-AG-06 | L | BH-04, BH-05, BH-31, BH-33, BH-67, BH-88, AM-25, AM-33, NEW-24 |
| **P3-SH-02** | You → What the team knows: USER.md view (add, edit, delete, mark as stated, Forget everything), per-bot memory lists, memory graph (MemGraph), Holding | `ui/argus/settings/TeamKnows.kt, ui/MemoryPage.kt, ui/MemGraph.kt` | TeamKnowsShotsTest; Forget everything verified | P3-AG-04 | M | AT-54 |
| **P3-API-01** | Scoped memory + souls in E2E CloudSync (SyncAdapters for mem_scope and souls; private scopes only with 'Sync private memory', off by default) | `connect/SyncAdapters.kt, connect/SyncMerge.kt` | SyncMergeTest; private scope excluded by default | P3-AG-01 | M | AM-35 |
| **P3-MG-01** | A-team memory/profile/routines import from argus-team-export.v1 + a-team repo ticket: Settings → Data → 'Export for ARGUS' (v2.19.x build) | `team/ATeamImport.kt; a-team app/www/app.js` | Import fixture → scopes correct; a-team export passes its own Playwright test | P0-MG-01, P3-AG-02 | M | — |

### 6.5 P4 · 0.56.0 · Apps & skills

_argus-connect, per-bot apps/skills/MCP._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P4-CN-01** | connect/ArgusConnect.kt: catalog + categories, connected list, per-app/per-tool toggles, connect by key/fields or browser OAuth loopback, Test, Disconnect, /v1/me/tools (5-min cache) + /v1/me/execute (12 KB output cap); no third-party token stored on the phone | `connect/ArgusConnect.kt, connect/Http.kt` | ArgusConnectTest against a mock server; vault holds only the bz_ token | P2-SF-01 | L | MM-08 |
| **P4-CN-02** | Apps & connectors page (AT .cnp plugin list: Connected/Available, brand logo tiles from gen-logos → vector drawables, category menu, floating search, detail sheet) + MCP servers section + device connectors section (Spotify, Classroom, GitHub device flow, token paste) + suggested-connector chips | `ui/team/apps/*, tools/gen-logos.mjs, connect/Connectors.kt` | AppsShotsTest; disabled apps at 45 % opacity; logos CC0/notice | P4-CN-01 | M | BH-72, BH-74, AT-49, AM-55 |
| **P4-CN-03** | Connector tools in ToolRouter: per-bot BotAppGrant filtering, connected apps first, prompt cap 64 + load_tools groups, connectors_list, prompt line forbidding shell/env for connectors, BH read-only auto-allow regex mapped to READ, writes → approval card | `agent/ToolRouter.kt, connect/ConnectTools.kt` | ToolCapTest; ReadOnlySlugTest ports BH composio.ts cases | P4-CN-01 | M | BH-24, BH-49, AT-50, AM-19, MM-08 |
| **P4-CN-04** | Bot profile → Apps + Skills tabs; ConnectCard OAuth return resumes the paused turn (deep link / loopback) | `ui/team/profile/AppsTab.kt, SkillsTab.kt, connect/AuthReturn.kt` | ConnectResumeTest; per-bot grants persist | P4-CN-02, P3-SH-01 | M | BH-27, BH-48, BH-66 |
| **P4-CN-05** | Skills on argus-connect: SkillSync → /v1/me/skills (one-time migration from blaze-auth /v1/skills), My skills editor + Polish (Undo) + Argus originals read-only 'Copy to my skills', per-bot assignment (Spec.skills), SkillMatch on demand (≤3/turn), Marketplace kept | `connect/SkillSync.kt, agent/SkillLibrary.kt, ui/SkillLibraryPage.kt` | SkillMigrateTest (no skill lost); Polish undo | P4-CN-01 | M | BH-66, AT-51, AM-23, AM-57, MM-10 |
| **P4-CN-06** | MCP per bot: BotAppGrant source=mcp, per-tool Ask/Always/Off mapped to ActionClass; stdio MCP servers runnable inside proot (Node/Python) optional; MCP output tainted | `connect/Mcp.kt, connect/McpConfig.kt` | McpGrantTest; taint → card for writes | P4-CN-03 | M | BH-35, AM-56 |
| **P4-CN-07** | Event triggers client: list_event_triggers / create_event_routine tools backed by argus-connect trigger catalog; events arrive via sync/poll (consumed by SignalBus in P6) | `connect/Triggers.kt` | TriggersClientTest with mock | P4-API-01 | M | BH-41 |
| **P4-API-01** | argus-connect server (repo argus-connect): trigger webhooks/polling (Gmail push, Calendar watch, GitHub, Slack) → per-user event queue; optional Composio provider adapter server-side with the user's own key; bz_ auth unchanged | `argus-connect repo` | Server tests; no Composio key ever reaches the phone | — | L | BH-62, BH-73 |
| **P4-QA-01** | Connectors on a real phone: OAuth loopback, write approvals, per-bot filtering; tool-selection eval | `evals/suites/tools/*` | Checklist signed; eval ≥ baseline | P4-CN-01…07 | M | — |

### 6.6 P5 · 0.57.0 · Team talk & life

_TeamBus, rooms, 14 domains, routing v2, Life + People (05 E3/E4)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P5-TM-01** | TeamBus: send_to_bot (hop ≤3, thread budget 20 calls, expects_reply, stay_silent ends), bot_threads, scope redaction of payloads, collapsed 'A ↔ B · n messages' line in both threads, HandoffCard live states, provenance badges (from bot / routine / voice), no side doors (bots never call each other's tools) | `team/TeamBus.kt, team/BotMessaging.kt` | TeamBusTest (hop cap, budget, redaction); LoopGuard reused | P1-TM-05, P3-AG-01 | L | BH-12, BH-17, BH-42, BH-45, BH-57, MM-13, NEW-05 |
| **P5-TM-02** | Rooms/groups: kind=ROOM, members, @mentions else routing hints else all, ≤3 responders, last 12 messages, post_to_group/stay_silent, create_group from UI + by a bot, add/remove member events, stacked-orb rows; Duo/DuoAll generalised (parallel answer + one reaction); MeetingRoom → 'Room' view; huddle (ARGUS facilitates ≤3 rounds, ≤30 calls, compare + choices) | `team/Rooms.kt, agent/Duo*.kt, ui/team/RoomThread.kt` | RoomDispatchTest ports DuoTest; huddle budget test | P5-TM-01 | L | BH-46, BH-58, AM-25, MM-13, NEW-06 |
| **P5-TM-03** | Workers + subagents: spawn_agent with Specialists templates (permissions ∩ template groups), subagents hidden ≤4 per parent reporting via send_to_bot, Orchestra node ownerBotId, new Reviewer worker (AT review rubric + research claim reviewers) | `team/Workers.kt, orchestra/Blueprint.kt, assets/specialists/reviewer.md` | WorkerPermTest; Blueprint owner test | P5-TM-01 | M | BH-59, BH-60, BH-89, AT-72, AM-17, AM-18 |
| **P5-AG-01** | Domain catalog: assets/domains/<id>/ (domain.json, SOUL.md, PLAYBOOK.md, skills) for Tempo, Inbox, Pulse, Ledger, Career, Forge, Hearth, Pantry, Atlas, Circle, Tutor, Clerk, Scout, Studio, grouped into 10 Life areas; create-domain-from-template | `assets/domains/**, team/Domains.kt, agent/Playbooks.kt` | DomainTemplateTest validates all 14 (Playbooks validator); private scopes for Pulse/Ledger/Clerk | P3-AG-03 | L | NEW-02 |
| **P5-TM-04** | Routing v2: SpecialistMatch over domain routingHints (keyword + on-device embedding; ≥0.75 silent, 0.5–0.75 announce, ties ask), multi-domain → orchestrate generic Blueprint, ARGUS proposes a new domain after 3 unowned repeats; Needle tools + route_to/set_routine/remember; regenerate needle-ft data with team/domain commands (ml/needle-ft) | `team/Router.kt, agent/SpecialistMatch.kt, agent/FastLane.kt, ml/needle-ft/` | Routing eval ≥ 90 % on a labelled set; Needle offline parse tests | P5-AG-01, P1-TM-06 | L | AT-38, AT-39, AM-18, MM-20, MM-25, NEW-04 |
| **P5-SH-01** | 'Set up my life team': 3-question interview + domain picker (Forge pre-proposed for Finn), optional seeded rooms (Life Admin, Founder HQ, Weekend); Team domain chips; roster page (List | Graph) with nested specialists, engine + app counts, Hire / Templates | `ui/team/Setup.kt, ui/team/Roster.kt` | SetupShotsTest; roster nesting by reportsTo | P5-AG-01 | M | AM-06, NEW-03 |
| **P5-SH-02** | Compose graph: Canvas pan/pinch/fit, drag nodes (x,y persisted + synced later), ports to create reports-to (dotted) / waits-on (dashed) edges, owns (solid), edge menu remove, node status dot/current job/model/progress, tap → thread or job sheet, +Bot/+Job/Fit, legend; ≤20 fps, stops when hidden | `ui/team/TeamGraph.kt` | GraphLogicTest (pure hit-test, edge rules); shots | P5-SH-01 | L | AT-23, MM-04 |
| **P5-SH-03** | Life mode v2: area tiles (lead orb, live line, panel value; reorder/hide/change lead), domain page (hero, panels, routines, specialists, files, composer to that lead), Mind area (life logs, brainstorm, Time Capsule, memory graph) and Phone area (phone control, Magic Hand, App store/site apps, device doctor) entry tiles | `ui/team/LifeMode.kt, ui/team/DomainPage.kt` | LifeShotsTest; every AM view reachable from an area | P5-AG-01, P1-SH-05 | M | AM-43, NEW-02 |
| **P5-SH-04** | People graph (Life → People): people from contacts (permission), memory user:people, connectors, notification logs (opt-in); graph + list, keep-in-touch suggestions (Circle), Group Brain card, Bots talking section, person sheet, Forget person; Bot profile → Connections tab (people served, bots talked to, groups) | `ui/team/People.kt, team/People.kt, notify/GroupBrain.kt` | PeopleShotsTest; Forget person removes notes + edges | P5-TM-01, P3-AG-01 | L | BH-87, AM-39, NEW-07 |
| **P5-QA-01** | Multi-agent evals (routing, delegation, hop loops, room responder cap, huddle cost) + RAM on a 4 GB phone with 14 bots enabled | `evals/suites/team/*` | Suites green; RAM within AgentBudget | P5-* | M | — |

### 6.7 P6 · 0.58.0 · Proactive team

_Routines per bot, SignalBus + arbiter, pings, system entry points (05 E5)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P6-PR-01** | Routines per bot: kinds CRON / EVENT / CHECKIN / WATCH, owner bot, cron parser (pure) + once/daily/weekly DST-aware (AT nextRun), human description (BH describeCron), AlarmManager exact + WorkManager 15-min backstop + boot receiver (AM RoutineReceiver), shell-job routines kept, Run now/pause/edit/delete, fire = turn in owner queue tagged from: routine, stay_silent + silent-streak back-off | `routine/Routines.kt, routine/Cron.kt, routine/RoutineReceiver.kt, team/Routines.kt` | RoutineScheduleTest ports AT nextRun + BH croner cases; fires with app closed | P1-TM-05 | L | BH-40, BH-41, BH-42, BH-61, AT-58, AM-60, MM-12 |
| **P6-PR-02** | Desk → Routines (day timeline + now line, schedule rows with toggle and ⋯ Run now, 'When…' event rows: app events, device triggers from Autopilot/Triggers (place, Wi-Fi, BT, battery, notification, app opened), clock group alarms/timers, check-ins) + routine editor sheet (What/Who/When/Deliver/Ask first) + Bot profile → Routines tab + create by text | `ui/team/RoutinesPage.kt, ui/team/RoutineEditor.kt, proactive/Autopilot.kt, proactive/Triggers.kt` | RoutinesShotsTest; Needle 'every Friday 5pm…' creates a routine | P6-PR-01 | M | BH-61, AM-61 |
| **P6-PR-03** | SignalBus (notifications, calendar changes, connector events via sync, place/Wi-Fi/BT/battery, time ticks, job results, web watches) + domain subscriptions from domain.json; proposers emit Suggestion or stay silent (cheapest lane) | `proactive/SignalBus.kt, team/Suggestions.kt` | SignalBusTest; no model call for unsubscribed signals | P5-AG-01, P4-CN-07 | M | BH-62, AM-61, NEW-13 |
| **P6-PR-04** | ARGUS arbiter + one Proactivity dial (Off/Quiet/Normal/Jarvis, per-bot override): mutes + 'Stop suggesting this' → standing rule, 24 h dedupe, quiet hours 22–07, ≤3/day, ≤1/domain/day, urgency bypass rules, back-off; unifies AM Proactive/Jarvis/FollowUps/Habits, AT daily 07:30 check + suggestion cards (Do it/Not now/Stop), BH check-ins | `proactive/Arbiter.kt, proactive/ProactiveDial.kt, proactive/Proactive.kt, proactive/Jarvis.kt` | ArbiterTest; ProactiveDialTest updated; SEND/SPEND/DELETE never on 'Do it' without a card | P6-PR-03, P2-SF-01 | L | BH-63, AT-59, AM-62, MM-12, NEW-13 |
| **P6-PR-05** | Check-ins (ARGUS, up to 6 local times, skip if you spoke <20 min, context = what waits on you) + set_checkins; Morning brief (opt-in, ≤8 lines + choices), Today strip in ARGUS chat, Life day line, Weekly review (Sunday, opt-in) | `proactive/CheckIns.kt, proactive/Brief.kt, ui/team/TodayStrip.kt` | CheckInTest (window, once per slot); brief shots | P6-PR-04 | M | BH-43, BH-63, NEW-14 |
| **P6-SH-01** | Pings: in-app PopNotification (orb, name, time, 2 lines, swipe up), EdgeGlow overlay in the bot's colour (opt-in 'display over other apps', 3 pulses / 2.6 s, reduced-motion variant), two-tap haptics + optional per-bot signature, Settings → Notifications toggles + Preview | `ui/argus/kit/PopNotification.kt, notify/EdgeGlow.kt, notify/Haptics.kt` | PingShotsTest; reduced-motion snaps | P2-SF-08 | M | AT-61, MM-23, NEW-18 |
| **P6-SH-02** | System entry points: Glance widgets (Team 4×2, Ask 4×1, Today 2×2), QS tiles (ARGUS → Voice, Approvals (n)), launcher shortcuts, share sheet → bot picker ranked by content + Direct Share top 4 bots, deep links argus://bot|inbox|routine|library|computer, floating orb as BotOrb, Assist panel bot switcher, lock-screen read-only rules | `widget/*, assist/BlazeAssist.kt, assist/BlazeTile.kt, ui/Overlay.kt, AndroidManifest.xml` | EntryPointsTest; manifest component names unchanged for existing entries | P2-SF-03, P1-SH-01 | L | AM-37, AM-41, NEW-15, NEW-16 |
| **P6-QA-01** | Real phone: routines with app closed across Doze + reboot, caps/quiet hours, widget refresh, edge glow over other apps | `tools/smoke/, docs/overhaul/phone-checklists.md` | Checklist signed on A07/A12 class + one Android 14 phone | P6-* | M | — |

### 6.8 P7 · 0.59.0 · Computers

_Ubuntu workspace per bot, Library, review, cloud desk, take-over (05 E7)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P7-CP-01** | Ubuntu 24.04 workspace distro (opt-in, Wi-Fi, arm64; Alpine stays system) via Distros + SetupPlan; ports AT fixes: tmux 3.3+ server-access ACL host config, hostLinks talloc relink after updates, PROOT_NO_SECCOMP retry, TZ env; Storage page shows sizes | `terminal/Distros.kt, terminal/Linux.kt, terminal/LinuxDoctor.kt, setup/SetupPlan.kt` | LinuxDoctor checks pass on device; SetupPlanTest | P0-IL-01 | L | AT-09, AT-41, AM-06, AM-46, MM-05 |
| **P7-CP-02** | BotWorkspace: per-bot $HOME /home/<botId> + Library dir + tmux window argus:<bot>, run/run_code routed per bot with term:<botId> lock, path guards between bots, workspace snapshot + LOOK FIRST + working set (last 6 paths) in PromptParts, attachments copied to /root/uploads, SOUL/MEMORY mirrored into the home | `terminal/BotWorkspace.kt, agent/PromptParts.kt, agent/Attachments.kt` | BotWorkspaceTest (isolation, snapshot); `$ cmd` from thread lands in the right window | P7-CP-01, P3-AG-03 | L | BH-36, BH-80, AT-28, AT-33, AT-41, AM-34, MM-05 |
| **P7-CP-03** | Terminal: tabs attach to bot tmux windows (dead tabs struck through), AT extra-keys rows, copy/paste/zoom; detached job supervisor (nohup/setsid + exit file) in JobKeeper, 'resume the same chat on completion' ping, 'N running in background' chip, Stop kills a turn's jobs (pkill + tmux wait-for) | `terminal/TermSessions.kt, terminal/BlazeTerminalActivity.kt, terminal/JobKeeper.kt` | TermTabsTest; background job pings the thread on finish | P7-CP-02 | M | AT-22, AT-42, AM-46 |
| **P7-CP-04** | File tools: write_file (only /root|/tmp, no .., 60 KB per call), attach (FileCard: image inline, HTML Preview sandbox without JS bridge, Open/Share via content provider, Save to Downloads/ARGUS), save, zip folders (200 MB cap) | `agent/FileTools.kt, agent/SaveFile.kt, ui/team/thread/FileCard.kt` | FileToolsTest path rules; preview has no bridge | P7-CP-02 | M | BH-38, AT-43, AT-44 |
| **P7-CP-05** | Library: library/ view over bot dirs, Downloads/ARGUS, Designs, Gallery; Desk → Library (search, filters All/Docs/Images/Code/Media/Workspaces, by bot, by project, file sheet: open/share/save/send to bot/move to project/rename/delete) + profile Library count + `#file` mention | `library/Library.kt, ui/team/LibraryPage.kt` | LibraryShotsTest; delete is a card (DELETE) | P7-CP-04 | M | BH-38, BH-65, MM-17 |
| **P7-CP-06** | Code review gate: AT review.js static scan script run in proot (secrets, eval, shell=True, pickle, innerHTML, TLS off, curl|sh, chmod 777; shellcheck/ruff/pyflakes/bandit/eslint if installed) + strict reviewer call (T=0) + ReviewCard + Settings → Code review (Off/Normal/Strict); Reviewer node after build in apk pipeline | `agent/review/CodeReview.kt, assets/review/scan.sh, orchestra/Pipelines.kt, ui/team/thread/ReviewCard.kt` | CodeReviewTest ports a-team tests/review.test.js cases | P7-CP-02, P5-TM-03 | M | AT-35, MM-22 |
| **P7-CP-07** | Browser per bot: site-<bot> WebView profiles (cookies separate), AT single-tool `steps` batching ≤15, confirm for pay/book/send/submit/delete, handoff for passwords/cards, Take over / Hand back on the phone browser with handback note into the next turn | `browser/BrowserEngine.kt, browser/BrowserTools.kt, browser/phone/*` | BrowserProfileTest; handback note appears in the turn | P2-SF-01 | M | BH-52, AT-45, AM-47, AM-48, MM-07 |
| **P7-CP-08** | computer/ package: ComputerProvider {LocalBrowser, Codespaces, SelfHostedDesk, Orgo}, Lease state machine (start on first call, poll 3 s/4 min, idle 5 min (2–15), cap 60 min, extend ≤4 h, orphan stop on start), CloudGuard AlarmManager + 15-min WorkManager backstop, keys in SecretVault bound to host, minutes/cost ledger, COMPUTE_PAID card each session, CloudChip, cloud tool (run/screen/click/type/key/extend/stop) + cloudSteer | `computer/*, agent/SecretVault.kt` | LeaseTest ports a-team tests/v214 cases; stops with the app killed | P2-SF-01 | L | BH-36, BH-50, BH-55, BH-79, AT-46, AT-52, AM-58, MM-06, MM-09 |
| **P7-CP-09** | LiveScreenCard (frames only while visible, action line, Watch/Take over/Stop) + Computer viewer (full-screen black, pinch zoom, extra keys in take-over, amber frame, Hand back + note) + Desk → Computers page (screens, workspace, cloud, browser tabs, Your PC, running jobs) + Bot profile → Computer tab + Settings → Computers & workspace | `ui/team/computer/*` | ComputerShotsTest; polling stops when hidden | P7-CP-08 | M | BH-52, BH-53, BH-54, AT-47, MM-06, NEW-15 |
| **P7-API-01** | Take-over relay + self-hosted desk: gateway WebSocket VNC relay in Blaze-agent (server-side VNC auth, watch mode drops input server-side, password never on the phone), noVNC-in-WebView client (MPL notice), desk/ Docker image (Xvfb + fluxbox + Chrome + x11vnc + noVNC; BH box scripts for per-bot dressing, screen per bot, CDP 9200+display) | `Blaze-agent repo (relay), desk/Dockerfile, desk/box/*, ui/team/computer/VncView.kt` | Watch cannot send input (relay test); take-over round trip on a phone; image builds on arm64 + x86 hosts (owner-run) | P7-CP-08 | L | BH-50, BH-51, BH-52, BH-84, MM-06, NEW-22 |
| **P7-QA-01** | Real phone: Ubuntu install on A07/A12 (seccomp, phantom-process killer), storage, lease stop with app killed, take-over latency, review gate on a sample repo | `docs/overhaul/phone-checklists.md` | Checklist signed | P7-* | M | — |

### 6.9 P8 · 0.60.0 · Voice & everywhere

_Voice two-agent, X search, agents-api v2, MCP relay, A-team v2.20 (05 E6/E8)._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P8-VC-01** | Voice two-agent pattern on AM CallScreen: voice persona + send_task into the bot's queue + [work_landed] injection + recall_text_messages / search_conversations / peek_activity / end_the_call + call record row (duration, transcript ≤200 lines, tasks, one-sentence summary); backends on-device (default) · Gemini Live (user key) · xAI realtime (BYO key, gateway-minted 300 s secret) | `voice/VoiceAgent.kt, voice/xai/*, voice/gemini/*, ui/CallScreen.kt` | VoiceToolsTest; fake-mic device test modelled on BH voice-call.mjs | P5-TM-01 | L | BH-69, BH-85, MM-15 |
| **P8-VC-02** | Voice mode UI (hero orb amplitude, swipe to switch bot, live captions, result cards, group call strip, Mute/End/Type, Hand to chat) + per-bot voice settings (voice, speed 0.7–1.5, language, preview, My voice) + voice context builder (top 12 memories, routines, 14 recent messages, live activity) + dictation via on-device recognizer | `ui/team/VoiceMode.kt, voice/VoiceSettings.kt` | VoiceShotsTest; approvals never voice-only for money/delete | P8-VC-01 | M | BH-68, BH-70, BH-71, AM-40 |
| **P8-CN-01** | X search: optional x_search tool when an xAI key is in the vault (gateway /v1/x-search, handles/date filters, sources), default web search site:x.com with an honest label; /x command; Researcher worker uses it | `agent/query/XSearch.kt, agent/Web.kt` | XSearchTest (label shown without key) | P4-CN-03 | S | BH-39, MM-16 |
| **P8-API-01** | argus-agents-api v2 (moved to argus-mobile/agentsapi/): D1 schema (05 §9.3), bz_ auth via blaze-auth, PATs aat_ (read, jobs:write, approve), /v2/sync delta with rev + HLC LWW + 30-day tombstones, approvals decide (money/delete on-device-only default), events feed (no bodies, no memory), REST + MCP parity incl. list_routines/pause_routine/list_approvals/decide_approval/team_activity, OpenAPI; v1 API_TOKEN mode kept for self-hosted | `agentsapi/** (from a-team api/)` | Ports a-team api/test.mjs (20) + new v2 tests; owner deploys | P1-TM-05, P2-SF-01 | L | BH-37, BH-82, AT-62, AT-63, AT-64, MM-21, NEW-19 |
| **P8-API-02** | TeamSync adapter (team.db ↔ v2: bots, jobs, routines, approvals, events) + Settings → Sync & devices (status, self-host URL/token, 'Use your team from other apps' MCP endpoint copy, PATs create/revoke); MCP-created jobs arrive confirmed=false | `connect/TeamSync.kt, connect/SyncAdapters.kt, ui/argus/settings/SyncPage.kt` | TeamSyncTest two-device conflicts; remote approval applied only if still pending | P8-API-01 | M | BH-81, AT-55, MM-21, NEW-19 |
| **P8-API-03** | On-device MCP relay mcp://argus/<botId> through a gateway WebSocket (off by default, per-bot tokens from blaze-auth, approvals inside the call, money/delete on-device only) — used by Desktop now and HermesEngine in 1.1 | `connect/McpRelay.kt; Blaze-agent repo` | RelayTest; disabled by default | P2-SF-01 | L | BH-37, NEW-20 |
| **P8-AG-01** | CloudRunEngine: engine kind for long jobs on gateway /v1/runs (CloudRuns, resumable SSE, needs_device hand-back), counted against the 3 concurrent cloud runs cap | `agent/engines/CloudRunEngine.kt, agent/CloudRuns.kt` | CloudRunEngineTest with fake SSE | P0-AG-01 | M | NEW-21 |
| **P8-MG-01** | A-team v2.20 'Move to ARGUS' (a-team repo): banner + Export for ARGUS + sync push to agents-api; ARGUS onboarding detects ai.argus.agents (package query) and offers import + sync-down | `a-team app/www/*, team/ATeamImport.kt, setup/*` | End-to-end: A-team demo team appears in ARGUS with jobs, memory, routines | P3-MG-01, P8-API-02 | M | — |
| **P8-QA-01** | Real phone: voice barge-in + Bluetooth, two-device sync, MCP client (Claude/Cursor) reading the team, relay off by default | `docs/overhaul/phone-checklists.md` | Checklist signed | P8-* | M | — |

### 6.10 P9 · 1.0.0 · ARGUS 1.0

_Continuity re-skin, domain wiring, A-team retirement, release gate._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P9-LF-01** | Continuity re-skin onto ui/argus: Terminal (always dark mono), Design studio, browser sheets, Camera, phone overlays, Music/Video/Movies/Gallery, App store, site apps, Learn, Seller, Life logs pages, Meeting room remnants, Stage (behind Chat ⋯ → Edit stage) | `ui/** (remaining), mediaapps/**, browser/**, device/**` | ThemeEverywhereTest covers every screen; shots reviewed | P1-SH-01 | L | AM-09, AM-12, AM-42, AM-43, AM-47, AM-51 |
| **P9-LF-02** | Domain wiring of AM features via domain.json toolGroups: Money (Money Radar, Scam Shield, Bank mode), Health (Safe Walk, Health Connect, habits), People (notification replies, Group Brain, contacts), Learning (Learn/flashcards, Classroom, student pack), Create (image gen, Design studio, media, Clip Studio, Beat Lab), Work (Call Copilot, Seller pack, email/calendar), Phone area (phone control, Magic Hand, app store, settings toggles, USSD) | `assets/domains/**, agent/ToolRouter.kt` | DomainToolsTest: each AM tool group owned by ≥1 domain or the Manager | P5-AG-01 | M | AM-12, AM-36, AM-37, AM-38, AM-39, AM-44 |
| **P9-LF-03** | Unchanged-module register: blazerouter, wgproxy, store/ + storeapi, tools/, update/, link/ + dashboard, siteapps, appstore, movies, OfflineBrain/BackupBrain, BlazeQuery — compile unchanged, entry points reachable from the new IA (Spotlight + open by name) | `docs/overhaul/unchanged.md` | Spotlight/open-by-name test reaches every entry | P1-SH-06 | S | AM-11, AM-22, AM-30, AM-45, AM-49, AM-50, AM-52, AM-53, AM-59, AM-64, AM-65, AM-66, AM-67 |
| **P9-MG-01** | A-team retirement: verify sync-down for active users, A-team v2.21 opens ARGUS (or Play link), a-team README archived; in-app migration report | `a-team repo, team/ATeamImport.kt` | Migration report shows counts; a-team archived (owner) | P8-MG-01 | S | — |
| **P9-QA-01** | Release gate (§9): eval suites (terminal 69, code 29, refusals 63, safety, routing, team), low-RAM smoke 7/7, PhonePerfTest, accessibility audit (TalkBack, 200 % font, contrast), real-phone matrix sign-off | `evals/, app/src/androidTest/**, docs/overhaul/phone-checklists.md` | All gates green and signed by owner | P9-LF-* | L | NEW-17 |
| **P9-IL-01** | 1.0 docs and cleanup: README, about.md (blaze-docs), CHANGELOG, HANDOFF, STATUS, docs/overhaul status; delete stale gateway/ (R24) and the unreachable old Home board code | `README.md, app/blaze-docs/*, gateway/, home/*` | Docs match the app; BlazeDocs index rebuilt | P9-QA-01 | M | AM-02, AM-24, AM-63 |

### 6.11 P10 · 1.1.0 · Open engines (post-1.0)

_Hermes, cloud routines, optional paid adapters, targetSdk 35._

| ID | Ticket | Files / packages | Acceptance | Deps | Eff | Covers |
|---|---|---|---|---|---|---|
| **P10-AG-01** | HermesEngine (1.1): port BH agent/loop.ts (POST /v1/responses streaming → rows, triggers, captions), bot bound to a user-hosted Hermes via URL + vault key or Desktop link, ARGUS tools served over the P8 relay, Hermes memory projection two-way (engine-memory.ts read path), engine status/restart/version, own-screen detector, never restart a primary Hermes | `agent/engines/HermesEngine.kt` | HermesEngineTest with fake SSE; memory projection round trip | P8-API-03 | L | BH-29, BH-33, BH-34, BH-56, MM-18 |
| **P10-PR-01** | Cloud routines (1.1): routine runs_in=cloud registered as agents-api Cron Trigger → gateway /v1/runs for that bot with read-only/cloud tools; writes wait as pending approvals | `agentsapi/**, team/Routines.kt` | Cloud routine result arrives as event on next sync | P8-API-01, P8-AG-01 | M | NEW-23 |
| **P10-CN-01** | Optional paid adapters surfaced (1.1): Composio provider visible in Apps & connectors when configured server-side; Orgo screen-per-bot dressing via desk scripts; xAI TTS voices list | `ui/team/apps/*, computer/*` | Adapters off by default; cost shown | P4-API-01, P7-API-01 | M | BH-51 |
| **P10-CP-01** | Subscription sign-in for provider CLIs inside proot (CliAuth, ToS warning per provider), opened from Bot profile → Engine | `terminal/CliAuth.kt, ui/team/profile/EngineTab.kt` | ToS warning shown; tokens stay in the workspace | P7-CP-02 | S | BH-32, MM-19 |
| **P10-IL-01** | targetSdk 35 bump (separate release): Android 15 dataSync 6 h cap, FGS types, edge-to-edge | `app/build.gradle.kts, AndroidManifest.xml` | CI green; FGS behaviour verified on Android 15 | P9-IL-01 | M | — |

---

## 7. Full coverage matrix

Every feature from slices 1, 2 and 3 is assigned to a phase, a ticket (first = primary, which sets the phase) and a UI location from slice 4. **How**: Port = bring in · Unify = merge implementations (winner named) · Keep = Argus-mobile feature kept and re-skinned · New = designed in 04/05 · Not ported = by design, with the reason (tracked in `docs/overhaul/do-not-port.md`, P0-IL-03).

**Coverage check (generated by `06-parts/build.py`, which fails on any gap):** BH 89/89 · AT 72/72 · AM 68/68 · MM 25/25 · NEW 24 → **278 rows, 0 missing, 0 duplicates, every ticket reference resolves.** Tickets: 102; tickets with no feature row are pure enablers (QA, release, infra).

### 7.1 Bot HQ (slice 1, BH-01…BH-89)

| ID | Feature | Phase | Ticket(s) | UI location | How |
|---|---|---|---|---|---|
| BH-01 | Bot roster (multi-bot team; no 4-bot cap) | P1 (0.53.0) | P1-TM-01, P1-TM-04 | Team mode; Team roster; + → New bot | Unify |
| BH-02 | Primary bot (Doug) = Manager | P1 (0.53.0) | P1-TM-01 | Team → ARGUS row (Lead); Chat mode | Unify |
| BH-03 | Avatar system (shape × colour identity) | P0 (0.52.4) | P0-SH-02 | Bot profile → Soul → Look (A-team orbs; BH artwork not shipped) | Port (idea only) |
| BH-04 | Bot profile fields: label/description/instructions/mode/effort | P1 (0.53.0) | P1-TM-04, P3-SH-01 | Bot profile → Soul | Port |
| BH-05 | SOUL.md persona file (editable) | P3 (0.55.0) | P3-AG-03, P3-SH-01 | Bot profile → Soul → Soul file; `/soul` | Port |
| BH-06 | Pin / unread / last preview / status dots | P1 (0.53.0) | P1-TM-02, P1-TM-03 | Team mode rows + PinStrip | Port |
| BH-07 | Search palette (bots + messages + settings) | P1 (0.53.0) | P1-SH-01 | Top bar search → Spotlight | Unify (AM Spotlight) |
| BH-08 | Keyboard shortcuts | P1 (0.53.0) | P1-SH-01 | Hardware keyboard / ChromeOS shortcuts; gestures on phone | Port |
| BH-09 | Theme system/light/dark | P1 (0.53.0) | P1-SH-06 | Settings → Appearance; long-press me | Unify |
| BH-10 | Row-based transcript model | P1 (0.53.0) | P1-TM-02 | Thread (every bot and room) | Port |
| BH-11 | Streaming replies from the engine | P0 (0.52.4) | P0-AG-01 | Thread | Unify (AM loop wins) |
| BH-12 | Trigger types into a turn (user/routine/bot/system) | P1 (0.53.0) | P1-TM-02, P5-TM-01 | Thread provenance | Port |
| BH-13 | Live state + rotating activity phrases | P1 (0.53.0) | P1-SH-03 | Thread → ActivityLine; Team row status dot | Unify |
| BH-14 | 'Worked for · N steps' collapsible step list | P1 (0.53.0) | P1-SH-03 | Thread → WorkChip | Port |
| BH-15 | Stop / interrupt | P2 (0.54.0) | P2-SF-06 | Composer stop ■; LiveScreenCard Stop; notification | Unify (AM StopResume) |
| BH-16 | New conversation (generation bump) | P1 (0.53.0) | P1-TM-02, P1-SH-04 | Chat ✎; Bot profile → Overview → New conversation; `/fresh` | Port |
| BH-17 | Message provenance badges | P5 (0.57.0) | P5-TM-01 | Thread message labels | Port |
| BH-18 | Markdown + citation chips | P1 (0.53.0) | P1-SH-03 | Thread (AM ReplySources real sources) | Unify |
| BH-19 | Notices info/warn/error | P1 (0.53.0) | P1-SH-03 | Thread → SystemNote | Port |
| BH-20 | Composer (empty → call affordance) | P1 (0.53.0) | P1-SH-01 | Composer (mic when empty → Voice mode) | Unify (AM composer) |
| BH-21 | Date dividers, jump-to-bottom, empty-chat hero | P1 (0.53.0) | P1-SH-03 | Thread | Port |
| BH-22 | Dev fixtures mode | P1 (0.53.0) | P1-QA-01 | — (Compose @Preview fixtures for shots) | Port |
| BH-23 | Approval card Allow/Always/Don't (in tool call, 25-min expiry) | P2 (0.54.0) | P2-SF-01, P2-SF-02 | Thread ApprovalCard; Inbox; notification actions | Unify |
| BH-24 | Read-only auto-allow heuristic | P4 (0.56.0) | P4-CN-03 | — (policy, visible in Permissions) | Port |
| BH-25 | Permissions page (always-allow list, Ask again) | P2 (0.54.0) | P2-SF-04 | Settings → Privacy → Approvals & permissions | Port |
| BH-26 | Question card (ask_user) | P2 (0.54.0) | P2-SF-02 | Thread QuestionCard; Inbox | Port |
| BH-27 | Connect card (request_app_connection) | P2 (0.54.0) | P2-SF-02, P4-CN-04 | Thread ConnectCard; Inbox | Port |
| BH-28 | Draft card (implemented for real) | P2 (0.54.0) | P2-SF-02 | Thread DraftCard; Inbox | New (BH UI only) |
| BH-29 | Hermes engine per bot | P10 (1.1.0) | P10-AG-01 | Bot profile → Engine (1.1) | Port (deferred, D7) |
| BH-30 | Cache-stable app system prompt (+teammates, groups) | P3 (0.55.0) | P3-AG-03 | — | Unify (PromptParts) |
| BH-31 | Model & provider picker per bot | P3 (0.55.0) | P3-AG-06, P3-SH-01 | Bot profile → Engine (Automatic / Depth / own key) | Unify (D6) |
| BH-32 | Subscription sign-in via terminal | P10 (1.1.0) | P10-CP-01 | Bot profile → Engine → sign in (opens Terminal) | Port (deferred, ToS) |
| BH-33 | Engine status / restart / version | P3 (0.55.0) | P3-SH-01, P10-AG-01 | Bot profile → Engine status row; ThreadIntro pill | Port |
| BH-34 | Hermes own-screen detector + stop | P10 (1.1.0) | P10-AG-01 | Bot profile → Engine (Hermes bots) | Port (deferred) |
| BH-35 | Extra MCP servers for bots | P4 (0.56.0) | P4-CN-06 | Settings → Apps & connectors → MCP servers; Bot profile → Apps | Unify (AM Mcp.kt) |
| BH-36 | Hermes-native tool surface (capability checklist) | P7 (0.59.0) | P7-CP-02, P7-CP-08 | — (AM tools; desktop computer_use via cloud desk) | Unify |
| BH-37 | Per-bot MCP endpoint served | P8 (0.60.0) | P8-API-03, P8-API-01 | Settings → Sync & devices → Use your team from other apps | Port |
| BH-38 | save_file → per-bot Library | P7 (0.59.0) | P7-CP-04, P7-CP-05 | Thread FileCard; Desk → Library | Port |
| BH-39 | x_search (xAI) | P8 (0.60.0) | P8-CN-01 | Any thread; `/x`; Voice mode result cards | Port (optional key) |
| BH-40 | Routine tools create/list/update/delete (cron + tz) | P6 (0.58.0) | P6-PR-01 | Desk → Routines; Bot profile → Routines | Unify |
| BH-41 | Event triggers / create_event_routine | P4 (0.56.0) | P4-CN-07, P6-PR-01 | Desk → Routines → When… | Port (argus-connect) |
| BH-42 | stay_silent | P5 (0.57.0) | P5-TM-01, P6-PR-01 | — (routines/rooms/check-ins end silently) | Port |
| BH-43 | set_checkins | P6 (0.58.0) | P6-PR-05 | Settings → Notifications → Check-ins; Bot profile (ARGUS) → Routines | Port |
| BH-44 | create_task / update_task | P1 (0.53.0) | P1-TM-05 | Desk → Jobs (job steps); Thread step list | Unify (AT jobs) |
| BH-45 | send_to_bot (hop ≤3) | P5 (0.57.0) | P5-TM-01 | Thread HandoffCard + collapsed bot↔bot line | Port |
| BH-46 | create_group / post_to_group | P5 (0.57.0) | P5-TM-02 | Team rows (groups); + → New group | Port |
| BH-47 | ask_user tool | P2 (0.54.0) | P2-SF-02 | Thread QuestionCard | Port |
| BH-48 | request_app_connection tool | P2 (0.54.0) | P2-SF-02, P4-CN-04 | Thread ConnectCard | Port |
| BH-49 | Composio meta-tools (search/execute) | P4 (0.56.0) | P4-CN-03 | — (argus-connect /v1/me/tools + execute) | Unify (argus-connect) |
| BH-50 | Shared cloud computer, screen per bot | P7 (0.59.0) | P7-CP-08, P7-API-01 | Desk → Computers; Bot profile → Computer | Unify (providers) |
| BH-51 | Screen dressing (bot's own desktop look) | P7 (0.59.0) | P7-API-01, P10-CN-01 | Computer viewer (self-hosted desk/Orgo) | Port (desk image) |
| BH-52 | Live viewer: watch / take control / hand back / reset | P7 (0.59.0) | P7-CP-09, P7-API-01, P7-CP-07 | LiveScreenCard → Computer viewer | Port |
| BH-53 | Screenshot thumbnails on steps | P7 (0.59.0) | P7-CP-09 | Thread step list thumbnails | Port |
| BH-54 | Computer settings page | P7 (0.59.0) | P7-CP-09 | Settings → Computers & workspace | Port |
| BH-55 | Idle/hibernation watcher | P7 (0.59.0) | P7-CP-08 | — (Lease idle stop; CloudChip) | Unify (AT CloudGuard) |
| BH-56 | Respect primary bot's own Chrome | P10 (1.1.0) | P10-AG-01 | — (external engine rule) | Port (deferred) |
| BH-57 | Bot-to-bot messaging (visible, hop cap) | P5 (0.57.0) | P5-TM-01 | Thread; People → Bots talking | Port |
| BH-58 | Group chats (rooms, ≤3 responders, members) | P5 (0.57.0) | P5-TM-02 | Team rows; Room thread; Bot profile → Connections | Port |
| BH-59 | Subagents (hidden child bots ≤4) | P5 (0.57.0) | P5-TM-03 | — (shown as steps/handoffs) | Unify (AM BgAgents) |
| BH-60 | Hermes delegation inside one task | P5 (0.57.0) | P5-TM-03 | — | Unify (AM Dispatch) |
| BH-61 | Cron routines (+ UI: upcoming, run now, pause) | P6 (0.58.0) | P6-PR-01, P6-PR-02 | Desk → Routines; Bot profile → Routines | Unify |
| BH-62 | Event routines (realtime triggers) | P4 (0.56.0) | P4-API-01, P6-PR-03 | Desk → Routines → When… | Port (argus-connect) |
| BH-63 | Proactive check-ins (primary bot) | P6 (0.58.0) | P6-PR-05, P6-PR-04 | ARGUS thread; Settings → Notifications → Check-ins | Unify |
| BH-64 | Tasks per bot + global Tasks view | P1 (0.53.0) | P1-TM-07 | Desk → Jobs (By bot filter); Bot profile → Overview | Unify (AT jobs) |
| BH-65 | Library (bot's files) | P7 (0.59.0) | P7-CP-05 | Desk → Library; Bot profile → Overview count | Port |
| BH-66 | Skills per bot | P4 (0.56.0) | P4-CN-05, P4-CN-04 | Bot profile → Skills; Settings → Skills | Unify |
| BH-67 | Memory view MEMORY.md/USER.md, forget one | P3 (0.55.0) | P3-AG-04, P3-SH-01 | Bot profile → Memory; You → What the team knows | Unify (Mind) |
| BH-68 | Voice agent memory injection | P8 (0.60.0) | P8-VC-02 | Voice mode | Port |
| BH-69 | Voice call (two-agent: send_task, work_landed, peek/recall/search) | P8 (0.60.0) | P8-VC-01 | Voice mode; tap mic | Port |
| BH-70 | Per-bot voice settings | P8 (0.60.0) | P8-VC-02 | Bot profile → Voice; long-press mic | Port |
| BH-71 | Dictation | P8 (0.60.0) | P8-VC-02 | Hold mic | Unify (on-device) |
| BH-72 | App marketplace (catalog, categories, connect/disconnect) | P4 (0.56.0) | P4-CN-02 | Settings → Apps & connectors | Unify (argus-connect) |
| BH-73 | Per-user Composio sessions + execution | P4 (0.56.0) | P4-API-01 | — (server, optional adapter) | Not in app (server adapter) |
| BH-74 | 'Add to Slack' suggestion | P4 (0.56.0) | P4-CN-02 | Bot profile → Overview → Suggested; Apps chips | Port |
| BH-75 | Google sign-in via Composio Gmail | P0 (0.52.4) | P0-IL-03 | — (ARGUS account in Onboarding) | Not ported (ARGUS account replaces) |
| BH-76 | Allow-list ALLOWED_EMAILS | P0 (0.52.4) | P0-IL-03 | — (gateway waitlist) | Not ported (server-only) |
| BH-77 | Dev login / owner key / sign-in links | P0 (0.52.4) | P0-IL-03 | — (Desktop link QR is the phone equivalent) | Not ported (dev-only) |
| BH-78 | Sessions (cookie) | P0 (0.52.4) | P0-IL-03 | — (bz_ token in Keystore) | Not ported (ARGUS token) |
| BH-79 | Secrets hygiene | P7 (0.59.0) | P7-CP-08, P2-SF-05 | — | Unify (AM SecretVault) |
| BH-80 | Known security gaps (must not regress) | P2 (0.54.0) | P2-SF-01, P7-CP-02 | — | Fixed (policy + per-bot isolation) |
| BH-81 | SSE event hub | P1 (0.53.0) | P1-TM-02, P8-API-02 | — (StateFlows on phone; agents-api events) | Unify |
| BH-82 | REST API surface (reference) | P8 (0.60.0) | P8-API-01 | — (agents-api v2 subset) | Port (subset) |
| BH-83 | SQLite schema | P0 (0.52.4) | P0-TM-01 | — | Port (team.db) |
| BH-84 | Install/upgrade on the computer | P7 (0.59.0) | P7-API-01 | — (desk/ image) | Port (self-hosted desk) |
| BH-85 | Tests (e2e, voice-call, desk) | P0 (0.52.4) | P0-QA-01, P8-VC-01 | — | Port (as specs) |
| BH-86 | Grokbot branding leftovers | P0 (0.52.4) | P0-IL-03 | — | Not ported |
| BH-87 | Muse-style social connections (not in BH) | P5 (0.57.0) | P5-SH-04 | Life → People; Bot profile → Connections | New |
| BH-88 | Editable soul + memory files | P3 (0.55.0) | P3-AG-04, P3-SH-01 | Bot profile → Soul / Memory | New (editor) |
| BH-89 | Grok Bot features not in BH code (templates, drafts, subagents) | P1 (0.53.0) | P1-TM-04, P2-SF-02, P5-TM-03 | + → New bot → From a template; DraftCard | Port/New |

### 7.2 A-team (slice 2, feature index #1…#72)

AT-nn = row nn of the 02 feature index; the section code (A1…K3) is in brackets.

| ID | Feature | Phase | Ticket(s) | UI location | How |
|---|---|---|---|---|---|
| AT-01 | WebView shell + Native bridge (A1) | P0 (0.52.4) | P0-IL-03 | — | Not ported (native services replace) |
| AT-02 | Nav stack + reopen last page (A2) | P1 (0.53.0) | P1-SH-01 | — (Back returns where you came from; last mode restored) | Port |
| AT-03 | Notch Bots/Chat pill (A3) | P1 (0.53.0) | P1-SH-01 | Notch → Team·Chat·Life·Desk | Port |
| AT-04 | Chat page bound to Manager (A4) | P1 (0.53.0) | P1-SH-04 | Chat mode | Port |
| AT-05 | Chat drawer, saved chats (v2.19, A5) | P1 (0.53.0) | P1-SH-04 | Chat mode → history drawer | Port (D1) |
| AT-06 | Team home as chats (A6) | P1 (0.53.0) | P1-TM-03 | Team mode | Port |
| AT-07 | + sheet (A7) | P1 (0.53.0) | P1-SH-01 | Top bar + → New menu; composer + → Add-to-chat sheet | Port |
| AT-08 | Theme Match phone/Dark/Light (A8) | P1 (0.53.0) | P1-SH-06 | Settings → Appearance | Unify |
| AT-09 | First-run setup (nickname, workspace wait) (A9) | P1 (0.53.0) | P1-SH-06, P7-CP-01 | Onboarding → Setup (one list) | Unify (SetupPlan) |
| AT-10 | Sheets/toasts/long-press/empty states (A10) | P1 (0.53.0) | P1-SH-02 | Everywhere (kit) | Port |
| AT-11 | Bots as chats (B1) | P1 (0.53.0) | P1-TM-02, P1-TM-03 | Team mode; Thread with intro + JobCard | Port |
| AT-12 | Bot editor + look (B2) | P1 (0.53.0) | P1-TM-04 | + → New bot; Bot profile → Soul | Port |
| AT-13 | Orb avatars, Manager sphere, app icon (B3) | P0 (0.52.4) | P0-SH-02 | Everywhere (BotOrb) | Port |
| AT-14 | Built-in Manager, locked (B4) | P1 (0.53.0) | P1-TM-01 | Team → ARGUS row; Chat mode | Unify (ARGUS = Manager) |
| AT-15 | Manager tools (B5) | P1 (0.53.0) | P1-TM-04, P1-TM-05 | — (ARGUS by text) | Port |
| AT-16 | update_agent / note_team (B6) | P1 (0.53.0) | P1-TM-04 | Thread Undo chip | Port |
| AT-17 | Jobs model + Jobs page (C1) | P1 (0.53.0) | P1-TM-05, P1-TM-07 | Desk → Jobs | Port |
| AT-18 | Job queue runs + waits-on (C2) | P1 (0.53.0) | P1-TM-05 | Desk → Jobs status; Thread system notes | Port (parallel, closed-app) |
| AT-19 | Approvals inline / N need you (C3) | P2 (0.54.0) | P2-SF-03 | Team NeedYouBanner; Inbox; OkCard | Unify |
| AT-20 | Verified done_job (C4) | P2 (0.54.0) | P2-SF-07 | — (job blocked until verified) | Port |
| AT-21 | Failed jobs + retry/backoff (C5) | P1 (0.53.0) | P1-TM-05 | Job detail sheet → log, Retry | Port |
| AT-22 | Background jobs WIP (C6) | P7 (0.59.0) | P7-CP-03 | Desk → Computers → running jobs; Thread tool card | Unify (AM JobKeeper) |
| AT-23 | Compose graph (C7) | P5 (0.57.0) | P5-SH-02 | Team roster → Graph; + → Compose graph | Port |
| AT-24 | Streaming + parts + Stop (D1) | P2 (0.54.0) | P2-SF-06 | Thread; Composer stop | Unify (rules into AM loop) |
| AT-25 | Native tools + fence fallback (D2) | P2 (0.54.0) | P2-SF-07 | — | Unify (TextCalls cases) |
| AT-26 | Tool cards + grouping (D3) | P1 (0.53.0) | P1-SH-03 | Thread → WorkChip / ToolCard | Port |
| AT-27 | Markdown renderer (D4) | P1 (0.53.0) | P1-SH-03 | Thread | Unify (restyle AM) |
| AT-28 | Attachments (D5) | P7 (0.59.0) | P7-CP-02, P1-SH-01 | Composer + → Photo/Camera/File | Unify |
| AT-29 | Context window / summary / TZ (D6) | P0 (0.52.4) | P0-API-01 | — | Unify (blaze_context fix) |
| AT-30 | Home box command router (D7) | P1 (0.53.0) | P1-TM-06 | Team composer 'Tell the team…' + plan sheet | Port |
| AT-31 | Sign-in banner / offline replies (D8) | P1 (0.53.0) | P1-TM-06 | Thread sign-in card | Unify (AM SignInGate/OfflineBrain) |
| AT-32 | No filler / unbacked claims (D9) | P2 (0.54.0) | P2-SF-07 | — (SystemNote 'not verified') | Unify |
| AT-33 | Workspace snapshot / working set (D10) | P7 (0.59.0) | P7-CP-02 | — | Port |
| AT-34 | Guard layer (E1) | P2 (0.54.0) | P2-SF-07 | — | Port |
| AT-35 | Code review (E2) | P7 (0.59.0) | P7-CP-06 | Thread ReviewCard; Settings → Code review | Port |
| AT-36 | Live gateway model picking (F1) | P0 (0.52.4) | P0-API-01 | Settings → Models & engines (read-only list) | Port |
| AT-37 | Gemini own key per bot (F2) | P3 (0.55.0) | P3-AG-06 | Settings → Models → Your keys; Bot profile → Engine | Port |
| AT-38 | Needle on-device commands (F3) | P1 (0.53.0) | P1-TM-06, P5-TM-04 | Composer (offline commands); Settings → Models → On-device | Unify (native FastLane) |
| AT-39 | Needle fine-tune data (F4) | P5 (0.57.0) | P5-TM-04 | — (ml/needle-ft) | Port |
| AT-40 | Plan display (F5) | P1 (0.53.0) | P1-SH-06 | You → Plan & usage | Unify (AM Billing) |
| AT-41 | Ubuntu PRoot workspace + tmux per bot (G1) | P7 (0.59.0) | P7-CP-01, P7-CP-02 | Bot profile → Computer → Workspace | Unify (AM terminal) |
| AT-42 | Terminal (G2) | P7 (0.59.0) | P7-CP-03 | Desk → Computers → Terminal; + → Open terminal | Unify (AM terminal) |
| AT-43 | attach/save/preview/share/zip (G3) | P7 (0.59.0) | P7-CP-04 | Thread FileCard; Desk → Library | Port |
| AT-44 | write_file (G4) | P7 (0.59.0) | P7-CP-04 | — | Port |
| AT-45 | Bots' browser (G5) | P7 (0.59.0) | P7-CP-07 | Bot profile → Computer → Browser tabs | Unify (AM origin) |
| AT-46 | Cloud computer + lease + guard (G6) | P7 (0.59.0) | P7-CP-08 | Desk → Computers → Cloud; CloudChip | Port |
| AT-47 | Computer view card (G7) | P7 (0.59.0) | P7-CP-09 | Thread LiveScreenCard → Computer viewer | Port |
| AT-48 | ARGUS sign-in (H1) | P1 (0.53.0) | P1-SH-06 | Onboarding; You → Account | Unify (AM) |
| AT-49 | Connectors page + logos (H2) | P4 (0.56.0) | P4-CN-02 | Settings → Apps & connectors | Port |
| AT-50 | Connector tools for agents (H3) | P4 (0.56.0) | P4-CN-03 | — (per-bot) | Port |
| AT-51 | My skills + Polish + originals (H4) | P4 (0.56.0) | P4-CN-05 | Settings → Skills; Bot profile → Skills | Port |
| AT-52 | Vault / provider keys (H5) | P7 (0.59.0) | P7-CP-08 | Settings → Privacy → Wallet & logins | Unify (AM SecretVault) |
| AT-53 | Hybrid memory (H6) | P3 (0.55.0) | P3-AG-01 | Bot profile → Memory | Unify (scopes) |
| AT-54 | Manager profile + autoLearn (H7) | P3 (0.55.0) | P3-AG-05, P3-SH-02 | You → What the team knows | Unify |
| AT-55 | Two-way team sync (H8) | P8 (0.60.0) | P8-API-02 | Settings → Sync & devices | Port (v2) |
| AT-56 | Backup / import / erase / demo (H9) | P1 (0.53.0) | P1-MG-01, P1-TM-03 | Settings → Sync & devices → Backup; Team empty state (Demo) | Port |
| AT-57 | Removed Connections / gmail helpers (H10) | P0 (0.52.4) | P0-IL-03 | — | Not ported (removed in v2.18) |
| AT-58 | Routines closed-app (I1) | P6 (0.58.0) | P6-PR-01 | Desk → Routines | Unify (AM engine) |
| AT-59 | Daily check + suggestion cards (I2) | P6 (0.58.0) | P6-PR-04 | Inbox → Suggestions; ARGUS Today strip | Unify |
| AT-60 | Notification list + system notifs (I3) | P2 (0.54.0) | P2-SF-08 | Bell sheet; Android notifications | Unify |
| AT-61 | Vault-style pings: avatar, edge glow, haptics, pop-up (I4) | P6 (0.58.0) | P6-SH-01 | System + in-app | Port |
| AT-62 | REST /v1 (J1) | P8 (0.60.0) | P8-API-01 | — | Port (v2, v1 kept) |
| AT-63 | MCP /mcp + OpenAPI (J2) | P8 (0.60.0) | P8-API-01 | Settings → Sync & devices → MCP endpoint | Port |
| AT-64 | Auth / tenancy (J3) | P8 (0.60.0) | P8-API-01 | — | Port (bz_ + PATs) |
| AT-65 | Evals harness (K1) | P0 (0.52.4) | P0-QA-01 | — | Port |
| AT-66 | Playwright / Java tests (K2) | P0 (0.52.4) | P0-QA-01 | — | Port (as specs) |
| AT-67 | No-Gradle build (K3) | P0 (0.52.4) | P0-IL-03 | — | Not ported (Gradle) |
| AT-68 | Settings cards (Workspace, Cloud, Connectors, Skills, Manager, Notifications, Review, Memory, Sync, Appearance, Models, On-device, Data) | P1 (0.53.0) | P1-SH-06 | Settings (§4.11 tree) | Unify |
| AT-69 | Step budget + StepGuard | P2 (0.54.0) | P2-SF-06, P2-SF-07 | WorkChip 'Stopped after N · Go on' | Port |
| AT-70 | Interrogation bounce / short replies | P2 (0.54.0) | P2-SF-07 | — | Port |
| AT-71 | Server / `cmd &` detach | P2 (0.54.0) | P2-SF-07 | — | Port |
| AT-72 | Parallel tool calls (not started in AT) | P5 (0.57.0) | P5-TM-03 | — | Unify (AM ToolScheduler) |

### 7.3 Argus-mobile (slice 3 inventory a.1–a.9)

AM-nn numbers the rows of 03 §a in document order: a.1 → AM-01…13, a.2 → 14…25, a.3 → 26…30, a.4 → 31…35, a.5 → 36…45, a.6 → 46…53, a.7 → 54…59, a.8 → 60…62, a.9 → 63…68. Sub-features named in slice 4's table (Safe Walk, Seller pack, Time Capsule, Call Copilot, Learn, image generation, Undo center, Jarvis, habits…) ride with their parent row and are wired to domains in P9-LF-02.

| ID | Feature | Phase | Ticket(s) | UI location | How |
|---|---|---|---|---|---|
| AM-01 | Space shell (top bar, Home|Chat|Projects pill, backdrop) | P1 (0.53.0) | P1-SH-01 | Replaced by A-team shell; backdrop = Orbit ground | Unify |
| AM-02 | Home card stack (+ legacy board) | P1 (0.53.0) | P1-SH-05, P9-IL-01 | Life mode → Panels | Keep (moved); legacy board deleted at 1.0 |
| AM-03 | Chat (bubbles, pills, quotes, reactions, receipts, /clear, private) | P1 (0.53.0) | P1-SH-04 | Chat mode | Keep (re-skinned) |
| AM-04 | Projects | P1 (0.53.0) | P1-TM-07 | Desk → Projects; Life → Projects | Keep (moved) |
| AM-05 | Profile = Settings root | P1 (0.53.0) | P1-SH-06 | You (avatar) → Settings | Unify |
| AM-06 | Onboarding + one-screen setup | P1 (0.53.0) | P1-SH-06, P5-SH-01, P7-CP-01 | Onboarding → Setup | Keep (+ team picker, Ubuntu) |
| AM-07 | Spotlight | P1 (0.53.0) | P1-SH-01 | Top bar search | Keep |
| AM-08 | Composer (+, mic, hints, slash, attachments) | P1 (0.53.0) | P1-SH-01 | Composer | Unify |
| AM-09 | Stage | P9 (1.0.0) | P9-LF-01 | Chat mode ⋯ → Edit stage | Keep (R22) |
| AM-10 | Rich replies (charts, tables, mini tools, forms, diagrams) | P1 (0.53.0) | P1-SH-03 | Thread → floating window | Keep (restyled) |
| AM-11 | Views (open_view without a model call) | P9 (1.0.0) | P9-LF-03 | Slash `/name`, Spotlight | Keep |
| AM-12 | Design studio | P9 (1.0.0) | P9-LF-01, P9-LF-02 | Life → Create → Design; `/design` | Keep |
| AM-13 | Design tokens (SpacePalette, GlassKit, Kit, icons, motion) | P0 (0.52.4) | P0-SH-01, P1-SH-01 | — | Unify (ui/argus aliases, then removed) |
| AM-14 | Agent loop (Agent.Session) | P0 (0.52.4) | P0-AG-01 | — | Keep (LocalEngine, frozen size) |
| AM-15 | Rapid messages / owed-reply ledger | P0 (0.52.4) | P0-IL-01 | — | Keep |
| AM-16 | Background agents, Handoff, TaskKeeper, ProgressGate | P1 (0.53.0) | P1-TM-05 | Thread pills; Desk → Jobs | Keep (jobs run on it) |
| AM-17 | Specialists (10 built-in + user-made) | P1 (0.53.0) | P1-TM-01, P5-TM-03 | + → New bot → From a template; workers | Unify (WORKER templates) |
| AM-18 | Orchestra (Blueprint DAG, parallel tools) | P5 (0.57.0) | P5-TM-03, P5-TM-04 | — (multi-domain goals) | Keep (+ ownerBotId) |
| AM-19 | Tool diet & routing (ToolRouter, ExecRoute…) | P4 (0.56.0) | P4-CN-03 | — | Keep (+ cap 64, apps) |
| AM-20 | Honesty / guards | P2 (0.54.0) | P2-SF-07 | SystemNote; Settings → Diagnostics | Keep (+ AT cases) |
| AM-21 | Approval & autonomy (ConfirmGate, CommandPolicy, Autonomy) | P2 (0.54.0) | P2-SF-01 | Inbox; Settings → Approvals & permissions | Unify (Policy.decide) |
| AM-22 | BlazeQuery (fact check while typing) | P9 (1.0.0) | P9-LF-03 | Thread (sources) | Keep |
| AM-23 | Skills (SkillLibrary, Match, Packs, Marketplace, Learning) | P4 (0.56.0) | P4-CN-05 | Settings → Skills; Bot profile → Skills | Unify |
| AM-24 | Self-knowledge (BlazeDocs, SelfControl) | P9 (1.0.0) | P9-IL-01 | — (about.md updated) | Keep |
| AM-25 | Personas (OnePersona, Duo, HaloStudio, SharedPersona) | P3 (0.55.0) | P3-SH-01, P5-TM-02 | Bot profile → Soul → Personality; Rooms | Unify (R15) |
| AM-26 | OpenAI-compatible client (LlmClient, budgets, failover) | P3 (0.55.0) | P3-AG-06 | — | Keep |
| AM-27 | Argus Cloud gateway (Cloud.kt, billing, quota) | P0 (0.52.4) | P0-API-01 | You → Plan & usage | Keep (+ LiveModels) |
| AM-28 | Legacy providers / free routing (hidden) | P3 (0.55.0) | P3-AG-06 | Settings → Models → Your keys (Gemini) | Keep (hidden) |
| AM-29 | Tiers (no picker; Insight/Oracle/Panoptes) | P3 (0.55.0) | P3-AG-06 | Bot profile → Engine → Depth | Keep |
| AM-30 | On-device (Needle FastLane, OfflineBrain, BackupBrain, BlazeRouter) | P1 (0.53.0) | P1-TM-06, P9-LF-03 | Settings → Models → On-device | Keep |
| AM-31 | One Mind (blaze.db, journal, review) | P3 (0.55.0) | P3-AG-01 | You → What the team knows | Keep (+ mem_scope) |
| AM-32 | Chat tier + cache tier | P3 (0.55.0) | P3-AG-01 | Bot profile → Memory → meter | Keep |
| AM-33 | Pinned facts / 'What Argus is holding' | P3 (0.55.0) | P3-SH-01 | Bot profile → Memory → Holding; `/holding` | Keep (per bot) |
| AM-34 | Task memory (+ working set) | P7 (0.59.0) | P7-CP-02 | — | Keep |
| AM-35 | Cloud sync (E2E) | P3 (0.55.0) | P3-API-01 | Settings → Sync & devices | Keep (+ scopes) |
| AM-36 | Phone control (a11y, orient, pointer, settings jumps) | P9 (1.0.0) | P9-LF-02 | Life → Phone area; any bot via tool | Keep |
| AM-37 | Magic Hand | P9 (1.0.0) | P9-LF-02, P6-SH-02 | System overlay; Settings → Phone & permissions | Keep |
| AM-38 | Messaging, calls, SMS, USSD, contacts, calendar | P9 (1.0.0) | P9-LF-02 | Phone/People/Work tools (SEND = card) | Keep |
| AM-39 | Notification replies, Group Brain, Scam Shield, Money Radar | P9 (1.0.0) | P9-LF-02, P5-SH-04 | People page; Money domain page; Inbox alerts | Keep |
| AM-40 | Voice (wake word, read aloud, My voice, Gemini voice) | P8 (0.60.0) | P8-VC-02 | Voice mode; Settings → Voice | Keep |
| AM-41 | Assistant entry points (assist, tile, orb, widget) | P6 (0.58.0) | P6-SH-02 | Assist panel; QS tiles; floating orb; widgets | Keep (extended) |
| AM-42 | Camera (Scan, Text, Ask, Photo, Translate, Document) | P9 (1.0.0) | P9-LF-01 | Composer + → Camera; `/camera` | Keep |
| AM-43 | Life logs / brainstorm | P5 (0.57.0) | P5-SH-03, P9-LF-01 | Life → Mind area; `/lifelog` | Keep (no QS tile, R23) |
| AM-44 | Bank mode | P9 (1.0.0) | P9-LF-02 | Money domain; Settings → Privacy → Bank mode | Keep |
| AM-45 | Device health (Watchdog, Thermal, CrashLog) | P9 (1.0.0) | P9-LF-03 | Settings → Diagnostics | Keep |
| AM-46 | Terminal (BusyBox, Alpine + distros, tabs, jobs, git) | P7 (0.59.0) | P7-CP-01, P7-CP-03 | Desk → Computers → Terminal | Keep (+ Ubuntu workspace, bot tabs) |
| AM-47 | Browser (tabs, VPN, tracker block, logins, page ask) | P7 (0.59.0) | P7-CP-07, P9-LF-01 | Desk → Computers → Browser; Settings → Phone → Browser | Keep (+ per-bot profiles) |
| AM-48 | Argus Phone browser engine | P7 (0.59.0) | P7-CP-07 | Computers → Browser → Open on phone | Keep |
| AM-49 | Site apps | P9 (1.0.0) | P9-LF-03 | Life → Phone area → Apps | Keep |
| AM-50 | App Store (curated, VirusTotal) | P9 (1.0.0) | P9-LF-03 | Life → Phone area → Apps; `/store` | Keep |
| AM-51 | Music / Video / Movies / Gallery | P9 (1.0.0) | P9-LF-01 | Life → Create → Media; Desk → Library → Media | Keep (re-skinned) |
| AM-52 | Desktop link / local dashboard | P9 (1.0.0) | P9-LF-03 | Desk → Computers → Your PC; Settings → Sync & devices | Keep |
| AM-53 | Updates | P9 (1.0.0) | P9-LF-03 | Settings → Updates | Keep |
| AM-54 | ARGUS account (blaze-auth, MFA) | P1 (0.53.0) | P1-SH-06 | Onboarding; You → Account | Keep |
| AM-55 | On-phone OAuth connectors | P4 (0.56.0) | P4-CN-02 | Settings → Apps & connectors → On this phone | Keep (device-bound only, D5) |
| AM-56 | MCP client | P4 (0.56.0) | P4-CN-06 | Settings → Apps → MCP servers | Keep (+ per-bot grants) |
| AM-57 | Key / skill sync via account | P4 (0.56.0) | P4-CN-05 | — | Unify (skills → argus-connect) |
| AM-58 | Secrets (Keystore, SecretVault, Secure Form) | P7 (0.59.0) | P7-CP-08, P2-SF-05 | Thread Secure form; Settings → Privacy → Wallet & logins | Keep (+ host binding) |
| AM-59 | URL safety | P9 (1.0.0) | P9-LF-03 | — | Keep |
| AM-60 | Scheduled prompts + shell jobs | P6 (0.58.0) | P6-PR-01 | Desk → Routines | Keep (+ owner bot, cron) |
| AM-61 | Trigger routines / Autopilot (place, Wi-Fi, BT) | P6 (0.58.0) | P6-PR-02, P6-PR-03 | Desk → Routines → When… (device) | Keep (→ SignalBus) |
| AM-62 | Proactive engine, dial, follow-ups, habits, Jarvis | P6 (0.58.0) | P6-PR-04 | Settings → Notifications & proactivity; Inbox | Unify (one dial) |
| AM-63 | Repo gateway/ (stale 0.3.0) | P0 (0.52.4) | P0-IL-02, P9-IL-01 | — | Read-only now, deleted at 1.0 |
| AM-64 | blazerouter (Go, local) | P9 (1.0.0) | P9-LF-03 | Settings → Models → Advanced | Keep |
| AM-65 | wgproxy (Go, browser VPN) | P9 (1.0.0) | P9-LF-03 | Browser VPN | Keep |
| AM-66 | store/ catalog + tooling | P9 (1.0.0) | P9-LF-03 | — | Keep |
| AM-67 | storeapi module | P9 (1.0.0) | P9-LF-03 | — | Keep |
| AM-68 | tools/ (proot fetch, smoke gate, CI helpers) | P1 (0.53.0) | P1-QA-01 | — | Keep (+ smoke for new screens) |

### 7.4 Argus-mobile merge map (slice 3 c.1 #1–25)

| ID | Feature | Phase | Ticket(s) | UI location | How |
|---|---|---|---|---|---|
| MM-01 | Team of bots as chats | P1 (0.53.0) | P1-TM-01, P1-TM-02 | Team mode | — |
| MM-02 | Manager bot = Argus lead | P1 (0.53.0) | P1-TM-01, P1-TM-04 | Team → ARGUS row; Chat mode | — |
| MM-03 | Jobs & approvals inline, verified done | P1 (0.53.0) | P1-TM-05, P2-SF-02, P2-SF-07 | Thread; Desk → Jobs / Inbox | — |
| MM-04 | Compose graph | P5 (0.57.0) | P5-SH-02 | Team roster → Graph | — |
| MM-05 | Ubuntu workspace per bot | P7 (0.59.0) | P7-CP-01, P7-CP-02 | Bot profile → Computer | — |
| MM-06 | Computer you can watch / take over | P7 (0.59.0) | P7-CP-08, P7-CP-09, P7-API-01 | Computer viewer | — |
| MM-07 | Bots' own browser | P7 (0.59.0) | P7-CP-07 | Bot profile → Computer → Browser | — |
| MM-08 | Connectors (argus-connect) | P4 (0.56.0) | P4-CN-01, P4-CN-03 | Settings → Apps & connectors | — |
| MM-09 | Vault | P7 (0.59.0) | P7-CP-08 | Settings → Privacy | — |
| MM-10 | Skills | P4 (0.56.0) | P4-CN-05 | Settings → Skills | — |
| MM-11 | Memory / soul | P3 (0.55.0) | P3-AG-01, P3-AG-03 | Bot profile → Soul / Memory | — |
| MM-12 | Routines / check-ins / proactivity | P6 (0.58.0) | P6-PR-01, P6-PR-04 | Desk → Routines | — |
| MM-13 | Bots talking to bots | P5 (0.57.0) | P5-TM-01, P5-TM-02 | Thread HandoffCard; Rooms | — |
| MM-14 | Texting-style UI with many bots | P1 (0.53.0) | P1-TM-03, P1-SH-03 | Team mode; Thread | — |
| MM-15 | Voice (xAI optional) | P8 (0.60.0) | P8-VC-01 | Voice mode | — |
| MM-16 | X search | P8 (0.60.0) | P8-CN-01 | Any thread; `/x` | — |
| MM-17 | Library | P7 (0.59.0) | P7-CP-05 | Desk → Library | — |
| MM-18 | Hermes engine (optional) | P10 (1.1.0) | P10-AG-01 | Bot profile → Engine (1.1) | — |
| MM-19 | Model per bot / subscriptions | P3 (0.55.0) | P3-AG-06, P10-CP-01 | Bot profile → Engine | — |
| MM-20 | On-device Needle commands | P1 (0.53.0) | P1-TM-06, P5-TM-04 | Composer | — |
| MM-21 | Team sync / external API | P8 (0.60.0) | P8-API-01, P8-API-02 | Settings → Sync & devices | — |
| MM-22 | Built-in code review | P7 (0.59.0) | P7-CP-06 | Thread ReviewCard | — |
| MM-23 | Notch / edge glow / heads-up with bot avatar | P1 (0.53.0) | P1-SH-01, P6-SH-01, P2-SF-08 | Notch; system notifications | — |
| MM-24 | ARGUS account, plan, billing | P1 (0.53.0) | P1-SH-06 | You → Account / Plan & usage | — |
| MM-25 | Deep research / multi-agent big jobs | P5 (0.57.0) | P5-TM-04 | — (Orchestra) | — |

### 7.5 New in slices 4/5 (not in any repo)

| ID | Feature | Phase | Ticket(s) | UI location | How |
|---|---|---|---|---|---|
| NEW-01 | Four modes Team · Chat · Life · Desk + notch/swipe/slash switching | P1 (0.53.0) | P1-SH-01 | Notch | New (04) |
| NEW-02 | 14 life-domain agents + 10 Life areas | P5 (0.57.0) | P5-AG-01, P5-SH-03 | Life mode; Team chips | New (05/04) |
| NEW-03 | 'Set up my life team' interview + picker | P5 (0.57.0) | P5-SH-01 | Onboarding / Team empty state | New |
| NEW-04 | Routing v2 by domain hints + multi-domain orchestration | P5 (0.57.0) | P5-TM-04 | — | New (05) |
| NEW-05 | HandoffCard + animated team-talk line | P1 (0.53.0) | P1-SH-03, P5-TM-01 | Thread; Team list | New (04) |
| NEW-06 | Huddle mode in rooms | P5 (0.57.0) | P5-TM-02 | Room thread | New (05) |
| NEW-07 | People graph + person sheet + Connections tab | P5 (0.57.0) | P5-SH-04 | Life → People; Bot profile → Connections | New |
| NEW-08 | Memory scopes, share tags, proposals | P3 (0.55.0) | P3-AG-01, P3-AG-05 | You → What the team knows | New (05) |
| NEW-09 | Unified Policy.decide + ActionClass + SpendGuard | P2 (0.54.0) | P2-SF-01, P2-SF-04 | Settings → Approvals & permissions | New (05) |
| NEW-10 | Hash-chained audit log + Activity page | P2 (0.54.0) | P2-SF-05 | Settings → Activity | New (05) |
| NEW-11 | Pause all agents | P2 (0.54.0) | P2-SF-06 | Notch long-press; QS | New (05) |
| NEW-12 | Inbox with one needs-you truth | P2 (0.54.0) | P2-SF-03 | Bell; Desk → Inbox | New (04) |
| NEW-13 | SignalBus → proposers → ARGUS arbiter | P6 (0.58.0) | P6-PR-03, P6-PR-04 | Inbox → Suggestions | New (05) |
| NEW-14 | Morning brief, day line, weekly review | P6 (0.58.0) | P6-PR-05 | ARGUS thread; Life day line | New (05/04) |
| NEW-15 | Widgets (Team, Ask, Today, Live screen) | P6 (0.58.0) | P6-SH-02, P7-CP-09 | Home screen | New (04) |
| NEW-16 | QS Approvals tile, launcher shortcuts, deep links, share-to-bot ranking | P6 (0.58.0) | P6-SH-02 | System | New (04) |
| NEW-17 | Handedness, reach mode, a11y rules | P1 (0.53.0) | P1-SH-06, P9-QA-01 | Settings → Appearance | New (04) |
| NEW-18 | Per-bot haptic signatures | P6 (0.58.0) | P6-SH-01 | Settings → Notifications | New (04) |
| NEW-19 | argus-agents-api v2: PATs, remote approvals, events | P8 (0.60.0) | P8-API-01, P8-API-02 | Settings → Sync & devices | New (05) |
| NEW-20 | On-device MCP relay | P8 (0.60.0) | P8-API-03 | Settings → Sync & devices (off by default) | New (05) |
| NEW-21 | CloudRunEngine | P8 (0.60.0) | P8-AG-01 | — | New (05) |
| NEW-22 | Self-hosted desk image | P7 (0.59.0) | P7-API-01 | Desk → Computers → Cloud | New (05) |
| NEW-23 | Cloud routines (phone off) | P10 (1.1.0) | P10-PR-01 | Desk → Routines (1.1) | New (deferred, D9) |
| NEW-24 | Soul change proposals as approval with diff | P3 (0.55.0) | P3-SH-01, P2-SF-02 | Bot profile → Soul | New (04 idea 12) |

### 7.6 Where the 254 source features land, by phase

| P0 | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 | P9 | P10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 22 | 68 | 21 | 19 | 19 | 17 | 12 | 34 | 15 | 22 | 5 |

---

## 8. Risks and mitigations

| # | Risk | Likelihood / impact | Mitigation | Owner | Watch in |
|---|---|---|---|---|---|
| K1 | `Agent.kt` (5.8k lines, already hit "Method too large") grows again | High / High | Only the `TeamHooks` seam touches it (≤ 20 lines, P0-AG-01); every capability in `team/`, `policy/`, `computer/`, `memory/`; QA checks the line count each phase | AG + IL | every PR |
| K2 | P1 is large (shell + team core in one minor) | High / Med | Land in ordered, revertable commits behind `argus_look` and a `team_mode` flag; owner build only when P1-QA-01 passes; fixes as 0.53.x patches | IL | P1 |
| K3 | APK size creep | Med / Med | Nothing big in the APK: Ubuntu rootfs, Needle/Qwen weights, voices, logos-as-vectors only; drop A-team's 37 MB wasm; Haze + Lucide are small; consider App Bundle/ABI splits post-1.0 | SH, CP | P0, P7 |
| K4 | RAM and battery on 4 GB phones (owner's A07/A12 class) with 14 bots | Med / High | Bots are idle data; only running jobs cost RAM (`AgentBudget.cap()`); SignalBus is model-free; silent back-off; graph and viewer draw only when visible; low-RAM smoke 7/7 gate every release | TM, PR, QA | P5–P7 |
| K5 | Security regression from Bot HQ patterns (it gates only Composio writes; bots share a computer) | Med / High | Port BH UX *on top of* `ConfirmGate`/`CommandPolicy` (R26); ≥ 60 policy cases; per-bot homes + path guards + browser profiles; SEND/SPEND/DELETE never auto from proactive or bot-initiated work; money/delete approvals on-device only | SF | P2, P5, P7 |
| K6 | Prompt injection through mail/web/MCP leading to sends/spends | Med / High | Taint → card with "Requested after reading…"; MCP and connector output marked untrusted; safety eval suite P2-QA-01 | SF, QA | P2, P4 |
| K7 | Memory leakage across domains / Desktop schema break | Low / High | Scopes enforced in the memory service and TeamBus redaction, not prompts; `memories` schema hash test; private-by-default for health/money/admin | AG | P3, P5 |
| K8 | Free-model quality for multi-agent coordination | Med / Med | Deterministic routing first (Needle, hints, thresholds), strong lane only for ARGUS decisions/huddles, Reviewer worker, routing + delegation evals | AG, QA | P5 |
| K9 | Bot-to-bot loops and cost blow-ups | Med / Med | Hop cap 3, thread budget 20 calls, huddle ≤ 30 calls, runaway guard 12/10 min, per-bot daily budget (TokenLedger) | TM | P5 |
| K10 | proot on older kernels (A12 4.19 seccomp), phantom-process killer, talloc path after updates | Med / Med | AM's proven proot + LinuxDoctor; port AT tmux ACL + talloc relink + `PROOT_NO_SECCOMP`; jobs under `JobKeeper` FGS; Ubuntu opt-in on arm64 only | CP | P7 |
| K11 | Background limits (Doze, Android 15 dataSync 6 h once targetSdk 35) | Med / Med | AlarmManager exact + WorkManager backstop; cloud lease has server-side idle where provider allows; targetSdk 35 after 1.0 (R30) | PR, CP | P6, P7 |
| K12 | Cloud cost (Orgo has no auto-stop) | Low / Med | Off by default, COMPUTE_PAID card per session, lease idle 5 min / cap 60 min / orphan stop, CloudChip with $/h | CP | P7 |
| K13 | Gateway work lives in another repo (`Blaze-agent`) | Med / High | D12: access or an owner there; file asks in P0; client works without relay (screenshot polling) so P7 still ships view-only if relay slips | API, IL | P0, P7, P8 |
| K14 | A-team users lose data in the move (different package + key) | Med / Med | Three paths: backup import (P1), export-for-ARGUS (P3), agents-api sync-down (P8); migration report; retire only after verification (§10) | MG | P1, P3, P8, P9 |
| K15 | CI churn: screenshot and source-scan tests break on new files | High / Low | Update the matching tests in the same commit (NoSquare, BackButton, ThemeEverywhere, IconOnly, MoviesLegal, skill hashes, store catalog); batch builds (15–25 min rounds) | QA | every phase |
| K16 | Licensing (Bot HQ artwork not MIT; a-team has no LICENSE; noVNC MPL; PRoot GPL) | Low / High | Orbs only; LICENSE added to a-team before copying code (P0-IL-02); MPL notice; PRoot stays a separate executable | IL | P0, P7 |
| K17 | Deployed wire names (`blaze-auto`, `X-Blaze-*`, `blaze-auth`, `*.blaze-app.workers.dev`) | Low / Med | Keep them; renaming needs a coordinated two-name redeploy outside this plan | API | — |
| K18 | Users overwhelmed by a big team / too many pings | Med / Med | Seed 3 bots; domains off until picked; one Proactivity dial; ≤ 3 proactive notifications/day; quiet hours | PR, SH | P5, P6 |
| K19 | Version numbers drift from plan (more than nine fix releases) | Low / Low | Phase names are the stable reference; IL re-numbers the table in this file and `overhaul.md` | IL | every release |

---

## 9. Test and QA plan

**Principles.** Tests are behaviour specs: every A-team Playwright PASS line, every A-team eval case and every Bot HQ e2e step is mapped to a ticket in `docs/overhaul/specs.md` (P0-QA-01) and becomes a JUnit/Robolectric/Compose test in that ticket. Owner rules: no `@Ignore`, no skipping, no threshold changes (700 ms stall gate, 7 low-RAM smoke tests, PhonePerfTest). Builds happen only when the owner says build; until then code is checked by hand and by running pure tests with standalone kotlinc where possible (the method the last rounds used).

**Layers.**

| Layer | What | Where | Gate |
|---|---|---|---|
| Pure JVM unit | All new rules as pure objects: Policy, Jobs queue, TeamBus, Rooms, Cron, Arbiter, Lease, LiveModels, MemScope, Projections, DueWords, Router thresholds, guards | `app/src/test/**` | 100 % of new pure files have tests; runs every CI round |
| Robolectric / Compose shots | Every new screen in light/dark × Plain/Orbit/Cobalt × font 100/200 %; TalkBack semantics; reduced motion | `*ShotsTest`, `BLAZE_SHOTS=1` | Owner reviews diffs each phase |
| Source-scan tests | NoSquare, BackButton, ThemeEverywhere, IconOnly, MoviesLegal, LegalAssets, skill hash / catalog sync, new `AgentKtSizeTest` (line budget) | `app/src/test/**` | Green every round |
| Device (androidTest) | Smoke (7 tests ≤ 700 ms), PhonePerfTest, Space checks replaced by Team/Chat checks, routine-fires-closed test, fake-mic voice test | `app/src/androidTest/**`, `tools/smoke/` | Green on CI emulator + owner phone |
| Evals | Ported A-team harness: terminal (69), code (29), refusals (63); new: safety (injection → send/spend), routing (labelled domain set), team (delegation, hop loops, room cap, huddle cost), tools (connector selection) | `evals/` | Each suite ≥ A-team v2.18 baseline; routing ≥ 90 % |
| Real arm64 phone | Owner's Galaxy A07/A12-class 4 GB (Android 13/14) + one Android 14/15 phone with GLES3 | `docs/overhaul/phone-checklists.md` | Signed checklist per phase |

**Real-phone checklist per phase (abridged; full lists live in `phone-checklists.md`).**
- **P1:** notch pull + swipe between modes; keyboard never moves chrome; create a bot, pick an orb, message it; delegate from ARGUS → job runs with the app swiped away and the result lands in the bot thread + notification; old settings links open; A-team backup imports; 200 % font.
- **P2:** approval from the thread, the Inbox and the notification; biometric on a spend; card expires after 25 min; Pause all stops a running job; Stop kills a long command.
- **P3:** edit SOUL.md and see behaviour change; delete a memory line in MEMORY.md and confirm it's gone from search; sync to a second phone (E2E) without private scopes.
- **P4:** connect Google via argus-connect OAuth loopback; a write action shows a card; a bot without the grant can't see the app.
- **P5:** Atlas asks Ledger (HandoffCard in both threads); a room with 4 bots answers with ≤ 3; 14 bots enabled on a 4 GB phone stays within the RAM cap.
- **P6:** routines fire with the app killed, across Doze and a reboot; quiet hours hold; edge glow over another app; widgets update; share a link to a bot.
- **P7:** install Ubuntu over Wi-Fi on the A07/A12; `$ cmd` lands in the bot's tmux window; review gate on a sample repo; Codespaces lease stops 5 min after idle with the app killed; take over and hand back (relay).
- **P8:** voice call with barge-in over Bluetooth; work handed off during a call lands as `[work_landed]`; two phones edit the same job (LWW); Claude/Cursor MCP sees the team; A-team v2.20 move end-to-end.
- **P9 (release gate):** all suites green, all checklists signed, accessibility audit (TalkBack walk of every mode, contrast on all grounds, 200 % font), coverage matrix rows all "done".

---

## 10. Migration plan for A-team users

A-team (`ai.argus.agents`, v2.18 vc 25 on main, v2.19 chat-drawer branch unmerged, own signing key) cannot update in place into `com.blaze.assistant`. Plan: **retire and migrate** (D10).

| Step | When | What moves | How | Ticket |
|---|---|---|---|---|
| M1 | P0 | — | Define `argus-team-export.v1.json` and the mapping from A-team's existing "Copy backup" JSON | P0-MG-01 |
| M2 | P1 (0.53.0) | Bots (name, role, email, portfolio, prompt, look → orb face/colour, reports-to), jobs (status, run, deps, due, priority, notes), nickname, theme | ARGUS Settings → Sync & devices → Backup → **Import A-team backup** (paste or file) | P1-MG-01 |
| M3 | P3 (0.55.0) | Manager profile (kinds, stated/inferred), bot private notes, team notes, routines, optional Manager chats | A-team **v2.19.x** adds Settings → Data → **Export for ARGUS**; ARGUS imports into scopes (`user:<kind>`, `bot:<id>`, `team`) and routines with owner bots | P3-MG-01 |
| M4 | P8 (0.60.0) | Everything above, automatically | A-team **v2.20 "Move to ARGUS"**: banner, export + push to argus-agents-api; ARGUS onboarding detects `ai.argus.agents` and offers import + sync-down with the same ARGUS account | P8-MG-01 |
| M5 | P9 (1.0.0) | — | Verify active users moved (migration report: counts of bots/jobs/memory/routines); A-team v2.21 opens ARGUS; archive a-team repo README | P9-MG-01 |

What maps where: A-team Home → Team mode; bot chats → threads (same intro + JobCard); Chat page → Chat mode (drawer kept); Jobs page → Desk → Jobs; Compose → Team roster → Graph; Terminal/Workspace → Desk → Computers (Ubuntu workspace per bot, opt-in download, ~800 MB); Cloud computer → Desk → Computers → Cloud (same providers, keys re-entered in the vault — keys never exported); Connectors → Settings → Apps & connectors (same argus-connect account, nothing to re-connect); My skills → Settings → Skills (same argus-connect source); Gemini key → re-enter under Models → Your keys (never exported); notifications/edge glow → same behaviour (P6). Things that do not move: provider keys and tokens (security), the Ubuntu rootfs (re-downloaded), WebView localStorage beyond the export.

Communication: in-app banner in A-team from v2.20, a short "What's different" sheet in ARGUS after import, CHANGELOG entry; Needle commands work the same ("add a job for Nova tomorrow").

---

## 11. How to pick this up

**If you are a new agent or engineer starting on this plan:**

1. **Read, in order:** `overhaul.md` (Status + Handoff log) → this file §1–§6 → the slice doc for your workstream (SH → 04; TM/AG/SF/PR → 05; CP → 02 §G + 05 §1.4/§5; CN → 02 §H + 05 §6; API → 02 §J + 05 §6.4/§9.3; MG → 02 §H9/§M + §10 here; LF → 03 §a). Source trees on disk: `/workspace/work/argus-mobile`, `/workspace/work/a-team`, `/workspace/work/bot-hq` (or GitLab 87806466 / blazeinchue/a-team / github nickvasilescu/bot-hq).
2. **Pick the lowest-numbered open ticket in your workstream whose deps are done** (§6). Check `STATUS.md` and the Handoff log first: someone may hold it.
3. **Before coding**, open the source files the ticket names in all three repos, and the matching spec lines in `docs/overhaul/specs.md` (after P0-QA-01).
4. **Rules that never bend:** commit straight to `main`; every commit title ends with `[skip ci]`; no Gradle build or CI until the owner says build; never change `applicationId`, signing, `keystore/` or the rotate-key CI step; never alter the shared `memories` schema (side tables only); keep deployed wire names (`blaze-*`, `X-Blaze-*`); never grow `Agent.kt` beyond the seam; no `@Ignore` or threshold changes; never copy Bot HQ / Grok artwork; never put a secret in code, logs, sync or commits.
5. **Pure first:** put rules in pure Kotlin files with JVM tests; Android glue beside them. Update the source-scan and screenshot tests your change affects in the same commit.
6. **When a ticket is done:** tick it in this file's §6 (append "✓ <commit>" to the title), update the coverage matrix row "How" if it changed, add a line to `STATUS.md` (unreleased round section) and one line to the `overhaul.md` Handoff log: `date · who · ticket(s) · what changed · what's next`.
7. **Releases:** only the integration lead bumps versions, and only when the owner says build. Patch per release, `.9` rolls to the next minor, each phase = next minor (§4).
8. **When blocked** (missing owner decision §2, gateway access, a failing spec): write it in the Handoff log with the ticket ID and move to the next unblocked ticket; never guess an owner decision — use the recommended default in §2 only where the plan says it is already assumed.

**Next 10 tickets to start (all of P0; 1–8 can run in parallel):**

| # | Ticket | Owner | Why first |
|---|---|---|---|
| 1 | P0-TM-01 team.db + models | TM | Everything in P1+ stores into it |
| 2 | P0-AG-01 BotEngine + LocalEngine seam | AG | Lets every bot run on the AM loop without touching `Agent.kt` |
| 3 | P0-SH-01 ui/argus tokens | SH | The A-team look under every new screen |
| 4 | P0-SH-02 Lucide + BotOrb | SH | The orb is the identity everywhere |
| 5 | P0-API-01 blaze_context + LiveModels | API | Fixes lost context today; live model list for per-bot policy |
| 6 | P0-QA-01 spec index + evals harness | QA | Turns A-team/Bot HQ tests into acceptance criteria |
| 7 | P0-MG-01 A-team export format | MG | Unblocks P1 import and the a-team export work |
| 8 | P0-IL-02 repo hygiene + notices (+ a-team LICENSE ask) | IL | Legal precondition for copying code |
| 9 | P0-IL-03 do-not-port register | IL | Stops dead code being ported |
| 10 | P0-IL-01 release 0.52.4 | IL | Ships the three pending rounds; first build when the owner says |

Then P1 starts with P1-SH-01 (shell) and P1-TM-01 (TeamStore) in parallel.

---

*Changelog:* 2026-11-11 v1 — reconciliation (30 items), 12 decisions for Finn, architecture, 11 phases with versions, 13 workstreams, ticket backlog, full coverage matrix, risks, QA, migration, pick-up guide (Specialist 6).
