# 01 · Bot HQ feature inventory + port notes

> Slice 1 of the ARGUS overhaul (see `overhaul.md`). Owner: Specialist 1. Status: **complete, first full pass** (2026-11-11).
> Source read in full: `github.com/nickvasilescu/bot-hq` @HEAD (MIT; 193 files, ~15.9k lines excluding assets): README, docs/, server/src/**, server/box/**, web/src/**, scripts/, tests/.
> Cross-checked against `argus-mobile@main` (Kotlin/Compose, `app/src/main/java/com/blaze/assistant/…`, plus `gateway/`) and `a-team@main` (`app/www/*.js`, `app/src/ai/argus/agents/*.java`, `api/`).

## How to read this

Every entry: **what it does · where (source) · deps · PORT** (existing equivalent in Argus-mobile = **AM**, in A-team = **AT**, then where it should land).
Landing codes: **N** native Kotlin/Compose on the phone · **P** on-device proot Linux (AM Alpine / AT Ubuntu 24.04) · **G** via the ARGUS gateway / ARGUS Connect (Cloudflare workers) · **C** cloud or self-hosted box (Orgo / Codespaces / user's own server / Blaze Desktop link).
💲 = needs a paid third-party service today → an open/self-hosted alternative is given.
Priority: **P0** core of the merge · **P1** should ship in the overhaul · **P2** nice to have / later.

## 0. What Bot HQ actually is (one paragraph)

A Node 22 server (Hono + better-sqlite3 + SSE + a WebSocket VNC relay) and a React/zustand web app. It puts a Grok-Bot-style texting UI in front of **up to 4 bots**, each bot being a **Hermes Agent** gateway (the first bot adopts the Hermes agent already running on the box; every other bot gets its own Hermes home + `hermes gateway run` on port 8650+). All bots share **one Orgo Linux VM**; each bot leases **one Orgo screen** (X display) dressed as "its own computer" (wallpaper, dock, Chrome with its own profile + CDP port, home-screen new-tab page). The server exposes its own tools to every Hermes over **MCP** (`/mcp/<botId>`): Composio connected apps (behind **approval cards**), `x_search` (xAI), Library files, tasks, cron + event **routines**, proactive **check-ins**, **bot-to-bot** messages and **group chats**, `ask_user` question cards. **Voice** is a second, lightweight xAI realtime agent in the browser that speaks as the bot and hands real work to the bot's text loop.

### Reality check vs. the overhaul brief (important)
The brief says Bot HQ includes "Muse-style social connections + editable soul/memory files" and "Dots-style per-agent computer". In the code:
- **Social connections (Muse): NOT present.** No contacts graph, no people, no bot-to-human social layer anywhere in server or web. Must be designed from scratch (slice 5) — see §14.
- **Soul file: present but NOT editable in the UI.** `SOUL.md` is generated from name/label/description (`engine.ts soul()`, rewritten by `syncPersona()` on rename). The user edits name/role/description/instructions, not the file.
- **Memory files: viewable + delete-one only.** `memories/MEMORY.md` and `memories/USER.md` in the bot's Hermes home are listed and individual entries can be forgotten (`engine-memory.ts`); no add/edit in UI. Writes are Hermes' own `memory` tool.
- **Per-agent computer (Dots): present** (Orgo screens + live noVNC viewer + take control/hand back).
- **Dead / stub code:** `agent/subagents.ts` (createSubagent etc.) is never wired to a tool or route; the `draft` card type renders but nothing emits it; `/memories/consolidate` and `/compact` are stubs; the idle-suspend watcher is disabled; tables `llm_items`, `pending_turns`, `memory_jobs`, `compactions` are unused; "Bot templates" button is "coming soon"; bot `mode` (personal/teammate) is stored but never used; `citations` on messages are never populated.

---

## 1. Bots, roster and identity

**BH-01 · Bot roster (multi-bot team, max 4)** — P0
- What: create/list/rename/delete bots; each has name, label (role), description, instructions, avatar shape+colour, mode, effort, voice settings, model/provider. Team capped at `MAX_BOTS` (=4, Orgo screen limit). Deleting a bot stops its gateway, deletes its Hermes home, releases its screen, deletes its Chrome profile + Library folder, unschedules routines/event triggers.
- Where: `server/src/routes.ts` (createBot, `/bots` CRUD), `store.ts` (toBot, listBots), `types.ts` (Bot), `db.ts` (bots table), `web/src/components/Sidebar.tsx`, `modals/CreateBotModal.tsx`.
- Deps: Hermes (engine per bot), Orgo (screen per bot).
- PORT: **AT** already has bots-as-chats + create/edit/delete (`app/www/app.js`, `manager.js` agent_create/update/delete) — this is the base. **AM** has Specialists (`agent/Specialists.kt`, user/blaze-made, own prompt + tool groups + skill packs) and Personas. Land **N**: one `Bot` entity in Room/SQLite (merge AT bot record + AM Specialist spec). Drop the 4-bot cap (it only exists because of Orgo screens); cap concurrency instead (AM `BgAgents` slots by RAM).

**BH-02 · Primary bot ("Doug · Chief of Staff")** — P0
- What: auto-created on first sign-in (`PRIMARY_BOT_NAME/LABEL`), pinned at top of sidebar, "main point of contact", only bot that does check-ins; "Make primary" can move the role.
- Where: `routes.ts findOrCreateUser`, `/bots/:id/primary`; `Sidebar.tsx` pinned tile; `proactive.ts` (primary only); `prompt.ts` ("[main point of contact]").
- PORT: = **AT Manager** (`manager.js`, fixed id `manager`, undeletable, routes work, keeps profile, runs proactive engine). Land **N**: Manager is the primary bot; keep "Make primary" as an option only if slice 5 wants a swappable chief.

**BH-03 · Avatar system (25 shapes × 11 colours)** — P1
- What: generated SVG character avatars (blob, pebble, bean, egg, squircle, tablet, capsule, cylinder, hex, gem, crystal, wedge, shield, dome, arch, cloud, teardrop, leaf, square, sparkle, clover, flower, heart, house, star) in black/brown/red/orange/yellow/green/cyan/blue/violet/magenta/gray; hash-picked default; per-bot CSS colour vars tint the details panel/viewer.
- Where: `web/src/avatar/BotAvatar.tsx`, `avatar/shapes.gen.ts` (generated by `web/scripts/gen-shapes.mjs` from `web/artwork/shapes/*.svg`), `lib/colors.ts`, `routes.ts hashPick`. ⚠️ artwork derives from Grok Bot design — **not MIT** (THIRD_PARTY_NOTICES.md); redraw originals.
- PORT: **AT** has its own avatars (rendered to PNG for notifications, `notify.js`). Land **N**: Compose `Canvas`/vector drawables, A-team look; reuse the *idea* (shape+colour identity, tint per bot) but ship original shapes.

**BH-04 · Bot profile fields: label/description/instructions/mode/effort** — P0
- What: role label shown under name; description feeds SOUL.md and prompt; free-text standing instructions appended to the system prompt; effort low/medium/high/xhigh → `reasoning_effort`; mode personal/teammate (unused).
- Where: `BotSettingsModal.tsx GeneralTab`, `routes.ts PATCH /bots/:id`, `agent/prompt.ts`, `loop.ts model_options`.
- PORT: **AT** Edit bot (prompt, model). **AM** Specialist prompt + `ThinkBudget.kt`. Land **N**. Map effort → AM ThinkBudget / gateway task hint.

**BH-05 · SOUL.md persona file** — P0 (brief calls it out)
- What: per-bot persona file in its Hermes home, generated from name/role/description; rewritten on rename; primary bot keeps its own.
- Where: `server/src/engine.ts soul()`, `syncPersona()`, `writeHome()`.
- Deps: Hermes (reads SOUL.md).
- PORT: **AM** has `OnePersona.kt`, `SharedPersona.kt` (persona.json, byte-identical first system message), `Personas.kt`, `HaloStudio.kt`. **AT** has editable bot prompt + Manager "Reset to default". Land **N**: make SOUL a real **editable markdown file per bot** (Compose editor with preview + "regenerate from profile" + reset), stored in the bot's folder (see BH-40) and mirrored into the proot workspace at `~/bots/<id>/SOUL.md` so shell tools can read it. Keep AM's cache-stable "persona first, app rules second" ordering.

**BH-06 · Pin / unread / last preview / status dots** — P0
- What: per-bot unread counter (bumped on assistant messages, cards, room posts), last message preview, last activity time; sidebar status dot: working / needs attention (awaiting approval) / unread; opening a chat marks read.
- Where: `store.ts toBot`, `routes.ts /bots/:id/read`, `Sidebar.tsx`, `web/src/store.ts`.
- PORT: **AT** pins + home list. **AM** `notify/ChatThreads.kt`. Land **N**.

**BH-07 · Search palette (⌘K)** — P1
- What: search bots by name/label/description, keyboard nav; placeholder promises "chats and settings" but only bots are searched.
- Where: `web/src/components/modals/SearchModal.tsx`.
- PORT: **AT** search (ui.test); **AM** FTS chat search (`LongChat`, `ChatIndex.kt`). Land **N**: one global search over bots + messages (FTS5) + settings.

**BH-08 · Keyboard shortcuts** — P2 — ⌘K search, ⌘B sidebar, ⇧⌘, details, ⌘, settings (`web/src/App.tsx`). PORT: phone = gestures (AT notch pull-down, AM SwipeNav); keep shortcuts for hardware keyboards/ChromeOS **N**.

**BH-09 · Theme (system/light/dark)** — P1 — `configStore.ts`, `AppSettings.tsx GeneralSection`. PORT: **AT** dark mode, **AM** `Theme.kt`/glass. Land **N** (A-team design tokens).

---

## 2. Chat, transcript and live activity

**BH-10 · Row-based transcript model** — P0
- What: conversation = rows `{seq, updatedSeq, kind, body}`; kinds message / tool-call / card / event / notice; streaming reply = same row rewritten with higher `updatedSeq`; clients upsert by seq; `generation` = conversation number.
- Where: `types.ts`, `store.ts appendRow/updateRow/listRows`, `db.ts rows`.
- PORT: **AM** ChatStore/ChatJson + cards; **AT** chat.js bubbles+tool cards. Land **N**: adopt this row model in the unified chat store — it cleanly carries cards, events and bot-to-bot messages.

**BH-11 · Streaming replies from the engine (SSE /v1/responses)** — P0
- What: per-bot serial queue; posts trigger to Hermes `/v1/responses` (stream, `conversation=hq-<bot>-g<gen>`, `store:true`, `instructions` = app prompt); maps events: output_text.delta → message, reasoning deltas → caption, `hermes.status` → caption, function_call → tool-call row, function_call_output → status, commentary messages split out; errors → notice.
- Where: `server/src/agent/loop.ts` (runTurn, sseEvents, captionFrom, readOutcome).
- Deps: Hermes gateway.
- PORT: **AM** Agent.kt + CloudRuns.kt (gateway `/v1/runs`, resumable SSE with Last-Event-ID) already does better (resume after drop). Land **N + G**: keep AM's loop; optional *Hermes adapter* (BH-50).

**BH-12 · Trigger types into a turn** — P0 — user / routine ("[Routine X is running now]") / bot ("[Message from X, another Bot]", hop depth) / system ("[System notice]"). `loop.ts triggerText`. PORT **N**: same envelope set in the unified agent; AM already has platform messages from BgAgents/Dispatch.

**BH-13 · Live state + rotating activity phrases** — P0
- What: per-bot LiveState (isRunning, activity kind+detail, awaiting reason, startedAt, caption from reasoning, step count) pushed over SSE; 13 activity kinds mapped from tool names (`activityFor`) to human labels ("Searching X", "Using its computer"); phrase table rotates ("Mulling it over").
- Where: `store.ts setLive`, `agent/tools.ts activityFor`, `web/src/components/ActivityLine.tsx`, `web/src/lib/phrases.ts`.
- PORT: **AM** StepPill.kt (clean step line), ThinkingLabel.kt, TaskPill; **AT** live tool cards. Land **N**: merge — keep AM's "never show raw tool/model names" sanitiser.

**BH-14 · "Worked for 2m 10s · 7 steps" collapsible step list** — P0
- What: consecutive tool-call rows fold into one chip with duration and step count; expand shows each step with status, args detail, screenshot thumbnail; flags for waiting/failed step.
- Where: `web/src/components/Transcript.tsx` (items builder, Steps component).
- PORT: **AM** `CalmSteps.kt`, `WorkTime.kt`, AgentCard; **AT** tool cards. Land **N**.

**BH-15 · Stop / interrupt** — P0 — aborts the running turn, expires pending approvals; "Stopped." notice. `loop.ts interrupt`, `routes.ts /interrupt`, Composer stop button. PORT: **AM** StopResume.kt (tidy + "Continue where you stopped") is richer → **N**.

**BH-16 · New conversation (generation bump)** — P0 — clears visible thread, starts new Hermes session, keeps memory. `store.ts clearConversation`, `/bots/:id/fresh`, Composer menu + settings. PORT: **AT** /clear; **AM** chats list. **N**.

**BH-17 · Message provenance badges** — P1 — `from: {kind: bot|routine|voice}` shows "from Juno", "routine …", "said on a call". `types.ts MessageBody.from`, `Transcript.tsx sender-label`. PORT **N**.

**BH-18 · Markdown rendering + citation chips** — P1 — `Markdown.tsx`; citation chips by host (`Transcript.tsx`) — field exists but server never fills it. PORT: **AM** Md.kt, ReplySources.kt (real sources) → **N**.

**BH-19 · Notices (info/warn/error)** — P1 — inline system lines (errors, "group has no Bots"). PORT **N**.

**BH-20 · Composer** — P0 — multiline input "Message <bot>", Enter to send; + menu (New conversation, Connect apps); mic = hold-to-dictate / tap toggle, Esc cancels; empty composer → button becomes "Start voice chat"; Stop while running; End call while in call. **No attachments** (paperclip icon asset unused). `web/src/components/Composer.tsx`, `voice/dictation.ts`.
- PORT: **AM** composer is far richer (attachments, camera, slash commands, quick replies); **AT** home box router + attachments. Land **N**: keep AM composer, add the "empty → call" affordance.

**BH-21 · Date dividers, jump-to-bottom, empty-chat hero** — P2 — `Transcript.tsx`. PORT **N**.

**BH-22 · Dev fixtures mode (`?fixtures`)** — P2 — sample transcript with every card type for screenshots. `web/src/dev/fixtures.ts`. PORT: Compose `@Preview` + AM screenshot params **N**.

---

## 3. Cards (human-in-the-loop)

**BH-23 · Approval card (Allow / Always allow / Don't allow)** — P0
- What: when a bot calls a Composio action that writes, the MCP call blocks; a card shows app logo, "Allow <bot> to <action>?", flattened args; Allow runs once; Always stores per-tool policy; Deny returns "declined, do not retry"; a new user message or 25 min timeout expires it ("skipped"); restart expires all. Live state shows "waiting for you"; unread bumped.
- Where: `agent/loop.ts requestApproval/resolveApproval/expire*`, `mcp.ts callTool`, `agent/tools.ts policyFor`, `routes.ts /approvals/:id`, `web/src/components/cards/Cards.tsx`, db `approvals`, `policies`.
- Deps: Composio (slugs), works for any engine because it's inside MCP.
- PORT: **AM** ConfirmCard + ConfirmGate.kt (outbound/destructive always ask, **prompt-injection taint**), CommandPolicy.kt (Ask / allow-for-chat / always), Mcp.kt per-tool Ask/Always/Off; **AT** approvals inline, Manager "never without your yes". Land **N**: AM's gate is stronger — reuse it; adopt Bot HQ's "approval happens inside the tool call, so it works for every engine/session" principle and the 25-min expiry.

**BH-24 · Read-only auto-allow heuristic** — P1 — slugs matching `_(GET|LIST|SEARCH|FETCH|FIND|READ|RETRIEVE|QUERY|LOOKUP|CHECK|DESCRIBE|VIEW|COUNT)_` auto-allowed; Composio workbench/bash code that calls app actions is treated as a write. `composio.ts isReadOnlySlug`, `tools.ts policyFor`. PORT: **AM** CommandPolicy SAFE level + MCP read-only hint → **N**.

**BH-25 · Permissions page (standing "always allow" list, "Ask again")** — P0 — `AppSettings.tsx PermissionsSection`, `/policies`. PORT: **AM** standing OKs (Autonomy.preApproved) → **N** one Permissions page across connectors, MCP, shell, phone actions.

**BH-26 · Question card (`ask_user`, multiple choice ≤6)** — P0 — ends the turn; tap an option or just type; answer is sent as "[Answer to "…"]". `tools.ts ask_user`, `routes.ts /cards/:seq/answer`, `Cards.tsx`. PORT: **AM** question cards/QuickReplies; **AT** "no bulleted interrogations" guard. **N**.

**BH-27 · Connect card (`request_app_connection`)** — P0 — bot asks to connect an app (logo, reason); Connect opens OAuth popup; on callback the card flips to connected and the bot gets "[System] user connected X, continue"; Skip tells the bot to go on without it. `tools.ts`, `routes.ts /apps/:slug/connect, /apps/callback, /cards/:seq/dismiss`, `Cards.tsx`, `App.tsx` postMessage listener.
- PORT: **AM** CredAsk.kt/SignInGate; **AT** connectors loopback. Land **N + G** (ARGUS Connect OAuth start → loopback/deep link → resume turn).

**BH-28 · Draft card (email/slack/message preview, Send/Discard)** — P1 — type + UI exist, **no server emitter**. `types.ts`, `Cards.tsx DraftCard`. PORT: **AM** confirm cards for outbound texts. Land **N**: implement for real (every outbound message = draft card), matches Hark-style approval prefs.

---

## 4. Engines and models (Hermes)

**BH-29 · Hermes engine per bot (primary vs managed)** — P0
- What: primary = existing gateway at `HERMES_URL` (never restarted/configured by the app); managed = `ENGINES_DIR/<botId>` Hermes home with own `config.yaml` (copied model/providers from primary), `.env` (API server key, inherited model API keys — never messaging tokens), `auth.json` (copied subscription sign-ins), `SOUL.md`, `BH_HOME` browser-harness, `DISPLAY`, CDP url, terminal cwd = Library folder, `mcp_servers.hq` (+ `EXTRA_MCP_SERVERS`); spawned detached, health-polled 90 s, adopted across server restarts, pid sanity via /proc.
- Where: `server/src/engine.ts` (whole file), `db.ts` columns engine/engine_port.
- Deps: Hermes Agent (Nous Research, OSS), Python on the box.
- PORT: no equivalent (AM/AT run their own loops; AM `BackupBridge.kt`/`FreeRouter.kt` mention Hermes only as a provider). Land: **do not embed Hermes on the phone** as the default engine (Python + browser harness + MCP in proot is heavy and duplicates AM's mature Kotlin agent). Keep the *per-bot home* concept natively (BH-40). Offer an **optional "external engine" adapter (C)**: a bot can be bound to any OpenAI-Responses-compatible agent endpoint (a user's Hermes on Blaze Desktop / a VPS / Orgo), reached via AM `link/DesktopLink.kt` pairing or a URL+key in Keystore.

**BH-30 · App system prompt (cache-stable)** — P0 — "Team app" block: who you are, app tools, your computer, style, context (owner, connected apps, teammates, groups, routines, skills, instructions); deliberately clock-free so the prefix caches. `agent/prompt.ts`. PORT: **AM** PromptParts/SharedPersona (same caching idea) → **N**; add teammates + groups list to AM prompt.

**BH-31 · Model & provider picker per bot** — P0 — Engine panel lists providers from Hermes `/api/model/options` (connected first; well-known: anthropic, openai-codex, xai-oauth, xai, openrouter, nous, gemini, openai-api), suggested models, free-text model id; "default" = engine default. `engine.ts engineInfo/toProviders`, `routes-config.ts /engine`, `web/src/components/EngineSettings.tsx`.
- PORT: **AT** deliberately has *no picker* (gateway live list `GET /v1/models/live`) + per-bot Gemini key; **AM** ProviderCatalog/ModelCatalog/ModelRoute + Cloud.kt (gateway picks). Land **N + G**: default "Auto (ARGUS gateway)"; advanced per-bot override (gateway model, BYO key provider, or external engine).

**BH-32 · Subscription sign-in (Claude Pro/Max, ChatGPT, Grok) via terminal on the bot's screen** — P1
- What: opens a terminal window on the bot's Orgo screen running `hermes auth add <provider> [--type oauth]` or `hermes model`; user finishes in the live viewer; each bot's auth.json is its own.
- Where: `routes-config.ts /engine/login`, `desk.ts openTerminal`, `hq-screen.py terminal`.
- 💲 consumer subscriptions; ToS risk (README warns).
- PORT: **AM** `terminal/CliAuth.kt` (CLI auth in proot) is the natural home. Land **P** (run provider CLIs' OAuth in the proot terminal sheet) — flag ToS per provider; keep gateway as default.

**BH-33 · Engine status / restart / version** — P1 — ready/starting/stopped/error, Hermes version, Restart (managed only). `engine.ts`, `routes-config.ts /engine/restart`. PORT **N** (status chip per bot: gateway health / external engine health).

**BH-34 · "Own screen" detector (Hermes Bot Desktop) + stop** — P2 — detects Hermes' private Xvnc screen so the user isn't watching an idle desktop; button runs `hermes computer-use screen stop`. `engine.ts ownScreenOn/stopOwnScreen`, `/engine/screen/stop`. PORT: only relevant to the external-Hermes adapter **C**.

**BH-35 · Extra MCP servers for every bot (`EXTRA_MCP_SERVERS`, e.g. X's XMCP)** — P1 — `env.ts`, `engine.ts extraMcpServers`. PORT: **AM** `connect/Mcp.kt` + McpConfig (mcp.json, encrypted, synced, Streamable HTTP + SSE, per-tool Ask/Always/Off) already better → **N**, add per-bot MCP enablement.

**BH-36 · Hermes-native tool surface (what bots can do through Hermes)** — P0 (capability checklist)
- terminal/process/execute_code, read_file/write_file/patch/search_files, web_search, web_extract, browser_* (CDP), computer_use, vision_analyze, memory, session_search, todo, delegate_task, send_message, skill*, clarify, cronjob, tool_search/tool_describe. Labels in `agent/tools.ts activityFor`.
- PORT: AM equivalents exist for nearly all: run_shell/run_code (P), file tools, `Web.kt` keyless search (DDG→Bing→GitHub), `browser/` + Phone browser tools, Vision.kt, Memory/Mind (remember/recall/forget), LongChat FTS (session_search), GoalPin/TaskKeeper (todo), BgAgents/Dispatch (delegate), Routines (cronjob), Autonomy `find_tool` (tool_search). **Gap:** desktop `computer_use` on a GUI box (see §6). AT: run blocks, browser tool, remember/recall, add_job. Land **N/P**.

---

## 5. App tools served over MCP

**BH-37 · Per-bot MCP endpoint (`POST /mcp/<botId>`, `/mcp/primary`)** — P1
- What: stateless JSON-RPC (initialize, ping, tools/list, tools/call, empty resources/prompts); bearer = HMAC(SESSION_SECRET, "mcp:<bot>"); lists app tools + the user's Composio meta-tools; claims the matching tool-call row; runs approvals.
- Where: `server/src/mcp.ts`, `engine.ts mcpToken`.
- PORT: **AT** `api/` Cloudflare Worker already exposes REST + **MCP** (`/mcp`, summary/list_agents/upsert_agent/jobs…). Land **G**: extend argus-agents-api MCP with the Bot HQ tool set (tasks, routines, library, send_to_bot, post_to_group, ask_user relay) so *external* engines (Hermes on Desktop, Claude Code, ARGUS Desktop) can act on the same team. On the phone the tools are just native tool functions (**N**).

Full app tool list (each is its own feature):

| ID | Tool | What | Source | PORT |
|---|---|---|---|---|
| BH-38 | `save_file` | write deliverable to Library `/root/grokbot/files/<bot>/` | tools.ts | AM SaveFile.kt (Downloads/Blaze); AT ```save/```attach + working set (`files.js`) → **N/P** per-bot Library folder |
| BH-39 | `x_search` 💲 | live X research via xAI Responses `x_search` tool (handles allow/exclude ≤10, from/to dates), answer + sources | tools.ts xSearch | none in AM/AT → **G** (gateway route `/v1/x-search`, BYO xAI key or ARGUS-paid). Alt: SearXNG self-hosted with `site:x.com`, Bluesky/Mastodon open APIs; official X API is paid ($) |
| BH-40 | `create_routine` / `list_routines` / `update_routine` / `delete_routine` | 5-field cron + IANA tz, validated with croner | tools.ts, routines.ts | AM Routines.kt (days bitmask, one-offs, shell jobs, alarms) + AT RoutineAlarm/RoutineJob + Manager routine_* → **N** |
| BH-41 | `list_event_triggers` / `create_event_routine` 💲 | Composio triggers (new Gmail, Slack msg, GitHub PR…) wake the bot with payload | tools.ts, triggers.ts | AM proactive/Triggers.kt (notification / battery / charger / app-opened) → **N** for device events; **G** for cloud app events (ARGUS Connect webhooks/polling) |
| BH-42 | `stay_silent` | end turn with no message (routines, check-ins, bot-to-bot) | tools.ts | AM proactive "most checks end in silence" → **N** formal tool |
| BH-43 | `set_checkins` | turn primary-bot check-ins on/off, times, tz | tools.ts, proactive.ts | AT Manager daily check (quiet hours, cap, mutes); AM Proactive dial → **N** |
| BH-44 | `create_task` / `update_task` | task per request: working / needs_input / done + note | tools.ts | AT jobs (deps, queue, done_job verified) ≫ this; AM TaskKeeper → **N** (AT jobs model) |
| BH-45 | `send_to_bot` | message another bot by name; reply comes back as a message; hop limit 3 | tools.ts, loop.ts enqueueBotMessage | AT `agent_delegate` (Manager only); AM Dispatch/BgAgents (specialists, not peers) → **N** peer messaging for every bot |
| BH-46 | `create_group` / `post_to_group` | bot creates a group chat; members post into it | tools.ts, rooms.ts | none → **N** (§7) |
| BH-47 | `ask_user` | question card (BH-26) | tools.ts | **N** |
| BH-48 | `request_app_connection` | connect card (BH-27) | tools.ts | **N+G** |
| BH-49 | Composio meta-tools `COMPOSIO_SEARCH_TOOLS`, `COMPOSIO_MULTI_EXECUTE_TOOL`, workbench/bash 💲 | find + run any of the user's connected-app actions | composio.ts, mcp.ts | AT/AM ARGUS Connect `/v1/me/tools` + `/v1/me/execute` → **G** |

---

## 6. The computer (Orgo screens, viewer, takeover)

**BH-50 · One shared cloud computer, one screen per bot** 💲 — P1
- What: single Orgo Linux VM (`ORGO_COMPUTER_ID`); primary bot uses default screen `:99`; others lease Orgo screens (4 max) persisted in `screens` table, re-verified every 2 min, re-leased after VM restart; computer woken/started on demand (never suspended by the app).
- Where: `server/src/computer.ts`, `orgo.ts`, `box.ts` (local shell when `HQ_LOCAL=1`, else Orgo bash API with base64-chunked file writes).
- Deps: **Orgo** ($0.055/h running per AT notes; 4-screen limit).
- PORT: **AT** cloud computer (Orgo **or GitHub Codespaces**, auto-stop guard `CloudGuard.java`, keys in Keystore); **AM** proot Alpine terminal (Linux XFCE/VNC desktop was *removed* in wave B-4). Land: **P** first (each bot gets its own proot workspace folder/user + its own headless Chromium with CDP, as AT already does per bot), **C** optional (AT's Orgo/Codespaces provider + auto-stop). Self-hosted alternative: Docker image with Xvfb/TigerVNC + noVNC (e.g. linuxserver/webtop or kasmweb), or E2B Desktop OSS / Daytona; one container per bot removes the 4-screen cap.

**BH-51 · Screen "dressing" (bot's own computer look)** — P2
- What: on the box, Python scripts install once (`base`), then per screen: soft wallpaper in the bot's colour (seeded SVG), no taskbar, small dock (Browser/Terminal/Files) via GTK `hq-shell.py`, light GTK theme, maximized Chrome with own profile `/root/grokbot/profiles/<bot>` and CDP port 9200+display, home-screen new-tab page (MV3 extension `ext/` + local server `hq-home.py` on 127.0.0.1:7391 that identifies the asking Chrome by TCP source port → pid → profile → bot) showing avatar, clock, name, role, apps; Chrome policy installs the extension even into the primary bot's own Chrome; redress on rename/recolour; undress on delete; jobs with HQ_DONE/HQ_RUNNING protocol.
- Where: `server/src/desk.ts`, `server/box/hqlib.py`, `hq-screen.py`, `hq-shell.py`, `hq-home.py`, `home.html`, `ext/*`; test `server/scripts/desk-test.ts`.
- PORT: no equivalent. Land **C** only (if a GUI box is used); on-phone, the "bot's computer" is a Compose **Workspace page** per bot (files, terminal tab in the bot's tmux window as AT does, browser live preview) — far cheaper than dressing X displays.

**BH-52 · Live viewer: watch (view-only) / take control / hand back** — P0 (brief calls it out)
- What: noVNC in a rounded frame keeping aspect ratio; small live view in details panel, large stage viewer, fullscreen; Watch drops key/pointer/clipboard **server-side**; Take control reconnects with `mode=control`; Hand back sets `handback_at` and the bot's next turn starts with "the user took control… look at the screen first"; panel too small to drive → control only in large/fullscreen; Reset screen (close windows, Chrome back to home, logins kept).
- Where: `server/src/vnc.ts` (RFB handshake upstream with VNC password + pure-JS DES `lib/des.ts`; offers browser an auth-less RFB; WatchFilter), `web/src/components/ComputerViewer.tsx`, `lib/computer.ts`, `routes-config.ts /handback, /screen/reset`, `idle.ts` viewer counting.
- Deps: Orgo websockify, noVNC (MPL-2.0).
- PORT: **AT** live *screenshot* card (~1.5 s polling, Expand, Stop) — no takeover; **AM** BrowserLivePreview, PhoneLiveCard, `browser/phone` engine. Land **N** for the in-app browser (bot drives an Android WebView/Chromium tab; user can tap "Take over" to interact directly, "Hand back" injects the same note) and **C** for remote GUI (Compose noVNC-equivalent: render RFB in a `SurfaceView`, or embed noVNC in a WebView against a gateway-relayed websocket; keep the server-side watch filter so view-only is enforced, and never ship the VNC password to the phone).

**BH-53 · Screenshot endpoint / thumbnails on tool steps** — P1 — `/bots/:id/screenshot` (Orgo JPEG q75), `ToolCallBody.screenshot`. PORT: AT screenshot card, AM screen read → **N/C**.

**BH-54 · Computer settings page** — P2 — status, screens in use "n of 4", per-screen reset, hibernation info. `AppSettings.tsx ComputerSection`, `/computer/details`. PORT: AT Settings > Cloud computer (running minutes) → **N**.

**BH-55 · Idle/hibernation watcher (disabled)** — P2 — pure decision function (turns running? viewers? last activity ≥20 min → suspend), dry-run mode; production never suspends. `idle.ts`. PORT: AT CloudGuard (5 idle min, 60 min max, alarm + job even when app killed) is the real one → **N**.

**BH-56 · Primary bot's own Chrome respected (`PRIMARY_CDP_URL`)** — P2 — never start/stop a Chrome the primary agent already owns. `computer.ts managesChrome`. PORT: rule for any external engine **C**.

---

## 7. Bots working together

**BH-57 · Bot-to-bot messaging** — P0 — see BH-45; message shows in the target's chat as from the sender; reply returns via `send_to_bot`; ping-pong stops at depth 3. PORT **N**: every bot can message any teammate; Manager sees the thread; show "Juno → Mira" provenance; keep the hop cap.

**BH-58 · Group chats (rooms)** — P0
- What: a room is a bot row `kind='room'` with members (≥2 bots); user message dispatches to @mentioned members, else all (max 3 responders), each gets "[Group chat X] members… recent 12 messages… reply with post_to_group or stay_silent"; members add/remove (UI + event rows "X joined · Y left"); created from UI (New group chat modal) or by a bot (`create_group`).
- Where: `server/src/agent/rooms.ts`, `routes-rooms.ts`, `routes.ts messages`, `web/src/components/modals/CreateGroupModal.tsx`, `MembersCard.tsx`, `DetailsPanel.tsx`.
- PORT: **AM** Duo/MeetingRoom (Blaze+Halo, merged away in 0.51) has the parallel-answer + "react once" mechanics in `Duo.kt` (pure, tested) and a Meeting room UI. **AT** none. Land **N**: Group threads with @mentions, max-responders, "nothing to add → silent"; reuse Duo's parallel run + single reaction; MeetingRoom UI as "watch the bots discuss".

**BH-59 · Subagents (hidden child bots, ≤4 per parent)** — P1 — **unwired** in Bot HQ (`agent/subagents.ts`). PORT: **AM** BgAgents + Dispatch `delegate` + Orchestrator blueprints already do this better → **N**.

**BH-60 · Hermes delegation for work inside one task** — P2 — Hermes `delegate_task` (ARCHITECTURE.md). PORT: AM Dispatch **N**.

---

## 8. Scheduling and proactivity

**BH-61 · Cron routines** — P0 — scheduler with croner (`protect: true`), next-run computed, human description ("Weekdays at 8:00 AM"), run → event row "Running routine: X" + routine turn; UI: Upcoming list with toggles in details panel, Routines tab (Run now, Delete, pause), event rows in chat. `routines.ts`, `store.ts describeCron/toRoutine`, `routes.ts /routines/*`, `DetailsPanel.tsx`, `BotSettingsModal.tsx RoutinesTab`.
- PORT: **AM** Routines.kt + RoutineReceiver (alarms survive app kill, shell-job routines, one-offs) + SchedulePage; **AT** RoutineAlarm/RoutineJob. Land **N** (AlarmManager/WorkManager); add cron-expression support + per-bot ownership.

**BH-62 · Event routines (Composio triggers, realtime subscription)** 💲 — P1 — `triggers.ts` (create/enable/disable/delete trigger, `event:<SLUG>` schedule, payload ≤8 KB appended to prompt). PORT: see BH-41. Alt to Composio triggers: ARGUS Connect webhooks into the gateway + FCM push to the phone; Gmail push via Pub/Sub, IMAP IDLE in proot, GitHub webhooks; n8n/Activepieces self-hosted.

**BH-63 · Proactive check-ins (primary bot)** — P0 — up to 6 local times (default 09:00, 16:30) + tz; 60 s tick, 5-min window, once per slot per day; skip if user spoke in last 20 min; context: tasks needing input, pending approvals, routines run in last 12 h, team; prompt "send ONE short message or stay_silent"; silent-streak tracking; "check in now" endpoint. `proactive.ts`, `routes-proactive.ts`.
- PORT: **AT** Manager daily suggestions (≤3, quiet hours, daily cap, per-topic mutes); **AM** Proactive.kt (dial Off/Low/Normal/High), Jarvis.kt, FollowUps. Land **N**: unify into one proactive engine owned by the Manager; add Bot HQ's "don't interrupt an active conversation" and "summarise what's waiting on you" context.

---

## 9. Tasks, Library, Skills, Memory

**BH-64 · Tasks (per bot + global Tasks view)** — P0 — states working/needs_input/done, note; details panel tab + full Tasks view with filters, tap opens the bot. `tools.ts`, `routes.ts /tasks`, `web/src/components/config/TasksView.tsx`.
- PORT: **AT** jobs ≫ (due dates, priority, deps/waits-on, queue, one-at-a-time, verified done, notifications, cloud API sync, Compose graph). Land **N**: AT jobs model is canonical; Bot HQ's `needs_input` state + global view merge in.

**BH-65 · Library (bot's files)** — P0 — browse folders under the bot's Library dir, newest first, download (local stream with realpath escape check, or Orgo `/files/export` signed URL), refresh; "asleep" state when the VM sleeps. `routes-config.ts /files*`, `web/src/components/config/LibraryTab.tsx`.
- PORT: **AT** files.js (attach cards, HTML preview sheet, Open/Share/Save, zip folders, working set); **AM** SaveFile, Gallery. Land **N/P**: per-bot Library = `~/bots/<id>/library` in the proot workspace, Compose file list, share sheet, MediaStore export.

**BH-66 · Skills (per bot, user-written)** — P0 — name, description, steps (≤20k chars); CRUD in Bot settings → Skills; all skills inlined into the prompt as "Skills <owner> gave you". `routes-config.ts /skills`, `BotSettingsModal.tsx SkillsTab`, `prompt.ts`.
- PORT: **AM** SkillLibrary (bundled SKILL.md, versioned seeding), SkillIndex/SkillMatch (load on demand), Marketplace (GitHub `blaze-skill` packs with red-flag review), SkillSync; **AT** My skills + Argus originals + Polish via ARGUS Connect. Land **N + G**: skills are SKILL.md files, matched on demand (not all inlined), assignable per bot; Polish + marketplace kept.

**BH-67 · Memory view: MEMORY.md + USER.md, forget one** — P0 — entries split by "§" lines; shown as "Picked up" (MEMORY.md) vs "Saved in chat" (USER.md); delete by content hash id; a "conversation memory % full" meter (always 0 now). `engine-memory.ts`, `routes.ts /memories`, `BotSettingsModal.tsx MemoriesTab`.
- PORT: **AM** Mind (one memory, kinds profile/preference/person/project/decision/fact, FTS5, dedupe, review, MemoryPage + MemGraph); **AT** hybrid memory (private per bot + shared team + reports-to visibility, secrets refused). Land **N**: Mind as the store; per-bot scoping from AT; **plus** export/edit as real markdown files per bot (`~/bots/<id>/MEMORY.md`, `USER.md`, `SOUL.md`) so the brief's "editable soul + memory files" is literally true — editable in-app and from the terminal, synced two-way with Mind.

**BH-68 · Voice agent memory injection** — P1 — top 12 memories (user facts first) + routines + 14 recent messages + live activity snapshot go into the voice prompt. `voice.ts voiceInstructions`. PORT **N** (same context builder for AM CallScreen).

---

## 10. Voice

**BH-69 · Voice call (realtime, speaks as the bot)** 💲 — P0
- What: server mints a 300 s xAI realtime client secret; browser opens `wss://api.x.ai/v1/realtime` directly (PCM 24 kHz, server VAD, grok-transcribe input transcription); voice agent prompt = persona + rules ("one or two sentences", no markdown, never narrate tools); 5 voice tools: `send_task` (hands work to the text agent, returns immediately; results come back as `[work_landed]` notes injected into the call), `recall_text_messages`, `search_conversations` (this chat / earlier / all bots), `peek_activity`, `end_the_call`; call start/end event rows with duration, transcript (≤200 lines), handed-off tasks, and a one-sentence summary (xAI, background); calls without /end closed after 2 h; call bar UI with mute, live captions, "working on…" task line, expand transcript.
- Where: `server/src/voice.ts`, `web/src/voice/call.ts`, `voice/audio.ts` (AudioWorklet mic capture, PCM player), `voice/api.ts`, `components/VoiceCallBar.tsx`, `tests/voice-call.mjs`.
- Deps: **xAI realtime** (grok-voice-latest), OpenAI SDK for summary.
- PORT: **AM** CallScreen.kt (hands-free call, barge-in, cards still tappable) on Android SpeechRecognizer + TTS / Gemini TTS / "My voice" clone; wake word "Hey Blaze" (`WakeWordService`). **AT** none. Land **N**: keep AM's call screen; add Bot HQ's **two-agent pattern** (fast voice persona + `send_task` to the real agent + `[work_landed]` + peek/recall/search tools + call record row with summary). Realtime backend selectable: on-device (free), Gemini Live (user key), xAI/OpenAI realtime (BYO key, 💲). Self-hosted alt: LiveKit Agents / Pipecat + faster-whisper/whisper.cpp + Piper/Kokoro TTS on the user's box.

**BH-70 · Per-bot voice settings (voice, speed 0.7–1.5, language/auto, preview)** 💲 — P1 — 28 fallback voices or `GET /v1/tts/voices`; 21 languages; preview via `/v1/tts` (mp3/pcm). `voice.ts /options, /preview`, `components/VoiceSettings.tsx`. PORT: **AM** VoiceSettings.kt, MyVoiceSettings, Gemini voices → **N**, voice per bot (each specialist sounds different).

**BH-71 · Dictation (hold-to-talk STT)** 💲 — P1 — MediaRecorder → `/voice/stt` → xAI `grok-voice-transcribe-2.0` (≤25 MB). `voice/dictation.ts`, `voice.ts /stt`. PORT: **AM** Android recognizer + on-device transcription in Life logs → **N** (no paid STT needed).

---

## 11. Connected apps (Composio)

**BH-72 · App marketplace (catalog, categories, search, connect/disconnect)** 💲 — P0 — Composio toolkits sorted by usage (≤1500, cached 1 h), category pills (Password Managers, Productivity, Communication, Design, Code + more), "Picked for you", Connected section, Built-in rows, disconnect with confirm; "Bot templates" disabled. `composio.ts catalog/connectedToolkits/disconnect`, `routes.ts /apps*`, `web/src/components/modals/ConnectAppsModal.tsx`, `ToolkitLogo.tsx`, `AppSettings.tsx AppsSection`.
- PORT: **AT** Settings > Connectors via **ARGUS Connect** (`connect.js`, logos, category filter, per-tool on/off, Test, OAuth loopback, keys encrypted server-side per user) — ARGUS's own self-hosted alternative to Composio; **AM** `connect/Connectors.kt` (loopback PKCE, GitHub device flow, token paste, one-click via blaze-auth) + Mcp.kt. Land **G + N**: ARGUS Connect is the backend; Compose marketplace UI in A-team style. Gap: catalogue breadth (Composio ~1000 apps) → grow ARGUS Connect with **MCP servers** (official vendor MCPs) and optionally Nango (self-hostable) / Activepieces pieces (MIT).

**BH-73 · Per-user Composio sessions + tool execution** 💲 — `composio.ts sessionFor/composioTools/executeComposio/innerToolSlugs`, db `composio_sessions`. PORT **G** (`/v1/me/tools`, `/v1/me/execute`).

**BH-74 · "Add to Slack" suggestion** — P2 — details panel suggestion that just opens Slack connect; hideable. `DetailsPanel.tsx`. PORT: AT/AM "suggested connectors" chips **N**.

---

## 12. Auth, accounts, security

**BH-75 · Google sign-in via a Composio-managed Gmail connection** 💲 — `routes.ts /auth/google/*`, `composio.ts startGoogleLogin/googleProfile` (reads Gmail profile, deletes throwaway connection). PORT: **AM/AT** ARGUS account (Google/GitHub PKCE, email+password, MFA, biometric) → **G**; drop this hack.

**BH-76 · Allow-list (`ALLOWED_EMAILS`)** — P2 — `env.ts`, `findOrCreateUser`. PORT: gateway waitlist/access (AM Cloud.kt 403 waitlisted) **G**.

**BH-77 · Dev login, owner key, one-time sign-in links** — P2 — `/auth/dev` (DEV_LOGIN), `/auth/owner` (HMAC of SESSION_SECRET), `/auth/link/:token` 15-min one-shot, `scripts/sign-in-link.sh`. PORT: dev-only; AM DesktopLink QR pairing is the phone equivalent **N**.

**BH-78 · Sessions (30-day httpOnly cookie, SQLite)** — `lib/auth.ts`. PORT: ARGUS session token in Keystore **N/G**.

**BH-79 · Secrets hygiene** — keys from env or `*_FILE`; child gateways and box shells get a minimal env (no server secrets); private 0600 files; VNC password never reaches the browser; managed bots inherit model keys but never messaging tokens. `env.ts`, `engine.ts`, `box.ts shellEnv`, `vnc.ts`. PORT: **AM** Secrets/SecretVault/Keystore, **AT** vault.js/SecretStore.java (same principles) **N**.

**BH-80 · Known security gaps (from README)** — bots share one computer and can read each other's files; browser/shell actions are **not** approval-gated; Hermes' own risky-command prompts not surfaced; no audit. PORT: AM ConfirmGate taint + CommandPolicy + AT guard.js already cover shell/phone actions — **do not regress** when porting; per-bot workspace isolation (separate proot users or folders with path guards).

---

## 13. Realtime transport, data, ops, tests

**BH-81 · SSE event hub (per user)** — events hello/rows/cleared/live/roster/bot/computer/routines/tasks/memories/connections/ping (20 s). `lib/hub.ts`, `routes.ts /events`, `web/src/lib/events.ts`. PORT: in-process `StateFlow`s on the phone **N**; gateway SSE for cross-device sync **G**.

**BH-82 · REST API surface** (for reference when wiring argus-agents-api): `/api/health`; auth `/auth/{me,google/start,google/callback,dev,owner,link/:t,logout}`; `/events`; `/bots` (GET, POST), `/bots/:id` (PATCH, DELETE), `/bots/:id/{primary,read,rows,messages,interrupt,fresh,routines,tasks,memories,screenshot,settings,skills,engine,engine/login,engine/restart,engine/screen/stop,handback,files,files/download,files/raw,screen/reset}`; `/approvals/:id`; `/bots/:id/cards/:seq/{answer,dismiss}`; `/routines/:id` (PATCH, DELETE), `/routines/:id/run`; `/tasks`; `/memories/:id` (DELETE); `/computer`, `/computer/{ensure,suspend,details}`; `/apps`, `/apps/:slug/connect`, `/apps/callback`, `/apps/:slug` (DELETE); `/policies`, `/policies/:tool`; `/skills/:id`; `/checkins` (GET, PUT), `/checkins/now`; `/rooms` (POST), `/rooms/:id/members` (GET, PUT); `/voice/{options,session,tool,end,preview,stt}`; WS `/api/bots/:id/screen?mode=watch|control`; `POST /mcp/:bot`. Sources: `routes*.ts`, `voice.ts`, `mcp.ts`, `vnc.ts`. PORT **G** subset.

**BH-83 · SQLite schema** — users, auth_sessions, login_attempts, computers, screens, bots (+ voice, model, provider, engine, engine_port, handback_at, kind, members, parent_bot_id, hidden), rows, llm_items*, pending_turns*, approvals, policies, routines (+ trigger_id, trigger_config), tasks, memories*, skills (+ description, updated_at), composio_sessions, proactivity, memory_jobs*, compactions* (*unused). `db.ts`, `db-config.ts`, `rooms.ts`, `subagents.ts`, `triggers.ts`, `proactive.ts`. PORT: AM blaze.db (SQLite + FTS5, same file format as Desktop/CLI) **N**.

**BH-84 · Install/upgrade on the computer** — `scripts/pack.sh` (build tarball, no secrets), `install-on-computer.sh` (private Node 22, `/opt/hq`, supervisor program `hq-app`, .env written once, KEYS_FILE deleted), `add-hq-to-hermes.py` (text-preserving YAML edit with backup + read-back check, `--remove`), uninstall steps. PORT: only for the optional self-hosted "ARGUS box" **C**; AM `update/` handles app updates.

**BH-85 · Tests** — `tests/e2e.mjs` (Playwright against a live install: create bot, streaming chat, shell on its screen, viewer watch/control/handback, search, connect apps, voice secret + greeting audio, group @mention, settings tabs, library/tasks, computer + permissions, delete); `tests/voice-call.mjs` (fake-mic two-way call with `ask-weather.wav`); `server/scripts/desk-test.ts` (screen dressing on a test VM); `desSelfTest()` at boot. PORT: AM JVM unit tests (pure objects) + AT Playwright UI tests → write equivalents for each ported flow (group chat routing, approval expiry, hop cap, voice send_task/work_landed).

**BH-86 · Branding leftovers** — app still says "Grokbot" (cookie `grokbot_session`, db file, localStorage keys, page title). PORT: n/a (ARGUS naming; AM RENAME-ARGUS.md).

---

## 14. Missing from Bot HQ but promised by the brief (must be designed, not ported)

**BH-87 · Muse-style social connections** — not in Bot HQ. Proposed landing (for slice 5): a **People** graph (AM Mind already has `person` memories + contacts access via phone Skills; AM notify/GroupBrain reads group chats; AT memory has team/private scopes). Each person node: relation, channels, key dates, what each bot knows; bots can be *introduced* to people; "connections" between the user's bots and other users' ARGUS bots (bot-to-bot across accounts via argus-agents-api MCP, with consent + approval cards). Open questions → slice 5.

**BH-88 · Editable soul + memory files** — partially present (BH-05, BH-67); the editor is new work.

**BH-89 · Grok Bot features not carried into Bot HQ's code** (from its own docs/comments): Grok-style "multiple bots texting" ✔ ported; X search ✔; voice ✔; subagents ✗ (dead code); bot templates ✗ (disabled); drafts ✗ (UI only).

---

## 15. Paid services → open / self-hosted alternatives (summary)

| Bot HQ dependency | Used for | Cost | Already in ARGUS | Open / self-hosted alternative |
|---|---|---|---|---|
| **xAI** | x_search, realtime voice, TTS, STT, call summaries | per token/minute | none | Voice: on-device Android STT/TTS (AM), Gemini Live/TTS free tier (BYO key, AM GeminiVoice), LiveKit Agents or Pipecat + whisper.cpp/faster-whisper + Piper/Kokoro. X research: BYO xAI key via gateway; SearXNG (`site:x.com`), Bluesky AT-proto / Mastodon APIs (free) |
| **Composio** | connector catalog, auth, tool exec, triggers, Google sign-in | paid tiers | **ARGUS Connect** (AT/AM), AM Connectors + MCP client | Nango (self-host), Activepieces (MIT), n8n (fair-code), vendor MCP servers; webhooks → gateway → FCM for triggers |
| **Orgo** | cloud VM, 4 screens, VNC, file export, public URL | ~$0.055/h running | AT Orgo + Codespaces provider with auto-stop | Phone proot Ubuntu (AT) for most work; Docker + Xvfb/TigerVNC + noVNC (webtop/kasm) on any VPS; E2B Desktop (OSS self-host); GitHub Codespaces free 60 core-h/mo |
| **Hermes Agent** | agent loop per bot | free OSS (models cost) | AM Kotlin agent (Agent.kt, CloudRuns) | Keep native loop; optional external-engine adapter for a user-hosted Hermes |
| Model subscriptions/keys | Claude/ChatGPT/Grok | user-paid | ARGUS gateway live free models (AT/AM) | Gateway auto (free lane), OpenRouter free, local models (AM OfflineBrain) |

---

## 16. Top 10 port items (ranked)

1. **Multi-bot team + Manager as primary** (BH-01/02/04) — AT bot model + AM Specialists, no 4-bot cap.
2. **Bot-to-bot messaging + group chats with @mentions** (BH-45/57/58) — new for both apps; reuse AM Duo parallel/react logic.
3. **Inline approval / question / connect / draft cards with "Always allow" + Permissions page** (BH-23–28) — on AM ConfirmGate (taint-aware) + per-tool policies.
4. **Live activity + "Worked for · N steps" transcript on the row model** (BH-10/13/14) — clean, card-friendly chat store.
5. **Per-bot computer you can watch and take over / hand back** (BH-52) — on-phone browser takeover now; remote GUI via gateway relay later.
6. **Voice two-agent pattern** (BH-69) — voice persona + `send_task` + `[work_landed]` + peek/recall/search + call record & summary, on AM CallScreen with pluggable realtime backends.
7. **Editable SOUL.md / MEMORY.md / USER.md per bot** (BH-05/67/88) — files mirrored with Mind, editable in app + terminal.
8. **Routines: cron + event + proactive check-ins unified under the Manager** (BH-40/41/61–63) — AM alarms + AT Manager rules + Bot HQ "don't interrupt / stay_silent".
9. **Connected-app marketplace on ARGUS Connect + per-bot MCP** (BH-35/49/72) — replace Composio.
10. **Per-bot Library + Tasks** (BH-64/65) — AT jobs model + AT files working set, per-bot folders in proot.

## 17. Biggest gaps & risks

- **Social connections don't exist in Bot HQ** — the brief over-credits it; slice 5 must design this from zero (privacy, consent, cross-account bot messaging).
- **Bot HQ is server-centric** (one always-on Linux box running 4 Python agents + Chrome + VNC). The phone can't host that; the port is mostly *concepts + UX*, with heavy parts on proot (limited, arm64, no GUI since AM removed the desktop) or a paid/self-hosted cloud box. Decide early whether ARGUS ships a "box" (self-hosted Docker image) or stays phone+gateway.
- **Paid dependency stack** (xAI, Composio, Orgo) — every one has a free/self-hosted path, but X search has no good free equivalent (X API is paid; scraping is fragile/ToS).
- **Security regression risk**: Bot HQ gates only Composio writes; AM/AT gate shell, phone and outbound actions with taint tracking. Port Bot HQ UX on top of AM's gate, never instead of it. Bots sharing a workspace can read each other's files — isolate per bot.
- **Engine duplication**: embedding Hermes would create a second agent loop beside AM's; recommend native loop + optional external-engine adapter.
- **Consumer-subscription sign-in for agents** (BH-32) carries provider ToS risk.
- **Licensing**: Bot HQ code is MIT (copyable with notice), but its avatar artwork/icons come from Grok Bot and app logos are third-party — redraw; noVNC is MPL-2.0 if embedded.
- **Dead code** (subagents, drafts, stubs) — don't port blindly; AM already has stronger versions.
- **Concurrency on phones**: N bots × background turns × voice — use AM's BgAgents RAM-based slots and AT's one-job-per-bot queue.

## Appendix A · File → feature map (every source file accounted for)

| File | Features |
|---|---|
| README.md, docs/ARCHITECTURE.md | overview, limits (BH-80), Hermes Bot Mode comparison |
| docs/orgo-builder-guide.md | Orgo API reference (lifecycle, screens, VNC, files, secrets, templates, pricing) — background for BH-50–56 |
| docs/xai-and-composio.md | xAI (models, Responses, tools, realtime, TTS/STT) + Composio (auth, catalog, tool router, triggers) reference — BH-39, 69–73 |
| .env.example, server/src/env.ts | config + secrets (BH-79) |
| server/src/index.ts | boot: DES self-test, routes, VNC relay, schedulers (`GROKBOT_NO_SCHEDULER`), idle, engines |
| server/src/agent/loop.ts | BH-11, 12, 13, 15, 23, 57 |
| server/src/agent/tools.ts | BH-36–49, 24 |
| server/src/agent/prompt.ts | BH-30 |
| server/src/agent/rooms.ts, routes-rooms.ts | BH-58 |
| server/src/agent/subagents.ts | BH-59 (dead) |
| server/src/engine.ts | BH-05, 29, 31–35 |
| server/src/engine-memory.ts | BH-67 |
| server/src/mcp.ts | BH-37 |
| server/src/composio.ts | BH-49, 72, 73, 75 |
| server/src/triggers.ts | BH-41, 62 |
| server/src/routines.ts | BH-61 |
| server/src/proactive.ts, routes-proactive.ts | BH-43, 63 |
| server/src/voice.ts | BH-69–71 |
| server/src/computer.ts, box.ts, orgo.ts | BH-50, 53, 56 |
| server/src/desk.ts, server/box/* | BH-51 |
| server/src/vnc.ts, lib/des.ts, idle.ts | BH-52, 55 |
| server/src/routes.ts | BH-01, 02, 06, 16, 26, 27, 64, 72, 75–77, 82 |
| server/src/routes-config.ts | BH-32–34, 52, 54, 65, 66, 67 |
| server/src/store.ts, db.ts, db-config.ts, types.ts | BH-10, 83 |
| server/src/lib/{auth,hub,ids}.ts | BH-78, 81 |
| server/scripts/desk-test.ts, tests/*.mjs, tests/ask-weather.wav | BH-85 |
| scripts/{pack.sh,install-on-computer.sh,add-hq-to-hermes.py,sign-in-link.sh} | BH-77, 84 |
| web/src/App.tsx, main.tsx, store.ts, configStore.ts, lib/* | shell, shortcuts, events, theme (BH-08, 09, 81) |
| web/src/components/{Sidebar,ChatView,Composer,Transcript,ActivityLine,Markdown,Menu,Icon,ToolkitLogo,SignIn}.tsx | BH-06, 13, 14, 17–21 |
| web/src/components/{DetailsPanel,MembersCard,EngineSettings,VoiceSettings,VoiceCallBar,ComputerViewer}.tsx | BH-31, 52, 58, 61, 69, 70, 74 |
| web/src/components/cards/Cards.tsx | BH-23, 26–28 |
| web/src/components/config/{AppSettings,BotSettingsModal,ConfigLayer,LibraryTab,TasksView,ui,glyphs}.tsx | BH-04, 25, 54, 64–67 |
| web/src/components/modals/{CreateBot,CreateGroup,ConnectApps,Search,Modal}.tsx | BH-01, 07, 58, 72 |
| web/src/voice/{call,audio,api,dictation}.ts | BH-69, 71 |
| web/src/avatar/*, web/artwork/*, web/scripts/gen-shapes.mjs, assets/* | BH-03 (artwork not MIT) |
| web/src/dev/fixtures.ts | BH-22 |
| web/public/assets/* | icons + third-party app logos (not MIT) |
