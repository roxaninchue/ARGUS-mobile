# 05 — Agent ecosystem architecture

> Slice 5 of the ARGUS overhaul (see `overhaul.md`). Owner: Specialist 5 (agent systems architect). Status: **draft v1, 2026-11-11**.
> Scope: how the unified ARGUS app (Kotlin/Compose, `argus-mobile`) runs an **ecosystem of specialist agents** — one per part of a person's life and per kind of task — orchestrated by a Manager. Built from what already exists in Argus-mobile (Blaze agent, BgAgents, Orchestra, Mind, Proactive, Routines, Connectors, MCP), A-team (Manager bot, jobs queue, profile memory, skills, Ubuntu workspace, cloud computer, live model picking, Needle, `argus-agents-api`) and Bot HQ (Hermes engines, per-bot MCP, approvals, rooms, subagents, soul/memory files, per-bot screen, voice).
> Nothing here is implemented yet. File references are to the three source trees as pulled on 2026-11-11.

## Contents
0. Summary and design principles
1. The agent model (what a Bot is)
2. Manager, routing, delegation, bot-to-bot, rooms, subagents, jobs
3. Catalog of life-domain specialist agents
4. Memory architecture
5. Runtime placement and open replacements for paid dependencies
6. Tool / MCP surface and the `argus-agents-api` v2 sync contract
7. Safety: approvals, spending, sending, deletion, audit, takeover
8. Proactivity and routines
9. Data models (Kotlin + SQL)
10. Sequence diagrams
11. Source → target map, build order and risks (input for slice 6)

---

## 0. Summary and design principles

**One sentence:** every Bot is the same Kotlin object — *identity + soul + memory + skills + tools + engine + apps + computer + permissions* — run by one agent runtime on the phone, with the Manager as the single front door that routes work to persistent **domain agents** (Health, Money, Work, Home…) which in turn spin up short-lived **task workers** (Researcher, Shopper, Coder…) and talk to each other through a governed message bus.

What we keep from each source, and why:

| From | Keep | Because |
|---|---|---|
| Argus-mobile | The agent runtime (`agent/Agent.kt` Session loop, `CloudRuns` resumable gateway runs, `BgAgents`, `orchestra/*` Blueprint DAG + `ToolScheduler`/`KeyedLocks`), tiered memory (Mind/`blaze.db`, chat tier, cache tier), `ConfirmGate` taint rule, `Modes`, `Specialists`/`SpecialistMatch`, phone control, `Routines`/`Triggers`/`Autopilot`, `Mcp.kt`, `Connectors.kt`, `SecretVault`/`SecretUse` | It is the most mature, tested runtime (~2.9k unit tests), native, and already never-blocking. It becomes the **engine of every bot**. |
| A-team | Manager bot + its tools and prompt, the jobs queue (deps, one job per bot, runaway guard), profile memory (stated vs inferred, kinds, secrets refused), hybrid team/private memory visibility, proactivity rules (quiet hours, 3/day cap, "stop suggesting this", risky regex), proot Ubuntu per bot (tmux window per bot), cloud computer (Orgo / Codespaces, off by default), live model list (`/v1/models/live`), Needle on-device command parsing, `argus-agents-api` (REST + MCP), argus-connect connectors and skills | It has the right **product shape**: a team of bots as chats with a lead. |
| Bot HQ | Bot record (label, mode personal/teammate, effort, voice), transcript rows (`seq`/`updatedSeq`, card kinds), approvals *inside the tool call* (Allow / Always / Don't), `send_to_bot` with hop depth, rooms (≤3 responders, `stay_silent`), subagents (hidden child bots, ≤4), soul/memory as editable files (`MEMORY.md`, `USER.md`), per-bot screen with watch / take-over, Library, check-ins with silent-streak back-off, Hermes as a pluggable engine | It has the best **multi-agent UX and safety mechanics**. |

Design principles (they resolve most later decisions):

1. **One runtime, many identities.** A bot is data, not code. Domain agents differ by soul, tools, memory scope, routines and permissions — never by a separate loop. (Avoids three agent loops: Blaze Session, A-team `CHAT.reply`, Hermes.)
2. **Manager is the front door, not a bottleneck.** The user can always talk to any bot directly; Manager handles the unaddressed message, delegation, conflicts and the daily picture.
3. **Persistent domain agents, ephemeral task workers.** Domain agents own a part of life (memory, routines, relationships with apps). Task workers (today's `Specialists.builtIn`) are templates spawned for one job and then gone.
4. **Phone first, cloud when needed, user-confirmed.** Default placement is on-device (phone control, proot Ubuntu, on-device models where good enough). A cloud computer starts only for work the phone can't do, and only after a yes (A-team rule kept).
5. **Zero-budget by default.** Free routes first (Blaze Gateway free lineup, on-device brain, Workers AI). Paid services (Composio, xAI, Orgo) become optional adapters, never requirements.
6. **Approvals live in the tool layer**, not the prompt (Bot HQ + `ConfirmGate`): every engine, every bot, every channel goes through the same policy check.
7. **Memory is the user's.** Readable, editable, forgettable as plain markdown files per bot; sensitive domains private by default; secrets never stored.
8. **Quiet by default.** Proactivity is arbitrated centrally with hard caps; most checks end in silence.

---

## 1. The agent model

### 1.1 Anatomy of a Bot

```
Bot
├── identity     id, name, label ("Money"), avatar (shape/color/face), voice, color, mode (personal|teammate), kind (agent|room|worker)
├── soul         SOUL.md  — who it is, voice, standing rules, boundaries (user-editable, versioned)
├── memory       MEMORY.md (its private notes) + scoped view of USER profile + TEAM notes  (§4)
├── skills       skill refs (bundled packs, Argus originals, user skills from argus-connect, playbooks)
├── tools        tool groups + explicit grants (≤64 tools in the prompt; ToolRouter loads groups on demand)
├── engine       which loop runs it + model policy (lane, tier, pinned model, effort)
├── apps         connector grants (argus-connect OAuth apps, MCP servers) with per-tool policy
├── computer     none | phone | ubuntu(proot, own tmux window + $HOME) | cloud(orgo|codespaces|self-hosted) | hermes-host
├── permissions  action classes allowed / ask / never, spend cap, send scope, data scopes, autonomy level
├── routines     schedules + event triggers it owns
└── relations    reportsTo (lead), members (rooms), parent (workers/subagents)
```

**Kinds:**
- `MANAGER` — exactly one, fixed id `manager`, cannot be deleted (A-team `manager.js ensure/canDelete`), soul editable only in its *user-rules* section (A-team v2.16c locked the core prompt; we keep a locked core + editable addendum).
- `DOMAIN` — persistent specialist for a life area (§3). Seeded from templates, fully editable, deletable.
- `CUSTOM` — any bot the user (or Manager via `agent_create`) makes.
- `WORKER` — ephemeral task specialist spawned for one job (today's `Specialists.Spec` + `BgAgents` entry). Hidden, no long-term memory, inherits parent's permissions minus anything it doesn't need.
- `SUBAGENT` — a hidden child of any bot for a parallel sub-goal (Bot HQ `subagents.ts`); like a worker but created by the bot itself, reports back with `send_to_bot`.
- `ROOM` — group chat; never runs its own loop (Bot HQ `rooms.ts`).
- `REMOTE` — a bot whose loop lives elsewhere (a Hermes gateway, a cloud run). Same record; `engine.kind = HERMES`.

### 1.2 Soul file (SOUL.md)

Bot HQ keeps persona in Hermes' home, A-team in `agent.prompt`, Argus in `Personas`/`OnePersona`. Unified: one markdown file per bot with fixed headings so it can be edited by hand *and* parsed:

```markdown
---
id: money
name: Ledger
label: Money
version: 7
locked_sections: [Boundaries]      # Manager & domain templates lock safety text; user can still add rules below
---
# Who you are
You look after Finn's money: spending, bills, subscriptions, savings goals...
# How you sound
Plain numbers first. Currency always. One recommendation, with the reason.
# What you own
Budgets, bills calendar, subscription audit, receipts in the Library.
# Standing rules            ← user-editable; "always/never" lines land here (A-team RULE_START detector)
- Never suggest crypto.
# Boundaries                ← locked template text
- You never move money, pay, or subscribe without an explicit yes on an approval card.
- You never share balances with other bots unless Finn allowed it (data scope money:share).
```

- Stored at `filesDir/bots/<id>/SOUL.md`, mirrored into the bot's Ubuntu home `/root/.argus/bots/<id>/SOUL.md` (like A-team `mirror()` of the profile), and — for Hermes bots — written into the Hermes home as the persona file.
- Every save bumps `version` and keeps the last 20 versions (`bot_soul_versions`) for "Undo edit".
- The system prompt is assembled as: locked Argus core rules → SOUL.md → domain playbook → skills index → memory blocks (§4) → context (time, team roster, connected apps, routines). This mirrors Bot HQ `instructionsFor()` and its **cache-stable rule**: nothing clock-dependent above the memory block, so prompt caching on the gateway stays effective; the `Now:` line (A-team `nowLine`) goes last.

### 1.3 Engines and models

| `EngineKind` | Where the loop runs | Use |
|---|---|---|
| `LOCAL` | Phone, `Agent.Session` loop; model calls to the Blaze Gateway / on-device brain per `BrainOrder` | Default for every bot. Full phone tools. |
| `CLOUD_RUN` | Blaze Gateway `/v1/runs` (CloudRuns): loop on the gateway, `needs_device` hands phone tools back | Long jobs that must survive the app closing; still the same bot. |
| `HERMES` | A Hermes gateway (`POST /v1/responses`), user-provided host; ARGUS tools exposed to it over MCP (`/mcp/<botId>` equivalent served by the phone relay or the gateway) | Power users who already run Hermes (Bot HQ's primary engine rule: never restart / reconfigure it). |
| `NEEDLE` | On-device Cactus Needle 3 (A-team `needle-worker.js`) | Not a bot engine — a **command fast path** for Manager (§2.3). |

Model policy per bot (`ModelPolicy`): `lane` (chat, quick, code, vision, long, agent — gateway `blaze_task` tags), `tier` (Spark / Flare / Inferno, `ModelChoice.forRole`), optional `pinned` model (A-team `applyModel`: Gemini with user key, or a preference the gateway honours), `effort` (Bot HQ low…xhigh → thinking budget), and `privacy` (`ZERO_RETENTION` forces BrainOrder's rule that Google-data requests only go to zero-retention models). The model id is **never typed in the app**: picks come from `GET /v1/models/live` (A-team v2.13), kept for the whole turn, gateway falls back inside the lane and reports it in headers.

### 1.4 Computers

| `ComputerKind` | Backing | Default for |
|---|---|---|
| `NONE` | — | Writer, Money (data-only) |
| `PHONE` | Accessibility phone control (`phone/flash/*`, `TapLoop`), on-phone WebView browser | Operator worker, Home (device settings), Food (delivery apps) |
| `UBUNTU` | Shared proot Ubuntu 24.04 (Argus `terminal/` + A-team `Workspace.java`); **per bot**: own tmux window, own `$HOME=/home/<botId>`, own Library dir `/root/argus/files/<botId>` | Dev/Ops, Creative, Learning, Admin (PDFs) |
| `CLOUD` | Orgo desktop, GitHub Codespace, or **self-hosted desk** (§5.3): one X screen per bot, noVNC watch / take-over (Bot HQ `vnc.ts`) | Only when confirmed: real Chrome, login/JS sites, GUI, x86 |
| `HERMES_HOST` | The Hermes machine's own screen / Chrome | Hermes bots |

Locks: real resources are held through `KeyedLocks` across all bots — `screen` (phone), `browser`, `file:<path>`, `term:<botId>`, `desk:<screenId>` — so two bots never drive one screen (Bot HQ's `BH_HOME` lesson; Argus `ToolScheduler` claims).

### 1.5 Permissions

```
Permissions(
  autonomy = ASSIST | ACT | AUTOPILOT,        // Modes: Plan≈ASSIST(read-only), Build+Auto≈ACT, Autopilot
  actions  = map ActionClass → ALLOW | ASK | NEVER,
  spendCapMonthly = Money(0, "ZMW"),           // 0 = every spend asks; never auto above cap
  sendScope = NONE | DRAFT_ONLY | KNOWN_CONTACTS | ANY,
  dataScopes = {"user:core", "health", "money", ...},   // which memory scopes it may read (§4.4)
  computerUse = { phone, ubuntu, cloud(askEachTime=true) },
  maxStepsPerTurn = 40, maxJobMinutes = 30, maxHops = 3
)
```
Action classes and defaults are in §7.

---

## 2. Manager, routing, delegation, bot-to-bot, rooms, subagents, jobs

### 2.1 Roles

- **Manager** (A-team Manager + Argus "Blaze" chat agent, merged): the default chat (the Notch "Chat" page in A-team v2.16 is bound to it). Fast lane answers itself; anything bigger is routed. Owns the roster, the job board, the user profile (writes), the proactivity arbiter and the daily brief. Tools: A-team `MGR_TOOLS` (`agents_list`, `agent_create/update/delete`, `agent_delegate`, `agent_status`, `memory_read/write/forget`, `routine_create/list/cancel`, `retry_job`) + Argus `spawn_agent`, `delegate`, `orchestrate`, `message_agent`, `cancel_agent`, `list_agents` + Bot HQ `create_group`, `ask_user`.
- **Domain agents**: own a life area; receive delegated jobs, run routines, propose suggestions, may delegate sideways (`send_to_bot`) and spawn workers.
- **Workers / subagents**: do one thing, return a result, die.

### 2.2 Message routing (who answers)

A user message arrives in a chat. Resolution order:

1. **Direct chat** with a bot → that bot answers (it may still hand off).
2. **Room** → room dispatcher (§2.6).
3. **Manager chat**:
   1. **Command fast path** (≤150 ms, offline): exact parse → Needle on-device tool call (A-team `needleCalls`: `add_agent`, `add_task`, `complete_task`, `reschedule`…) → if a confident call is produced, execute and confirm in one line. Extend Needle's tool set with `route_to(bot)`, `set_routine`, `remember` (fine-tune data lives in `a-team/needle-ft`).
   2. **@mention / explicit name** ("ask Money…", "@Pulse") → delegate.
   3. **Domain match**: `SpecialistMatch` extended to domains — keyword + embedding score of the message against each domain's `routingHints` (cheap, on-device). Score ≥ 0.75 and one clear winner → delegate silently; 0.5–0.75 → Manager delegates but says who ("Handing this to Travel."); ties → Manager answers or asks one question.
   4. **Fast lane**: quick question → Manager answers itself (Argus `FastLane`/`ChatLane`).
   5. **Multi-domain goal** ("plan my Cape Town trip within budget and keep my workouts going") → `orchestrate` with a `generic` Blueprint whose nodes are owned by domain agents (Travel, Money, Health) and a stitch node owned by Manager.
4. Manager **never blocks**: it replies in one short line and the work shows as a pill (Argus `AgentPill`) / job card (A-team).

### 2.3 Delegation = a Job

Delegation always creates a **Job** (A-team model, extended) so it is visible, resumable and syncable:

- Brief = A-team `QUEUE.brief()` shape ("You are assigned job #… Do it now… When finished call `done_job`… If you truly need something, ask and stop") + **relevant memory lines** (A-team `pRelevant`, filtered by the target's data scopes) + parent context summary.
- Queue rules (A-team `jobs.js`): a job waits on its `deps`; **one running job per bot** (more work → that bot spawns workers); priority high first; runaway guard 12 automatic starts / 10 min; proactive jobs never act unconfirmed (`MANAGER.jobAllowed`).
- Execution = a `BgAgents` entry running the target bot's identity (its soul, tools, computer), with Argus' resume-after-process-death (`resume = true`, `TaskWorker` keeps the process).
- Completion: result posted in the bot's own chat **and** summarised back to the requester (Manager or the delegating bot) + notification (A-team `NOTIF`/`AgentNotify` with avatar and edge glow).
- Failure: retry once with the error in context (Argus `AgentBudget.escalation`), then `failed` with `retryAt`; Manager's `retry_job`.

### 2.4 Bot-to-bot messaging

`send_to_bot(bot, message, expects_reply=true, thread?)` (Bot HQ) on a governed **TeamBus**:

- Delivered into the target's queue as a `BotMessage` row (`from.kind = bot`), the reply returns the same way.
- **Hop depth** carried on every message; at depth 3 the tool refuses ("answer the user directly") — Bot HQ `ctx.depth >= 3`.
- **Thread id** groups an exchange; a thread has a token/step budget (default 20 model calls) and ends when either side calls `stay_silent` or returns a result.
- **Data scope check** on the payload: a bot can't forward memory lines from a scope the receiver can't read (e.g. Health → Social only gets "Finn has a 7am run Tuesday" if `health:share-schedule` is allowed). Implemented as a redaction pass using the memory line's `scope` tags (§4).
- Visible: the user sees bot-to-bot exchanges as a collapsed "Money ↔ Travel · 3 messages" line in both chats, expandable (Bot HQ `event: bot-message`). Nothing hidden.
- **No side doors**: bots can't call each other's tools; they ask the owner bot. (Keeps permissions meaningful.)

### 2.5 Subagents and workers

- **Worker** = `spawn_agent(task, specialist, inputs)` from Argus, templates from `Specialists.builtIn` + user's custom specialists (`create_specialist`). Runs with the parent's permissions ∩ the template's tool groups.
- **Subagent** = Bot HQ hidden child (`parent_bot_id`, `hidden=1`), max 4 per parent, own context and (if the parent has a cloud desk) own screen lease; reports back via `send_to_bot` to the parent and never messages the user.
- Concurrency is global, from Argus `AgentBudget.cap()` (4 on 6 GB+, 3 on 4 GB+, 2 below, 1 low-RAM; network-only cap 3; phone-screen specialists never two at once). Cloud-run and Hermes bots don't count against the phone RAM cap but count against a per-user "concurrent cloud runs" cap (gateway side, default 3).
- **Blueprint orchestration** (`orchestra/*`) is the engine for multi-step, multi-owner work; node `role` gains `ownerBotId`, so a node can be executed by a domain agent rather than an anonymous worker.

### 2.6 Rooms (group chats)

Bot HQ semantics + Manager moderation:

- A room = `kind=ROOM` bot with `members`. A user message goes to @mentioned members, else to members whose `routingHints` match, else all — capped at **3 responders** (Bot HQ `MAX_RESPONDERS`).
- Each responder gets the last 12 room messages and replies with `post_to_group` or `stay_silent` (no repeats).
- **Huddle mode** (new): user asks the room to "work it out" → Manager becomes facilitator, runs up to N rounds (default 3) where members post proposals, then Manager posts the decision with a ```compare block and asks the user to pick. Every round costs budget; huddles are capped at 30 model calls.
- Seeded rooms (optional on setup): **"Life Admin"** (Admin, Money, Home), **"Founder HQ"** (Dev/Ops, Work, Researcher), **"Weekend"** (Social, Food, Travel).

### 2.7 Jobs, tasks and the board

- **Job** (A-team) = unit of delegated work with owner bot, due, priority, deps, run state (`queued|running|waiting|done|failed`), result, approvals.
- **Task** (Bot HQ `create_task/update_task`) = a bot's self-tracked progress item inside a job → merged as `JobStep`s, shown as the pill's step list.
- The **Board** (A-team Compose graph/job list) shows all jobs by bucket (overdue, today, tomorrow, upcoming, todo, done — `bucket()` in `api/src/index.js`), filterable by bot. Synced through `argus-agents-api` (§6.4).

