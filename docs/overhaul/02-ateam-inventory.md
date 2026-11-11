# 02 · A-team feature inventory + port notes

> Slice 2 of the ARGUS overhaul. **Status: complete (first full pass, 2026-11-11).**
> Source: `gitlab.com/blazeinchue/a-team` `main` @ `7c4b459a` (v2.18, versionCode 25), read from a local snapshot
> (`README.md`, `HANDOFF*.md`, `docs/HANDOFF-v2.15.md`, every `app/www/*.js`, `app/www/app.css`, `index.html`,
> `app/src/**`, `AndroidManifest.xml`, `api/`, `evals/`, `needle-ft/`). Unmerged branches compared against `main`
> through the GitLab API (project 88676660). Port notes were checked against `argus-mobile` `app/src/main/java/com/blaze/assistant/**` (grep).

## 0. Summary

**What A-team is.** "ARGUS Agents" (package `ai.argus.agents`): a no-Gradle Android app, a Java `Activity` hosting one
WebView that serves `app/www/` from `https://app.argus.local`, with a `Native` JS bridge (~50 methods) for HTTP/SSE,
Keystore secrets, the PRoot Ubuntu workspace, PTYs, files, notifications, alarms and a background browser. The whole
UI and agent loop is plain JS (no bundler, ~11k lines). A Cloudflare Worker (`argus-agents-api`, KV) syncs the team and
exposes it over REST and MCP. Chat-first: **every bot is a chat**, jobs and approvals live inline in its thread, a
built-in **Manager** leads the team, each bot works in a real **Ubuntu 24.04** under PRoot.

**Branch reality (important for the merge).**
| Branch | State vs `main` |
|---|---|
| `main` (v2.18, vc 25) | Contains everything from v2.6 → v2.18 **including** v2.14 cloud computer, v2.15 Manager, v2.16 notch/notifications/review/browser. MRs !10 (v2.14) and !11 (v2.15) are still open on GitLab but their content was folded into `main` (the compare API lists their commits as "ahead" only because of squash; every file they add exists on main). |
| `v2.19-chat-drawer` (vc 26, MR !12) | **The only branch with code not on main**: saved chats + ChatGPT-style left drawer on the Chat page (`www/talks.js`, `ICONS.drawer`, `Native.edgeSwipe` gesture-exclusion). |
| `v2.14-cloud-computer`, `v2.15-manager-agent`, `v2.16-notch-chat` | Superseded by main (content merged). Port from main. |
| `v2.10/2.11/2.12`, `v2.7`, `v2.9`, `wip/*`, `fix/refusal-apostrophe` | Merged or stale; 0 commits of unique content. |

**Feature count: 72 features** in 11 groups (A–K) below, plus the design language (section L).

**Recommended port strategy (one line per layer).**
- **UI:** rewrite natively in Compose using A-team's design tokens (section L). Do **not** ship A-team's WebView as a
  whole: its value is in the logic, and Argus-mobile already has native equivalents for the heavy native parts (PTY
  terminal, background browser, PRoot distros, Keystore vault, notifications).
- **Logic:** port JS modules to Kotlin one-to-one where they are pure (models.js, guard.js, manager.js schedule maths,
  jobs.js, memory.js, connect.js). Most of them already have unit tests in `evals/test/*.mjs` and Playwright suites:
  turn those cases into JUnit tests as the spec.
- **Servers: reuse unchanged.** `argus-agents-api` (team sync + MCP), `argus-connect` (`/v1/me/*` per-user connectors
  and skills), the gateway (`/v1/models/live`, `/v1/billing/*`, `/v1/skill-polish`), and the auth worker
  (`blaze-auth`). Argus-mobile already talks to blaze-auth and the gateway.
- **Data migration:** A-team and Argus-mobile are different packages (`ai.argus.agents` vs `com.blaze.assistant`), so
  there is no shared storage. Move users through the API sync (`POST /v1/sync`, account doc `user:<id>`) for bots and
  jobs, and the existing JSON backup (Settings > Data > Copy backup) for the rest. Memory, profile, chats and
  routines are local-only in A-team today, so they need a one-off export path (add one to A-team, or accept the loss).

---

## How to read the entries

Each feature: **What** it does · **Source** paths (relative to the a-team repo) · **Branch** · **Port**: is there an
equivalent in Argus-mobile (paths relative to `app/src/main/java/com/blaze/assistant/`) and how to bring it into
Kotlin/Compose. "AM" = Argus-mobile.

---

## A. App shell, navigation and pages

### A1. WebView shell + `Native` bridge
- **What:** one `Activity` + WebView serving `www/` from `https://app.argus.local`. The `Native` bridge exposes: `http`, `httpStream`/`httpAbort` (SSE batched about 30 ms into `__nativeStream`; dodges the gateway's missing CORS), `cloudHttp`, `browserSignIn`/`cancelSignIn` (PKCE loopback), `connectListen`/`cancelConnect`, `secretSet/Get`, `keySet/keyHas`, `workspaceStatus/Setup/Env`, `wsExec/wsStat/wsReadFile/wsPutFile/wsZip`, `saveToDownloads/shareFile/openFile/previewHtml`, `ptyOpen/Write/Resize/Close/List/Replay`, `notify/agentAvatar/notifyPrefs/notifyPreview/overlayAllowed/overlaySettings/haptic`, `cloudLease/cloudState/cloudSsh`, `routineSync/routineFired/timeZone`, `brCall` (browser), `clipboardSet/Get`, `openUrl`, `platform`, `setBars`, and (v2.19) `edgeSwipe`. Root is a FrameLayout (background browser at index 0, page on top). `singleTask` activity; notification taps carry `argus_agent`. Cached thread pool.
- **Source:** `app/src/ai/argus/agents/MainActivity.java`, `app/AndroidManifest.xml`, `app/build.sh`.
- **Branch:** main (+ `edgeSwipe` on v2.19).
- **Port:** AM is native Compose (`MainActivity.kt`, `BlazeApp.kt`). Drop the bridge: each method maps to an AM service (HTTP: `connect/Http.kt`; secrets: `agent/Secrets.kt`/`SecretVault.kt`; PTY: `terminal/TermSessions.kt`; browser: `browser/BrowserEngine.kt`; notifications: `notify/Notifier.kt`). Keep the bridge **only** if a transitional "classic A-team" WebView screen is wanted for a release; not recommended.

### A2. Navigation model, back stack, reopen where you left off
- **What:** `go(tab, sub, talk, back)` with tabs `home | agents (bot chat) | tasks (Jobs) | graph (Compose) | models (Settings)`; a NAV stack so Back returns where you came from (Chat > Settings > Back = Chat); the last page (Chat page, bot chat, list, Jobs, Compose; never Settings) restored on launch (`argus.last.v1`). Rendering is DOM-morphing (`morph()`/`patchHTML()`) so nothing jumps while typing.
- **Source:** `www/app.js` (`go`, `NAV`, `render`, `morph`, `lastPage`).
- **Branch:** main (v2.16b, v2.16c).
- **Port:** Compose Navigation (AM has `agent/ChatNav.kt`, `ui/BackRule.kt`, `agent/SwipeNav.kt`). Save the last route in DataStore. The "no jumps" rule maps to stable keys in `LazyColumn` and `imePadding()` with fixed-height chrome.

### A3. Notch + Bots | Chat pill
- **What:** a wordless top-centre grip (46×22 black capsule); pull down (or tap) to open a pill with two modes: **Bots** (team home) and **Chat** (plain chat with the Manager). Spring physics, drag follows the finger (`--pull`).
- **Source:** `www/notch.js`, `index.html` `#notch`, `app.css` "v2.16: notch".
- **Branch:** main (v2.16).
- **Port:** no equivalent in AM (its "notch" hits are status-bar awareness). New Compose component: `Box` with `draggable(Orientation.Vertical)`, `animateFloatAsState(spring(dampingRatio≈0.7))`, a two-segment pill. This is the top-level switch between "team of specialists" and "talk to the Manager". Strong candidate for the unified app's primary mode switch.

### A4. Chat page (TALK mode, bound to the Manager)
- **What:** Claude-style page: no intro/job cards; empty state is a centred orb plus a serif greeting by time of day ("Morning. What's first?"); AI replies have **no bubble** (text sits on the page), user messages keep ink bubbles; New chat button (square-pen) gives a fresh model context.
- **Source:** `www/app.js` (`talkHTML`, `TALK`), `app.css` "v2.16: Chat page", "AI replies sit on the page".
- **Branch:** main (v2.16, v2.16b).
- **Port:** AM's `ui/ChatScreen.kt` / `ui/ChatBubbles.kt` already do messaging-style chat. Add a "page" variant (no assistant bubble, serif greeting). The unified app's Manager chat should be this page.

### A5. Chat drawer: saved chats (unmerged)
- **What:** real saved chats on the Chat page. `S.talks = {v, cur, list:[{id, c, title?, named?, pin?, unseen?, del?}]}`; every Manager message carries `sid`. Left drawer (button with a custom three-stroke icon, or left-edge swipe with Android gesture exclusion): search over titles and message text with snippets, Pinned, Today / Yesterday / Previous 7 days / Older, unseen-reply dot, long-press Rename / Pin / Copy / Delete (with Undo); profile badge bottom-left, New chat bottom-right. A reply keeps streaming into its own chat when you switch away. History and context only include the chat being answered (`TALKS.inCtx`). Migrates earlier Manager history into chats. Local only.
- **Source:** branch `v2.19-chat-drawer`: `app/www/talks.js` (28 KB), `app.js`, `chat.js`, `icons.js` (`drawer`), `MainActivity.edgeSwipe`, `app/tests/v219.test.js`, HANDOFF.md "Done in v2.19".
- **Branch:** **v2.19-chat-drawer only (MR !12, not merged).**
- **Port:** AM has `agent/ChatStore.kt`, `agent/ChatIndex.kt` (FTS search), `agent/Projects.kt`, `agent/LongChat.kt`, but deliberately runs "one mind" (one continuous conversation). Decide in slice 5 whether the Manager gets multiple chats; if yes, use `ModalNavigationDrawer` + `ChatIndex` FTS and port the grouping/undo rules from `talks.js`. Use `View.setSystemGestureExclusionRects` for the edge swipe, as in `edgeSwipe`.

### A6. Home: the team as chats
- **What:** big team name (`h1.big`), subline "N bots + Manager · N jobs today · N overdue"; an ink **"N need you"** banner (overdue/approval jobs, names of who); a **pins** row of large orbs (long-press to pin) with red overdue dots; then the chat list (`crow`): Manager first (gold tint + "Lead" badge), then bots by last activity, each with avatar, name, time, one-line preview (red for overdue, green for done) and a blue unread dot; an "Unassigned" row; an "All jobs" button. Search mode filters bots and jobs. Empty state: three orbs, "Build your team", New bot / Demo team.
- **Source:** `www/app.js` (`homeHTML`, `crow`, `preview`, `needYou`, `pinIds`), `app.css` "home".
- **Branch:** main.
- **Port:** AM's home is `home/HomeBoard.kt`/`HomeGrid.kt` (news/widgets board, different purpose). Build a new `TeamHome` composable (LazyColumn of rows). In the unified app this is the **specialists list** (one row per life-domain agent) and should be reachable in one tap from the notch.

### A7. "+" sheet (New)
- **What:** bottom sheet menu: New bot, New job, All jobs, Terminal, Compose, Models & settings. Composer's left "+" opens it on Home; inside a bot chat "+" opens attachments.
- **Source:** `www/app.js` (`plusSheet`), `chat.js` (attach menu).
- **Branch:** main.
- **Port:** `ModalBottomSheet` with the `.menu` row style (44 px round icon tile, title, subtitle). AM has `ui/Attach.kt` for attachments.

### A8. Theme: Match phone / Dark / Light
- **What:** segmented control; `prefers-color-scheme` follow; all tokens switch via `data-theme`. Terminal is always dark.
- **Source:** `www/app.js` (`applyTheme`), `app.css`.
- **Branch:** main.
- **Port:** AM has `ui/AppTheme.kt` + `ThemeMode` (light/dark everywhere, "system" = dark-first). Re-skin with A-team tokens (section L); keep AM's ThemeMode plumbing.

### A9. First-run setup
- **What:** two steps: nickname, then a workspace wait screen (orbit loader, progress bar, live line) while Ubuntu installs in the foreground service. Purges legacy Connections tokens on start.
- **Source:** `www/setup.js`, `app.css` "first-run setup steps", "workspace setup".
- **Branch:** main (v2.18 removed the old Connections step).
- **Port:** AM has `setup/SetupGate.kt`, `SetupPlan.kt`, `SetupRunner.kt` (permission rings). Add the nickname step and reuse AM's Linux-ready flow (`terminal/LinuxReady*.kt`).

### A10. Sheets, toasts, long-press, empty states
- **What:** one bottom-sheet host (`#sheet-wrap`, grabber bar, scrim .35, slide-up .2 s), toasts (frosted, bottom, above composer; move to top when a sheet is open), long-press to pin or open menus (`.pressing` scale .97), centred empty states with a 30 px faded icon.
- **Source:** `www/app.js` (`openSheet`, `toast`), `app.css`.
- **Branch:** main.
- **Port:** Material3 `ModalBottomSheet` + custom `Snackbar` host styled per section L; `Modifier.combinedClickable(onLongClick)`. AM has `ui/BubbleMenu.kt`, `GlassKit.kt` pieces.

---

## B. Bots and the team

### B1. Bots as chats (bot model)
- **What:** a bot = `{id, name, email, role, portfolio, notes, prompt, color, face, lead (reports to), gm (Gemini model), managed_by, x, y, createdAt, _chat, _files, _seen}`. Each bot chat opens with an **intro** (xl orb, name, "role · email", model line with a health dot, portfolio), a **jobs card** (open jobs with checkboxes and due labels), a **"Needs your ok"** card for overdue jobs (Tomorrow / Done), then the thread. A `$ cmd` message runs straight in the bot's shell.
- **Source:** `www/app.js` (`addAgent`, `agentHTML`, `agentSend`), `chat.js`.
- **Branch:** main.
- **Port:** AM's `agent/Specialists.kt` (focused sub-agents with their own prompt and tool subset) and `agent/Personas.kt` are the nearest concept, but they are not user-visible chats. Model a `Bot` entity (Room or JSON store) with A-team's fields plus AM's specialist tool subset; one chat thread per bot in `ChatStore`. This becomes the life-domain specialist.

### B2. Bot editor sheet
- **What:** Name, Email, Role, Portfolio, Instructions, **Look** (6 orb ring styles × 8 palette colours + 2 greys + any colour via a conic swatch), Gemini model picker, Reports to, Memory button (N private notes), Delete. Manager gets a read-only card instead.
- **Source:** `www/app.js` (`agentSheet`, `mgrSheet`), `gemini.js` (`pickHTML`).
- **Branch:** main.
- **Port:** Compose sheet with A-team field style (52 px fields). AM has `ui/ColorWheel.kt` for the any-colour picker.

### B3. Orb avatars + Manager sphere + app icon
- **What:** procedural SVG "glass orb with orbit ring" per bot (`botSVG(face, colour)`: radial gradient body, halo, inner glow, rim, specular ellipse, tilted back/front rings; faces Orbit, Tilt, Steep, Pure, Twin, Polar). The Manager is a raster blue "A" sphere (`img/manager.png`) with a blue drop-shadow glow. App icon: adaptive A-orb + monochrome layer.
- **Source:** `www/app.js` (`BOT_FACES`, `botSVG`, `av`), `www/img/manager.png`, `app/res/mipmap-*`, `res/drawable/ic_fg.xml`.
- **Branch:** main (v2.16).
- **Port:** rewrite `botSVG` as a Compose `Canvas` (radial `Brush`es + `drawArc` for rings, rotate by tilt); deterministic face from name hash like `agentFace`. AM has `ui/ArgusMark.kt`/`ArgusBrand.kt` for the brand mark: align the Manager sphere with it.

### B4. Built-in Manager bot
- **What:** fixed id `manager`; seeded on first run/upgrade, re-seeded if missing or corrupt; cannot be deleted (UI **and** storage refuse); stored last in `S.agents` but shown first with a gold **Lead** badge; since v2.16c name, role, instructions, model and look are **locked** (`MANAGER.ensureState` restores them); every other bot is `managed_by: manager`; UI-created bots leave a note for the Manager without a model call. Default prompt and intro written "in our own words". One getting-to-know-you question per conversation; intro shown once.
- **Source:** `www/manager.js` (`ensureState`, `ensure`, `insert`, `canDelete`, `noteNew`, `DEFAULT_PROMPT`, `INTRO`), `app.js` (`removeAgent`), `docs/HANDOFF-v2.15.md`.
- **Branch:** main (v2.15 + v2.16c lock).
- **Port:** AM has a single assistant persona ("one mind", `agent/Mind.kt`, `OnePersona.kt`). In the unified app **the Manager = AM's main assistant**, upgraded with A-team's team-management tools. Keep the invariants (undeletable, locked identity) as unit tests.

### B5. Manager tools
- **What:** `agents_list`, `agent_create`, `agent_update` (refuses the Manager), `agent_delete` (asks first; needs a yes in the next message or the Delete button; never `manager`), `agent_delegate` (creates a job for a bot with the relevant profile lines attached), `agent_status`, `retry_job` (any job), plus profile and routine tools (I1, I2).
- **Source:** `www/manager.js` (`t_*`), `guard.js` (`MGR_TOOLS`), `chat.js` (`tools(a)`, exec branch).
- **Branch:** main.
- **Port:** add as tools in AM's tool registry (`agent/ToolRouter.kt`, `ToolCalls.kt`, `CommandRegistry.kt`). The delete confirmation maps to AM's `agent/ConfirmGate.kt` + `ui/ConfirmCard.kt`. Delegation maps onto AM's `orchestra/Orchestrator.kt` / `BgAgents.kt` for running the delegated work in the background.

### B6. Bot self-edit (`update_agent`) and team notes (`note_team`)
- **What:** a bot can change its own name/role/email/portfolio (or a report's with `for:`); team notes go to the shared team memory with an Undo card.
- **Source:** `www/chat.js`, `guard.js` schemas, `memory.js`.
- **Branch:** main (v2.6).
- **Port:** tools on the Bot entity; Undo via AM's `agent/UndoCenter.kt`.

---

## C. Jobs, approvals and the Compose graph

### C1. Jobs model, due parsing, Jobs page
- **What:** job = `{id, title, agentId, due (YYYY-MM-DD|null), status todo|done, run queued|running|waiting|done|failed|blocked, priority low|normal|high, notes, deps[], fails[], attempts, createdAt, doneAt}`. Natural due words (today, tomorrow, weekdays, this/next week, "in N days"). Jobs page: segmented filters with counts (Needs you, Today, Tomorrow, Upcoming, To-do, Overdue, Done, All), search, rows with checkbox, assignee mini-orb, "waiting" and "high" tags, due label (red when overdue), FAB "+".
- **Source:** `www/app.js` (`addTask`, `dueFromText`, `bucket`, `tasksHTML`, `taskRow`, `taskSheet`).
- **Branch:** main.
- **Port:** AM has `agent/TaskKeeper.kt`, `TaskLine.kt`, `TaskMemory*.kt`, `ScheduleDay.kt` (assistant tasks, not a team board). New `Job` entity + `JobsScreen`; reuse AM's date parsing where it exists, keep A-team's buckets.

### C2. Job queue that actually runs
- **What:** jobs made by the user, a bot (`add_job`) or the home box are queued; the bot receives "You are assigned job #id …" in its chat, works it with tools, closes it with `done_job`. **Waits on** (`deps`) holds a job until blockers are done. One chat streams at a time (global queue), people's messages go before queued jobs; runaway guard 12 automatic runs per 10 min; runs cut by app close are marked failed on next start; add_job duplicating the current job or the message just given is dropped and the bot is told to do the work.
- **Source:** `www/jobs.js` (`QUEUE`), `chat.js` (`request`, brief), `app.js` (`queueJob`).
- **Branch:** main (v2.6, v2.7).
- **Port:** AM has `agent/BgAgents.kt` (background agents), `orchestra/ToolScheduler.kt`, `KeyedLocks.kt`, `terminal/JobKeeper.kt` (foreground service). Implement the queue as a coroutine supervisor in a foreground service so jobs run with the app closed (A-team's documented gap) and **in parallel per bot** (another A-team gap).

### C3. Approvals inline ("Needs your ok", "N need you")
- **What:** overdue/awaiting jobs surface as an ink banner on Home and as a card in the bot's chat with Tomorrow / Done buttons; Manager proactive suggestions and risky actions (delete bot, spend, send) wait for a yes card.
- **Source:** `www/app.js` (`needYou`, `agentHTML` okc card), `manager.js` (`confirmDelete`, `card*`, `risky`), `jobs.js` (`QUEUE.start` holds unconfirmed proactive jobs).
- **Branch:** main.
- **Port:** AM's `ConfirmGate.kt`/`ConfirmCard.kt` + `notify/Notifier.kt` actions. In the unified app, a single **Approvals inbox** fed by all specialists, with the inline cards kept in each thread.

### C4. Verified `done_job`
- **What:** deliverable jobs need the files present and non-empty, a syntax check (HTML inline scripts compile, JSON parses, JS compiles, `py_compile`, `bash -n`) and the file attached; others need a real message to the user. Failed checks are fed back; a job still unverified ends **blocked**, never done. Done notifications only after verification.
- **Source:** `www/chat.js` (`verifyJob`, `checkFile`, `DELIVERABLE`).
- **Branch:** main (v2.7).
- **Port:** AM has `agent/ToolVerify.kt`, `VerifyPolicy.kt`, `VerifyProbes.kt`, `ActionProof.kt`. Add A-team's file checks as a verify policy for job completion.

### C5. Failed jobs log, retry and backoff
- **What:** `t.fails` (last 8, time + error) and `t.attempts`; network/TLS/gateway errors retry after 1, 5, 15 min; others wait; bots see FAILED JOBS in their prompt; `retry_job` tool; `add_job` with a failed job's title reopens it; job sheet shows the log and Retry. Plain-words TLS errors (captive portal: "log in to the Wi-Fi").
- **Source:** `www/jobs.js` (`fail`, `retry`, `BACKOFF`, `failedBlock`), `chat.js` (`netErr`).
- **Branch:** main (v2.16d).
- **Port:** AM has `agent/RetryGuard.kt`, `Recovery.kt`, `PlainError.kt`. Use WorkManager backoff or the job supervisor's own timers.

### C6. Background jobs with completion pings (partial, not wired)
- **What:** a supervisor script starts a long command once, waits ~25 s, reports done or running; persisted job list, polling, an automatic system-event ping that resumes the same chat, `job_status`/`job_wait`/`job_kill`, an "N running in background" chip, Stop kills a turn's jobs. **Not wired**: the run path never calls `BGJ.wrap`; schemas gated off; no Java `BgJobService`.
- **Source:** `www/bgjobs.js`, `guard.js` (`JOB_TOOLS`), `docs/HANDOFF-v2.15.md` item 10 + "Next steps" 1.
- **Branch:** main (WIP code present, disabled).
- **Port:** AM already has this done natively: `terminal/JobKeeper.kt` (foreground service, time budget, finish notification) and `agent/BgAgents.kt`. Use AM's; port only the "resume the same chat on completion" rule.

### C7. Compose graph (team org chart + job dependencies)
- **What:** full-screen SVG canvas: agent nodes and job nodes (fixed 184×84), pan/pinch-zoom/fit, drag to move (positions `x, y` stored and synced), connect ports to create **reports-to** edges (dotted, "lead") or **waits-on** edges (dashed, "dep"), ghost edge while dragging, edge menu to remove. Agent nodes show status dot (idle/working/waiting), current job, model, done-of-total bar; job nodes show state, assignee, last update, elapsed. Tap agent = open chat; tap job = job sheet; toolbar with +Job / +Bot / fit and a legend.
- **Source:** `www/app.js` (`Graph`, `graphShellHTML`, `NW/NH`), `app.css` "composer", "compose nodes v2.6".
- **Branch:** main.
- **Port:** AM has no team graph; `orchestra/Blueprint.kt` is a run-time dataflow graph (could be visualised with the same component). AM also has `ui/DiagramRender*.kt`. Rewrite as a Compose `Canvas` with `transformable()` + `detectDragGestures`, cubic Béziers for edges, `PathEffect.dashPathEffect` for dep/lead styles. Optional view of the specialist ecosystem (Manager → specialists → jobs) and of live orchestration runs.

---

## D. Chat engine

### D1. Streaming chat with interleaved tools, parts model, Stop
- **What:** tokens stream into one message; replies are ordered **parts** (text, tool, view, review, attach…); up to **40 steps** per reply (`S.settings.maxSteps` override); Stop is instant (`ctl.stopP` races every await: stream, exec, cloud, review); running commands cancelled (`pkill` + `tmux wait-for -S`); `settleStale()` marks tool parts left running by a closed app as stopped. The model that answered stored as `msg.via`/`msg.model`.
- **Source:** `www/chat.js` (`CHAT.reply`, `stream`, `SSE`, `stop`, `MAX_STEPS`), `workspace.js` (`live`, `cancelAll`), `MainActivity` (`httpStream`).
- **Branch:** main (v2.6 → v2.16b).
- **Port:** AM's agent loop is `agent/Agent.kt` (5.8k lines) + `LlmClient.kt` + `StreamGate.kt` + `StopResume.kt`, and also server-side runs via `CloudRuns.kt` (`/v1/runs`). Do not port A-team's loop; port its **rules** (step budget, instant stop, stale settle) into AM's loop.

### D2. Native tool calls + fenced-block fallback
- **What:** OpenAI `tools` on every request (`tool_choice: auto`); streamed `tool_calls` assembled; when a model writes fences instead, `normFences()`/`guardLine()` repair mid-sentence fences, commands on the fence line, unclosed fences, aliases (`exec`, `cmd`, `add-job`…) and `$ ` prompts. Retry once without tools on a 400. Cut-off JSON args are not run. Tool cap 128 (connectors up to 96 first).
- **Source:** `www/chat.js` (`TOOL_NAMES`, `normFences`, `scanBlocks`, `SSE.tool`, `tools`), `guard.js` (`toolSchemas`, `fromToolCall`, `normalize`).
- **Branch:** main (v2.6, v2.9, v2.18).
- **Port:** AM has `agent/ToolCalls.kt`, `TextCalls.kt` (text-call parsing), `ToolLeak.kt`. Merge A-team's fence-repair cases into `TextCalls` tests.

### D3. Tool cards and grouped steps
- **What:** each tool renders a collapsible card (icon, one-line title, status spinner/check, chevron; body = `$ cmd` + output tail, max 260 px); since v2.16c one live line while working, then **one group** ("4 edits, 2 reads, 1 command") with sub-groups by kind (read/edit/command…); attach, save, cloud view, review and auto-memory cards stay outside groups; Undo on memory writes.
- **Source:** `www/chat.js` (`cardHTML`, `groupHTML`, `kindOf`, `KINDS`, `regroup`), `app.css` "tool cards", "grouped tool steps".
- **Branch:** main.
- **Port:** AM has `agent/StepPill.kt`, `ToolCards.kt`, `ui/AgentCard.kt`, `ui/CalmSteps.kt`. Re-skin AM's step UI with A-team's `.tc`/`.tg` card style (section L) and its grouping labels.

### D4. Markdown renderer
- **What:** own renderer: paragraphs, headings, lists and task lists, tables (scrollable), code blocks with a header and Copy button, inline code, quotes, hr, links; streaming caret.
- **Source:** `www/chat.js` (`MD`), `app.css` `.mdb`, `.cb`, `.tbl`.
- **Branch:** main.
- **Port:** AM already renders rich replies (`ui/AnswerCards.kt`, `agent/RichRepair.kt`, `ui/ChatLinks.kt`). Restyle to A-team's code-block and table look.

### D5. Attachments in chat
- **What:** + → Photo / Camera / File; images downscaled to 1600 px JPEG and sent multimodal (text-only models get a description); text files < 200 KB inlined; every file saved to `/root/uploads`; attachment chips above the composer.
- **Source:** `www/chat.js` (`takeAtt`, `TEXT_EXT`, `userContent`), `app.css` "composer while streaming, attachments".
- **Branch:** main (v2.6).
- **Port:** AM has `agent/Attachments.kt`, `ui/Attach.kt`, `ui/CameraCapture.kt`. Add the "also copy into the bot's workspace" rule.

### D6. Context window, summary, time and time zone
- **What:** sends `blaze_context: "full"` (gateway's opt-out from its relevance pruning, which was the root cause of lost context); last 24 messages / 48k chars verbatim + running summary of older turns; tool turns flattened to text when a call goes without tools; a local `Now:` line in every system prompt; a stated city/zone saved to settings and profile and passed as `TZ` into PRoot; bulleted interrogations bounced once.
- **Source:** `www/chat.js` (`history`, `system`, `WINDOW`, `BUDGET`), `guard.js` (`flattenToolTurns`, `summarizeTurns`, `interrogates`), `manager.js` (`nowLine`, `noteTimezone`), `Workspace.java` (`tzEnv`).
- **Branch:** main (v2.15).
- **Port:** AM has `agent/TurnContext.kt`, `SessionTrim.kt`, `AutoCompact.kt`, `LongChat.kt`. **Action item:** check AM's gateway calls send `blaze_context: "full"` (or that `CloudRuns` is unaffected) — grep found no `blaze_context` in AM.

### D7. Home box / command router (Needle → gateway → rules)
- **What:** the composer on Home ("Tell the team…") applies clear commands instantly (add bot, add job, complete, reschedule, remove) through: exact structured parse → **Needle** on device → the gateway model when Needle finds nothing (online and signed in) → quick regex rules. Optional plan preview sheet ("Apply commands without the preview step"). Anything else goes to the Manager's chat, or `@Name` to that bot (mention autocomplete). Inside a bot chat, command-ish messages (`CMDISH`) are tried as commands first.
- **Source:** `www/app.js` (`interpret`, `structuredParse`, `cardParse`, `needleCalls`, `viaGateway`, `ruleParse`, `toActions`, `applyActions`, `planSheet`, `CMDISH`, `agentSend`), `chat.js` (home box mentions).
- **Branch:** main.
- **Port:** AM has `agent/FastLane.kt`, `FastActions.kt`, `QuickRules.kt`, `SlashCommands.kt`, `composer/Fuzzy.kt`, `SpotlightSearch.kt`. Port the action set as fast actions on the Bot/Job store; `@mention` routing to specialists is new in AM.

### D8. Sign-in banner and offline replies
- **What:** signed out → one sign-in card per chat (flag, removed when a reply works); gateway down → a local reply that jobs can still be added offline.
- **Source:** `www/chat.js` (`signinHTML`, `pushSignin`), `app.js` (`agentSend`).
- **Branch:** main (v2.7).
- **Port:** AM has `agent/SignInGate.kt` and `OfflineBrain.kt` (local model chat). Use AM's.

### D9. Reply hygiene: no filler, unbacked claims, notification summaries
- **What:** stock sign-offs dropped; a reply only continues after real results; `GUARD.unbackedClaims` flags "I ran… / tests passed / exit code 0" when no tool ran (first time hidden and re-asked, then a "not verified" note); notification body = first sentence(s) with fences and meta-talk stripped (160 chars).
- **Source:** `www/chat.js` (`FILLER`, `dropFiller`), `guard.js` (`unbackedClaims`, `CLAIM_MSG`), `jobs.js` (`NOTIF.summary`, `META`).
- **Branch:** main (v2.7, v2.9).
- **Port:** AM has `agent/ClaimGuard.kt`, `ClaimCheck.kt`, `ReplyClean.kt`, `ToolTruth.kt`. Merge A-team's claim patterns as extra test cases.

### D10. Workspace snapshot, "LOOK FIRST", working set
- **What:** before each reply the bot gets a fresh `/root` listing (project folders, newest files) and a rule to look before asking "where is it?" (bounced once by `guard.asksInsteadOfLooking`); the Manager sees files every bot made; paths a bot writes/attaches/saves form a per-bot working set (last 6 in the prompt) so "it" resolves.
- **Source:** `www/workspace.js` (`SNAP_CMD`, `snapFresh`), `files.js` (`FILES`), `guard.js`.
- **Branch:** main (v2.7, v2.16c).
- **Port:** AM has `agent/Orient.kt`, `ProjectDocs.kt`, `ScreenMemory.kt`. Add a per-bot workspace snapshot to the prompt builder (`PromptParts.kt`).

---

## E. Guard layer and code review

### E1. `guard.js` — the system around the model
- **What:** (1) tool-block normalize/validate: no-op/fake/unsafe run blocks are **not run** (destructive paths outside `/root` `/tmp`, fork bombs, raw devices, Android paths, pasted output), `sudo` stripped, warnings for foreground servers, `curl|sh`, force push; (2) `StepGuard` stops the same command+result a 3rd time; `feedback()` gives exit code, stderr tail and a targeted hint; (3) `CompletenessGate`: on jobs, no "done" until a passing build/test/demo ran after the last edit (2 nudges, auto `checkScript`); (4) `precheck`/`postcheck` refusals for clearly harmful asks with a short refusal + safe alternative, look-alikes allowed; (5) hardened prompt sections (`PROMPT`, `CLOUD_PROMPT`, `NARRATE_PROMPT`); plus `cloudSteer`, `detachServers` (servers and `cmd &` detached with a log), `apply_patch` shim, `flattenToolTurns`, `toAnthropic`, `isNarration` (not wired). Dual browser/CommonJS module, tested by `evals/test/*.mjs`.
- **Source:** `www/guard.js` (759 lines), `evals/`.
- **Branch:** main (v2.8 → v2.16c; v2.10/v2.11 guard fixes merged).
- **Port:** AM has many equivalent guards (`agent/CommandPolicy.kt`, `LoopGuard.kt`, `ProgressGate.kt`, `SecretGuards.kt`, `SearchGuard.kt`, `EchoGuard.kt`) but **no completeness gate** (grep: none). Port `checkRun` rules and `CompletenessGate` to Kotlin as pure objects; port the eval cases (`evals/test`) into JUnit.

### E2. Built-in code review
- **What:** when a bot changed code and is about to finish (after the completeness gate): static pass in the workspace (secrets, eval/`shell=True`/pickle/yaml.load/innerHTML/SQL strings/TLS off/`curl|sh`/`chmod 777`, installed linters shellcheck/ruff/pyflakes/bandit/eslint, smells) → strict reviewer model call (temperature 0) returning blocker/major/minor/nit findings tagged quality or safety with file:line and fix → blockers/majors go back to the bot (2 rounds, 3 on Strict; bot may dispute "not a bug: R3 because…"). One review card in chat (verdict, counts, findings). Settings > Code review.
- **Source:** `www/review.js`, `app.css` "code review card", `tests/review.test.js`.
- **Branch:** main (v2.16).
- **Port:** no equivalent in AM (only reviewer nodes in `orchestra/Pipelines.kt`). Port as a post-step in the coding specialist's loop; the static scan is a shell script run in PRoot (reuse `review.js` `script()` text verbatim).

---

## F. Models and on-device intelligence

### F1. Live gateway model picking
- **What:** no picker and no fixed chain since v2.13: `GET /v1/models/live` (account token; models probed healthy in ~6 h that the plan allows, fastest first, never balance-billed) refetched every 5 min; `MODELS.pick` takes the first fit (tools-verified for agent runs, vision for images, else chat lane), sent as `X-Blaze-Prefer` and kept for the turn; on failure the gateway answers with the next model (`x-blaze-prefer-status: fallback`, `x-blaze-route`), the app follows it, adds the failed pick to `X-Blaze-Avoid`; Auto (`blaze-auto`) only when the lane fails. Settings > Models shows up to 6 live models with speed, success rate and which lane uses them. Wire names centralised in `MODELS.WIRE`.
- **Source:** `www/models.js`, `www/app.js` (`GW`, `chainHTML`), `MainActivity.gwHeaders`, `evals/lib/gateway.mjs`.
- **Branch:** main (v2.13).
- **Port:** AM has `agent/Cloud.kt` (gateway, already sends `X-Blaze-Prefer`), `Providers.kt`, `ModelRoute.kt`, `TurnModel.kt`, `Failover.kt`, `ModelCatalog.kt`, `CloudRuns.kt`. AM does **not** call `/v1/models/live` (grep: none). Port `models.js` (98 lines, pure) to Kotlin as `LiveModels` and plug it into `ModelRoute`.

### F2. Google Gemini with the user's key
- **What:** Settings > Models: AI Studio key (Keystore, never logged), Load models (`/v1beta/openai/models`), hint about the Google AI Pro credit; per-bot Gemini model (`a.gm`); streams with native tools; on 429/quota one message, cool-down (Retry-After, 60 s, 1 h daily), gateway answers meanwhile. A bot on Gemini can chat without an ARGUS sign-in.
- **Source:** `www/gemini.js`, `CloudGuard.authFor` (key added in Java only for its host).
- **Branch:** main (v2.14).
- **Port:** AM supports Gemini as a provider (`agent/Brains.kt`, `LlmClient.kt`, `FreeRouter.kt`, `ModelCatalog.kt` Gemini native list) and per-provider keys (`agent/KeyRing.kt`). Add the **per-bot** model choice and the cool-down rule.

### F3. Needle 3 on-device command parsing (Cactus)
- **What:** Cactus Needle 3 (121M params, 35 MB `needle3.cact`, ~30 MB RAM, fully offline) in a Web Worker via `cactus.wasm`; function-calls against 5 tools (`add_agent`, `add_task`, `complete_task`, `reschedule_task`, `remove`) with regex triggers; splits multi-clause commands. Settings > On-device card (state, load).
- **Source:** `www/cactus.js`, `cactus.wasm`, `needle3.cact`, `needle-worker.js`, `app.js` (`NEEDLE_TOOLS`, `Needle`).
- **Branch:** main.
- **Port:** AM has `agent/OfflineBrain.kt` (Qwen2.5 0.5B via LiteRT/MediaPipe, ~0.5 GB, for offline chat) and fast lanes; it has no Cactus runtime. Options: (a) run Needle natively via Cactus's Android SDK (preferred; same model file, no WebView), (b) host `cactus.wasm` in a headless WebView/JS engine (works today, heavier), (c) map the 5 actions onto `OfflineBrain` with constrained JSON output. Keep the fallback order exact parse → Needle → gateway → rules.

### F4. Needle fine-tune data
- **What:** `gen.py` builds 2,090 synthetic ARGUS command examples (`data.jsonl`; splits train 1,756 / val 167 / test 167; `focus.jsonl` 96 hard cases); `tools.json` schema; Colab notebook `ARGUS-Needle-finetune.ipynb`; `run_platform.py`; `agentic/` adds an agentic tool set (`tools_agentic.json`) and its own splits.
- **Source:** `needle-ft/`.
- **Branch:** main.
- **Port:** keep the dataset in a `ml/needle-ft/` folder of the unified repo (or leave in a-team). Regenerate with the unified app's command set (life-domain specialists, connectors) before the next fine-tune.

### F5. Plan display
- **What:** Settings > ARGUS account shows plan (Basic / Standard / Pro) and days left from `/v1/billing/me`; Plans sheet lists prices from `/v1/billing/methods`; paying happens in ARGUS mobile.
- **Source:** `www/account.js` (`plan`, `planLine`, `plansSheet`).
- **Branch:** main.
- **Port:** AM has the real checkout (`connect/Billing.kt`, `Entitlements.kt`, `ui/CheckoutPage.kt`). Use AM's; nothing to port.

---

## G. Workspace, terminal, files, browser, cloud computer

### G1. Ubuntu 24.04 workspace under PRoot
- **What:** first run downloads the proot-distro v4.18.0 Ubuntu rootfs, untars in pure Java (xz-1.9), installs python3/pip/git/build tools/tmux in a dataSync foreground service ("Workspace ready" notification); Termux proot 5.1.107 builds as `lib*.so`; `exec()` retries with `PROOT_NO_SECCOMP=1`; `hostLinks()` re-points `libtalloc.so.2` each command (fix for "CANNOT LINK EXECUTABLE" after updates); TZ passed in; ~800 MB on disk; arm64 only. Bots run commands in a **tmux window named after the bot** (session `argus`), with the tmux ACL fix (`server-access` for the app uid, `start-server` with config before any client) and a plain-shell fallback (`NOSERVER`/`NOTMUX`). Settings card shows status.
- **Source:** `app/src/.../Workspace.java`, `WorkspaceService.java`, `Pty.java` (`HOST_SCRIPT`, `ensureTmux`), `www/workspace.js`, `app/lib/arm64-v8a/`, `app/libs/xz-1.9.jar`, tests `PtyArgvTest.java`, `WorkspaceTest.java`.
- **Branch:** main (v2.6 → v2.12).
- **Port:** AM has the same stack natively: `terminal/Linux.kt` (Alpine default), `Distros.kt` (**Ubuntu**, Debian, Arch, Fedora, Manjaro from proot-distro), `LinuxReady*.kt`, `LinuxDoctor.kt`, `LinuxPm.kt`, `JobKeeper.kt`. Use AM's runtime with Ubuntu as the agents' distro; port the tmux-per-bot windows, the uid/ACL fix and the `hostLinks` relink if AM lacks them (AM has no tmux: grep none). Per-bot isolation: one shared rootfs with `/root/<bot>` folders (A-team's model) is cheapest; separate rootfs per bot costs ~800 MB each.

### G2. Terminal (PTY + xterm.js)
- **What:** full-screen dark terminal: tabs (one per tmux window, dead tabs struck through), extra-keys rows (Esc / - | ~ PgUp ↑ PgDn; Tab Ctrl Alt tmux-prefix Home ← ↓ → End), copy/paste, font zoom, sessions outlive the view and WebView reloads (replay buffer). Termux terminal-emulator 0.118.0 JNI (`libtermux.so`) for forkpty; xterm.js 6.0.0 + fit addon draw it.
- **Source:** `www/terminal.js`, `www/vendor/xterm/`, `app/src/.../Pty.java`, `com/termux/terminal/ArgusJni.java`, `app/libs/termux-terminal-emulator-0.118.0.jar`.
- **Branch:** main (v2.6).
- **Port:** AM has a native terminal (`terminal/TermSessions.kt`, `Terminal.kt`, `BlazeTerminalActivity.kt`, `TerminalSheet.kt`, `ui/TermPalette.kt`) on the same Termux library. Use AM's; add "attach to bot's tmux window" tabs and A-team's extra-keys set.

### G3. Files: attach, save, preview, share, zip, working set
- **What:** `attach` tool posts attachment cards in the bot's reply: images inline, HTML with **Preview** (full-screen Dialog WebView serving `https://preview.argus.local/<path>` from the rootfs, only `/root` and `/tmp`, no JS bridge), Open / Share (read-only `content://ai.argus.agents.files/<token>/<name>`) / Save (MediaStore Downloads/ARGUS); folders → Zip (200 MB cap). `save` tool copies to Downloads. Bots are told to attach deliverables automatically.
- **Source:** `www/files.js` (`ATT`, `FILES`), `app/src/.../WsFiles.java`, `ShareProvider.java`, `MainActivity.previewHtml`.
- **Branch:** main (v2.7).
- **Port:** AM has `agent/SaveFile.kt`, `SaveFileAndroid.kt`, `agent/Delivery.kt`, `ui/DesignServe.kt`/`DesignView.kt` (serving generated pages), `ui/RichPreview`. Port `attach`/`save` as tools on top of these; keep the preview sandbox rules.

### G4. `write_file` tool
- **What:** native tool: `path` + `content`, only under `/root` or `/tmp`, no `..`, ~60 KB per call (bigger: first part then `cat >>`), written with `Native.wsPutFile` (no shell); counts as a code edit for the gate and joins the working set.
- **Source:** `www/chat.js` (`writeFile`), `guard.js` (`WRITE_MAX`), `MainActivity.wsPutFile`.
- **Branch:** main (v2.9).
- **Port:** AM has no `write_file` (grep none; it writes via shell/run_code). Add it as a direct file write into the rootfs (`Workspace.guestFile` path rules).

### G5. The bots' own browser
- **What:** one `browser` tool, every feature an action: `search` (DuckDuckGo HTML, Bing fallback), `fetch` (text+links, no JS), `open`/`new_tab` (real WebView tab behind the app, keeps cookies), `read` (elements with stable ids `[e12]`), `click`, `type`(+submit), `select`, `key`, `scroll`, `back/forward/reload`, `wait`, `find`, `links`, `extract`, `eval`, `screenshot` (to vision models), tabs, `show`, `handoff` (user takes over, Hand back), `hide`, `clear`; `steps` runs up to 15 actions in one call. Guards: never types card numbers or passwords (handoff instead); pay/book/send/submit/delete refused until the user says yes (`confirm: true`). Watch button on browser cards. Prompt: never start the cloud computer for browsing.
- **Source:** `www/browser.js`, `app/src/.../AgentBrowser.java` ("ported from Argus-mobile's Phone browser").
- **Branch:** main (v2.16d).
- **Port:** this was **ported from AM**: AM's `browser/BrowserEngine.kt`, `BrowserTools.kt`, `BrowserRead.kt`, `BrowserScripts.kt`, `TabStore.kt`, `SecureInput.kt`, `BrowserVaultUi.kt`, `PagePointer*.kt` are the originals and richer (VPN, tracker block, vault logins). Use AM's; carry over A-team's single-tool `steps` batching and the confirm/handoff rules if missing.

### G6. Cloud computer (Orgo or GitHub Codespaces)
- **What:** optional, off by default. Bots use the phone's Ubuntu for everything and the `cloud` tool (`run`, `screen`, `click`, `type`, `key`, `extend`, `stop`) only for a real browser / JS-login sites / GUI / x86. **Orgo** (`/computers/{id}` status/start/stop/resume/bash/screenshot/click/type/key; suspended computers woken with `/resume`; GUI commands detached with nohup; Chrome `--no-sandbox`, `DISPLAY=:99`). **Codespaces** (list/create on owned repo with `idle_timeout_minutes=5`, start/stop; commands via `gh codespace ssh` from the phone's Ubuntu with `GH_TOKEN` in a one-shot 0600 env file; quota errors shown once and not retried). **Lease rules:** start on first call (poll 3 s, 4 min max), renew to now + idle (default 5 min, 2–15), stop at turn/job end, hard cap 60 min unless `extend` (≤ 4 h), orphan stop on app start; a Java alarm + 15-min JobScheduler job (`CloudGuard`) stop it even when the app was killed. "Cloud on · $0.055/h" chip; running minutes and cost in Settings. `GUARD.cloudSteer` bounces plain shell work back to `run`. Keys only in Keystore, added in Java per host, responses redacted.
- **Source:** `www/cloud.js`, `app/src/.../CloudGuard.java`, `CloudAlarm.java`, `CloudJob.java`, `guard.js` (`cloudSchema`, `CLOUD_PROMPT`, `cloudSteer`), `tests/v214.test.js`.
- **Branch:** main (v2.14 + v2.16b fixes).
- **Port:** no equivalent in AM (AM removed its on-phone Linux desktop; "Orgo" grep hits were false positives). Bot HQ also uses Orgo per bot (slice 1). Port `CLOUD` as a Kotlin `CloudComputer` service with the same lease state machine; `CloudGuard` maps to an `AlarmManager` receiver + WorkManager periodic worker. This is the "per-agent computer you can watch/take over" in the unified vision.

### G7. Computer view (live screen card)
- **What:** a `{t:'view'}` part in the bot's message: live frame, one action line ("Clicking at 640, 400…"), Expand (full-screen black overlay) and Stop (ends the turn and stops the machine). Frames polled every ~1.5 s only while the card is on screen, the page visible and the machine running, never renewing the lease. Sources: Orgo screenshot API; Codespaces and the phone's Ubuntu via a stdlib Python CDP client (`SHOT_PY`, `Page.captureScreenshot` JPEG q55 from Chromium with `--remote-debugging-port=9222`). A local run that mentions chromium/playwright/puppeteer/selenium opens it.
- **Source:** `www/cloud.js` (`view*`, `SHOT_PY`, `frame`, `loop`, `openFull`), `app.css` "v2.14 cloud computer".
- **Branch:** main (v2.14).
- **Port:** AM has `ui/BrowserLivePreview.kt` and `ui/ScreenGlow.kt` (agent-working edge particles). Build a `ComputerViewCard` composable (polling `Image` with `LaunchedEffect` tied to visibility via `LocalLifecycleOwner` + `onGloballyPositioned`), full-screen `Dialog`. Add "take over" (tap/keys forwarded to Orgo) to match Bot HQ.

---

## H. Account, connectors, skills, vault, memory, sync

### H1. ARGUS account sign-in
- **What:** same account as ARGUS mobile/Desktop via the auth worker: Continue with Google / GitHub (browser PKCE, loopback redirect), email + password, two-step codes (app, emailed, recovery), forgot password, "Use without an account" (offline-first). Full-screen gate styled after ARGUS mobile's account screen.
- **Source:** `www/account.js` (`ACC`), `MainActivity.browserSignIn`, `app.css` "sign-in".
- **Branch:** main.
- **Port:** AM has it: `connect/BlazeAccount.kt`, `Mfa.kt`, `AuthReturn.kt`, `ui/AccountPage.kt`. Use AM's; keep "use without an account".

### H2. Connectors page (ARGUS Connect, per user)
- **What:** full-screen plugin-list page: back button, title with a category menu, sections **Connected** / **Available**, rows with real brand logo tiles (46 px rounded, brand colour background), name and status, a trailing menu; search pill floating at the bottom; detail sheet: on/off per app and **per tool**, connect by pasting a key/fields or OAuth in the browser (loopback `http://127.0.0.1:<port>/connect`), Test, Disconnect. All calls to argus-connect `/v1/me/*` with the user's `bz_` token; keys stored encrypted server-side, never on the phone. 92 logo files (Simple Icons CC0 + official icons + category glyphs), generated by `app/scripts/gen-logos.mjs`.
- **Source:** `www/connect.js` (`CONN`), `www/logos.js`, `www/img/connectors/`, `MainActivity.connectListen`, `HANDOFF-connectors.md` (contract), `app/scripts/gen-logos.mjs`, `THIRD_PARTY_NOTICES.md`.
- **Branch:** main (v2.17, page v2.18).
- **Port:** AM has `connect/Connectors.kt` (597 lines: local OAuth loopback on 127.0.0.1:43117, Google/Spotify, tokens on the phone), `GitHubApps.kt`, `Classroom.kt`, `Mcp.kt` (remote MCP client). **Conflict:** two connector models (AM on-device tokens vs A-team server-side per-user argus-connect). Recommendation: make argus-connect the source of truth for third-party apps (multi-device, server-side encryption, one catalog), keep AM's on-device connectors only for phone-native things (Accessibility, SMS, calendar provider). Port the page as a Compose screen; ship the SVG logos as vector drawables (convert with the same generator).

### H3. Connector tools for agents
- **What:** before each turn bots load `GET /v1/me/tools` (5-min cache) and run tools with `POST /v1/me/execute`; connected apps' tools go first (up to 96, others fill to 64, total ≤ 128 under the gateway's tool cap); `connectors_list` tool; the prompt names the user's connectors and forbids shell/env access for them; output capped at 12 KB. Signed out = no connector tools.
- **Source:** `www/connect.js` (`refreshTools`, `schemas`, `exec`, `listExec`, `promptLine`).
- **Branch:** main (v2.17, v2.18).
- **Port:** new in AM. Implement `ConnectTools` that merges argus-connect tools into AM's `ToolRouter`, with per-specialist filtering (each life-domain specialist sees only its domain's apps: email/calendar for "Admin", banking for "Money", etc.).

### H4. My skills + Polish + Argus originals
- **What:** Settings > My skills: create, edit, delete own skills (name, description, content ≤ 100k chars, apps), **Polish** (`POST /v1/me/skills/polish` → gateway `/v1/skill-polish`, with Undo), read-only **Argus originals** with "Copy to my skills". Skills are served to agents and over the user's personal MCP.
- **Source:** `www/skills.js` (`SKILLS`), `HANDOFF-connectors.md`.
- **Branch:** main (v2.17).
- **Port:** AM has `agent/SkillLibrary.kt` (bundled skills from assets), `SkillIndex.kt`, `SkillMatch.kt`, `SkillPacks.kt`, `connect/SkillSync.kt`, `SkillMerge.kt`. Add argus-connect `/v1/me/skills` as a sync source in `SkillSync` and the Polish action in the skill editor.

### H5. Vault (Keystore secrets)
- **What:** `SecretStore`: AES-256-GCM key in AndroidKeyStore, prefs `argus_secrets`, excluded from backup; `vault.js` strips tokens out of the localStorage state into the Keystore and fills them back on load; provider keys (`orgo`, `gh`, `gemini`) stored under `key.<name>` that the page can set and test but never read back; Java adds them to requests only for their own host (`CloudGuard.authFor`) and redacts responses. Memory refuses secrets.
- **Source:** `www/vault.js`, `app/src/.../SecretStore.java`, `CloudGuard.java`, `memory.js` (`SECRET`).
- **Branch:** main.
- **Port:** AM has a richer vault: `agent/SecretVault.kt` (Secure Form, cards, logins, codes), `Secrets.kt`, `KeyRing.kt`, `SecretGuards.kt`, `SecretUse.kt`. Use AM's; port the write-only provider-key rule and per-host key injection.

### H6. Hybrid memory (per bot + team)
- **What:** private notes per bot plus one team list, injected into the prompt (6,000-char cap, keyword-ranked when large); managers see their reports' notes; `remember`/`recall`/`forget` tools; team writes show a card with Undo; Memory screen (Team / per-bot tabs, edit, delete); secrets refused; local only (not synced).
- **Source:** `www/memory.js` (`MEM`).
- **Branch:** main (v2.6).
- **Port:** AM has `agent/Memory.kt`, `Mind.kt` (journal, memories, context ladder), `MemoryGate.kt`, `MemoryMerge.kt`, `MemoryDelete.kt`, `PinnedFacts.kt`, `ui/MemoryPage`/`MemGraph`. Extend AM memory with a **scope** (`team` | `bot:<id>`) and the "managers see reports' notes" visibility rule.

### H7. User profile kept by the Manager + auto-learn
- **What:** JSON + markdown view, lines marked **stated** vs **inferred**, secrets refused; `memory_read` (all bots), `memory_write`/`memory_forget` (Manager; other bots only if Settings allows); relevant lines travel with delegated jobs; Settings > "What Manager knows about you" (add, edit, delete, mark as stated, Forget everything); mirrored to `/root/.argus/manager/profile.{json,md}`; local only (`argus.profile.v1`). Since v2.16c `autoLearn` saves rules ("always/never/don't/from now on"), preferences ("I prefer…", "my boss is…", "call me…") and "remember/note that…" from each user message with a "Saved to memory" card; every bot's prompt carries the standing rules (`S.settings.autoMemory`).
- **Source:** `www/manager.js` (`profile`, `saveProfile`, `mirror`, `autoLearn`, `pf*`).
- **Branch:** main (v2.15, v2.16c).
- **Port:** AM has `agent/PromptProfile.kt`, `MemoryIntent.kt`, `Learn.kt`, `Learning.kt`, `SelfKnowledge.kt`. Merge: the profile becomes AM's user-facts memory with the stated/inferred flag; the "standing rules in every specialist's prompt" rule is important for the ecosystem.

### H8. Two-way sync with `argus-agents-api`
- **What:** automatic with the ARGUS account (or a self-hosted URL+token under Advanced); `track()` stamps changed items (`u`), tombstones deletions; `POST /v1/sync` two-way, last write wins per item; items changed while a request is out are kept; replies without agents/jobs arrays rejected. Settings shows the MCP endpoint with Copy. Only bots and jobs sync (no chats, memory, profile).
- **Source:** `www/app.js` (`SYNC`, `track`, `pub`, `sig`), `api/src/index.js` (`sync`), `tests/sync.test.js`.
- **Branch:** main.
- **Port:** AM has `connect/CloudSync.kt` (E2E-encrypted chats/memory/settings/designs, off by default), `SyncEngine.kt`, `SyncMerge.kt`. Keep `argus-agents-api` as the team/job store (it is what ARGUS Desktop and MCP clients read), implement a `TeamSync` adapter in AM's `SyncAdapters.kt` with the same LWW + tombstone contract.

### H9. Backup, import, erase, demo team
- **What:** Settings > Data: Copy backup (JSON), Import, Demo team, Erase (red glass button).
- **Source:** `www/app.js` (`exp`, `imp`, `wipe`, `demo`).
- **Branch:** main.
- **Port:** AM has `agent/BackupBrain.kt`, `BackupBridge.kt`. Add an **A-team import** that reads this JSON (bots, jobs, settings, memory) — the cleanest migration path for existing A-team users.

### H10. Removed in v2.18 (do not port)
- On-phone **Connections** card (WhatsApp Business, account tokens exported into the shell env), and the v2.16b `gmail_list`/`gmail_read`/`cal_list` shell helpers that depended on it. On start the app wipes the old tokens and rewrites `~/.argus/env` with the nickname only.
- **Source:** `HANDOFF-v2.16.md` §5, README "Connectors and skills", `www/setup.js` (`purgeLegacy`).

---

## I. Proactivity, routines, notifications

### I1. Routines and reminders (closed-app)
- **What:** `routine_create`/`list`/`cancel`: once, daily, weekly or cron in the local time zone (DST-aware); the page computes fire events and mirrors them to Java (`routineSync`); `RoutineAlarm` (AlarmManager, boot receiver) + `RoutineJob` (15-min JobScheduler) post the notification with the app closed; task/brief routines run their model call the next time the WebView runs.
- **Source:** `www/manager.js` (`nextRun`, `addRoutine`, `events`, `sync`, `fire`, `tick`), `app/src/.../Routines.java`, `RoutineAlarm.java`, `RoutineJob.java`.
- **Branch:** main (v2.15).
- **Port:** AM has `routine/Routines.kt` + `RoutineReceiver.kt` (days bitmask, one-offs, shell jobs) and `proactive/Triggers.kt`. Use AM's engine (it runs the agent natively, closing A-team's "runs only when the WebView runs" gap); add cron and per-bot ownership.

### I2. Daily check, "Manager suggests" cards, Today strip
- **What:** daily initiative check at 07:30 (one cheap call, skipped when nothing changed); at most 3 suggestion cards with **Do it / Not now / Stop suggesting this** (saves a standing "don't" rule); quiet hours 22:00–07:00; max 3 proactive notifications a day; per-topic mutes; never spends, sends, deletes or starts the cloud computer unconfirmed; one gentle follow-up at a time; a Today strip in the Manager chat; Settings > Proactivity.
- **Source:** `www/manager.js` (`check`, `CHECK_SYS`, `addCards`, `card*`, `inQuiet`, `canNotify`, `risky`, `muted`, `jobAllowed`, `cloudAllowed`, `todayHTML`).
- **Branch:** main (v2.15).
- **Port:** AM has `proactive/Proactive.kt`, `Jarvis.kt`/`JarvisLogic.kt` (quiet hours exist), `Autopilot.kt`, `FollowUps.kt`, `ProactiveDial.kt`, `agent/NudgePolicy.kt`. Merge A-team's caps, mute and "Stop suggesting this → standing rule" into AM's proactive policy; render the suggestion card in A-team style.

### I3. In-app notification list + system notifications
- **What:** when a bot finishes/fails a job or replies while you're not looking: an Android notification (title = bot name, body = clean summary, tap opens the chat via `argus_agent`) and an entry in the in-app list (bell with unread badge). POST_NOTIFICATIONS requested on Android 13+.
- **Source:** `www/jobs.js` (`NOTIF`), `MainActivity` (`notify`, `openFromIntent`).
- **Branch:** main (v2.6).
- **Port:** AM has `notify/Notifier.kt` (status line with Stop, inline replies), `ChatThreads.kt`, `Conversations.kt`, `MessageThread.kt`. Use AM's; one conversation thread per bot.

### I4. "ARGUS Vault" pings: heads-up with the bot's avatar, edge glow, haptics, pop-up
- **What:** heads-up notification titled with the bot's name and showing its own orb picture (MessagingStyle `Person` + large icon, tinted in its colour; avatars rendered to PNG in JS and handed to Java once); **EdgeGlow**: light pulsing three times along both screen edges in the bot's colour (overlay window, needs "Display over other apps"; CSS version in-app); **Haptics**: exactly two short taps; in-app **pop-up** banner (frosted, avatar, name, time, 2-line text; tap opens chat, swipe up dismisses). Settings > Notifications toggles + Preview.
- **Source:** `www/notify.js` (`PING`), `app/src/.../AgentNotify.java`, `EdgeGlow.java`, `Haptics.java` (ported from ARGUS Vault), `app.css` "notifications the ARGUS Vault way".
- **Branch:** main (v2.16).
- **Port:** AM has MessagingStyle threads (`notify/*`), an overlay (`ui/Overlay.kt`) and screen-edge particles (`ui/ScreenGlow.kt`). Port `EdgeGlow`/`Haptics` as Kotlin (they came from ARGUS Vault's Kotlin originally: `notify/EdgeGlow.kt`), and render avatars from the Compose orb to a Bitmap for `Person.setIcon`.

---

## J. Cloud API: `argus-agents-api` (Cloudflare Worker)

### J1. REST `/v1`
- **What:** `GET /v1/summary` (overview, overdue, due today); `GET/POST /v1/agents` (list with `?query=`, create-or-update by name); `GET/PATCH/DELETE /v1/agents/{name|id}`; `GET /v1/jobs?agent=&bucket=&status=&query=`; `POST /v1/jobs {title, agent, due, priority}`; `PATCH/DELETE /v1/jobs/{id|title}`; `POST /v1/jobs/{id|title}/complete` (`{"done":false}` reopens); `POST /v1/sync`. Agents/jobs resolvable by id, exact name, email, first name or title text; due words in the team time zone (`TZ_OFFSET_MIN`, default +120). CORS open.
- **Source:** `api/src/index.js`, `api/README.md`, `api/test.mjs` (20 tests), `api/dev.mjs` (local :8790).
- **Branch:** main.
- **Port:** **reuse as is.** Kotlin client in AM (`connect/Http.kt` style). Consider extending later with chats/memory endpoints if those should sync.

### J2. MCP `/mcp` + OpenAPI
- **What:** streamable-HTTP JSON-RPC (`initialize`, `ping`, `tools/list`, `tools/call`; batches; GET → 405) with tools `summary`, `list_agents`, `get_agent`, `upsert_agent`, `delete_agent`, `list_jobs`, `add_job`, `update_job`, `complete_job`, `delete_job`, `query`; `/openapi.json` for REST-tool importers.
- **Source:** `api/src/index.js` (`mcp`, `TOOLS`, `openapi`).
- **Branch:** main.
- **Port:** reuse. AM's `connect/Mcp.kt` (MCP client) can even consume it. Show the endpoint + copy in Settings like A-team.

### J3. Auth and tenancy
- **What:** either the deployment's `API_TOKEN` (shared doc `doc`) or any ARGUS session token, verified via service binding `AUTH` → blaze-auth `/v1/me` (cached 10 min in KV; rejections cached 60 s; other statuses not cached); each account has its own doc `user:<id>`; 401s carry a `reason`. KV binding `DATA`.
- **Source:** `api/src/index.js` (`accountId`), `api/wrangler.toml`.
- **Branch:** main.
- **Port:** nothing to change; AM already holds the same `bz_` session token.

---

## K. Quality infrastructure

### K1. Evals harness
- **What:** `evals/run.mjs` (Node 22, no deps): suites `terminal` (69 tasks L1–L5), `code` (29 tasks, end-to-end runnability, complexity limits, no stubs), `refusals` (63 prompts: 32 refuse, 31 help look-alikes); strict transcript rules (syntax, sudo, destructive, fake-run…); `--guard`, `--native`, `--model chain` (same live-model logic as the app), per-step traces, reports in `evals/reports/`; unit tests `evals/test/*.mjs` (83 at v2.15) for guard, models, manager, bgjobs.
- **Source:** `evals/`, `evals/README.md`, `evals/PLAN.md`.
- **Branch:** main (from `wip/agent-evals`).
- **Port:** keep as an external harness against the gateway; re-point it at the unified app's prompt/guard once ported (or run it on the Kotlin guard through a small CLI). AM has `agent/Traces.kt`, `PhoneQaReport.kt`.

### K2. App tests
- **What:** Playwright suites with mocked `Native`, auth, gateway and vault (ui, signin, models-workspace, terminal, chat, jobs, v27, v29, v214, v215 (97 checks), v216/b/c/d, v217 (68), v218, review, polish, icons, setup, vault, sync); Java `PtyArgvTest`, `WorkspaceTest`.
- **Source:** `app/tests/`.
- **Branch:** main (+ `v219.test.js` on v2.19).
- **Port:** use them as **behaviour specs**: each `PASS` line is a requirement for the Compose port (Compose UI tests + JUnit).

### K3. No-Gradle build
- **What:** `app/build.sh`: aapt2 → javac (1.8) → d8 → zip → zipalign → apksigner (~20 s); no AAR/Maven/Kotlin. Release key changed at v2.12 (alias `argus-agents`).
- **Source:** `app/build.sh`, HANDOFF "How to build".
- **Port:** obsolete in the unified Gradle app. Note: A-team users must install the unified app as a new package (different package + key), so plan an in-app "Move to ARGUS" prompt in a last A-team release.

---

## Feature index (72)

| # | Feature | Group | AM equivalent | Port approach |
|---|---|---|---|---|
| 1 | WebView shell + Native bridge | A1 | native app | drop; map to services |
| 2 | Nav stack + reopen last page | A2 | ChatNav, BackRule | Compose nav + DataStore |
| 3 | Notch Bots/Chat pill | A3 | none | new component |
| 4 | Chat page (Manager, no AI bubble) | A4 | ChatScreen | variant |
| 5 | Chat drawer, saved chats (v2.19) | A5 | ChatStore/ChatIndex (one mind) | decide, then drawer |
| 6 | Team home as chats | A6 | HomeBoard (different) | new screen |
| 7 | + sheet | A7 | Attach | sheet |
| 8 | Theme | A8 | AppTheme/ThemeMode | re-skin |
| 9 | First-run setup | A9 | SetupGate | extend |
| 10 | Sheets/toasts/long-press/empty | A10 | GlassKit | restyle |
| 11 | Bots as chats | B1 | Specialists (hidden) | new Bot entity |
| 12 | Bot editor + look | B2 | ColorWheel | new sheet |
| 13 | Orb avatars, Manager sphere, icon | B3 | ArgusMark | Canvas rewrite |
| 14 | Built-in Manager (locked) | B4 | one mind | Manager = main assistant |
| 15 | Manager tools | B5 | ToolRouter, ConfirmGate | port tools |
| 16 | update_agent / note_team | B6 | UndoCenter | port tools |
| 17 | Jobs model + Jobs page | C1 | TaskKeeper | new |
| 18 | Job queue runs + waits-on | C2 | BgAgents, JobKeeper | service, parallel |
| 19 | Approvals inline / need you | C3 | ConfirmCard | approvals inbox |
| 20 | Verified done_job | C4 | ToolVerify | verify policy |
| 21 | Failed jobs + retry/backoff | C5 | RetryGuard | port |
| 22 | Background jobs (WIP) | C6 | JobKeeper (done) | use AM |
| 23 | Compose graph | C7 | Blueprint (data only) | Canvas rewrite |
| 24 | Streaming + parts + Stop | D1 | Agent.kt, StopResume | port rules |
| 25 | Native tools + fence fallback | D2 | ToolCalls, TextCalls | merge cases |
| 26 | Tool cards + grouping | D3 | StepPill, CalmSteps | restyle |
| 27 | Markdown | D4 | AnswerCards | restyle |
| 28 | Attachments | D5 | Attachments | extend |
| 29 | Context window/summary/TZ | D6 | TurnContext, AutoCompact | check blaze_context |
| 30 | Home box command router | D7 | FastLane, QuickRules | port actions, @mentions |
| 31 | Sign-in banner / offline | D8 | SignInGate, OfflineBrain | use AM |
| 32 | No filler / unbacked claims | D9 | ClaimGuard | merge cases |
| 33 | Workspace snapshot / working set | D10 | Orient | port |
| 34 | Guard layer | E1 | CommandPolicy, LoopGuard | port gate + rules |
| 35 | Code review | E2 | none | port |
| 36 | Live gateway model picking | F1 | Cloud.kt, ModelRoute | port models.js |
| 37 | Gemini own key, per bot | F2 | Brains, KeyRing | per-bot + cool-down |
| 38 | Needle on-device commands | F3 | OfflineBrain (Qwen) | Cactus SDK |
| 39 | Needle fine-tune data | F4 | none | keep dataset |
| 40 | Plan display | F5 | Billing | use AM |
| 41 | Ubuntu PRoot workspace + tmux per bot | G1 | Linux, Distros | use AM + tmux |
| 42 | Terminal | G2 | TermSessions | use AM |
| 43 | attach/save/preview/share/zip | G3 | SaveFile, Delivery | port tools |
| 44 | write_file | G4 | none | port |
| 45 | Bots' browser | G5 | BrowserEngine (origin) | use AM |
| 46 | Cloud computer + lease + guard | G6 | none | port |
| 47 | Computer view | G7 | BrowserLivePreview | new card |
| 48 | ARGUS sign-in | H1 | BlazeAccount | use AM |
| 49 | Connectors page + logos | H2 | Connectors.kt (different model) | argus-connect + Compose |
| 50 | Connector tools for agents | H3 | none | port |
| 51 | My skills + Polish + originals | H4 | SkillLibrary, SkillSync | add source |
| 52 | Vault / provider keys | H5 | SecretVault | use AM |
| 53 | Hybrid memory | H6 | Memory, Mind | add scopes |
| 54 | Manager profile + autoLearn | H7 | PromptProfile, Learn | merge |
| 55 | Two-way team sync | H8 | CloudSync | TeamSync adapter |
| 56 | Backup/import/erase/demo | H9 | BackupBrain | A-team import |
| 57 | Removed Connections/gmail helpers | H10 | — | do not port |
| 58 | Routines (closed-app) | I1 | routine/Routines | use AM + cron |
| 59 | Daily check + suggestion cards | I2 | Proactive, Jarvis | merge policy |
| 60 | Notification list + system notifs | I3 | Notifier | use AM |
| 61 | Vault-style pings (avatar, glow, haptics, pop-up) | I4 | ScreenGlow, Overlay | port |
| 62 | REST /v1 | J1 | — | reuse server |
| 63 | MCP /mcp + OpenAPI | J2 | Mcp.kt (client) | reuse server |
| 64 | Auth/tenancy | J3 | BlazeAccount token | reuse |
| 65 | Evals harness | K1 | Traces | keep external |
| 66 | Playwright/Java tests | K2 | — | use as specs |
| 67 | No-Gradle build | K3 | Gradle | obsolete |
| 68 | Per-bot Settings cards (Workspace, Cloud, Connectors, Skills, Manager, Notifications, Review, Memory, Sync, Appearance, Models, On-device, Data) | L/Settings | SettingsV18 | re-layout |
| 69 | Step budget + StepGuard | D1/E1 | LoopGuard | merge |
| 70 | Interrogation bounce / short replies | D6 | AnswerStyle | merge |
| 71 | Server/`cmd &` detach | E1 | Processes | port |
| 72 | Parallel tool calls (not started) | D1 | orchestra/ToolScheduler | use AM |

---

## L. A-team design language (the unified app adopts this)

Source of truth: `app/www/app.css` (72 KB, layered: v1 monochrome base → **v2 "chat-first look (Grok Bot × ChatGPT × Claude)"** →
**"liquid glass (v1 look) on the chat-first layout"** → per-feature sections v2.6–v2.18; later layers override earlier ones),
`index.html`, `icons.js`, `app.js` (`botSVG`, `COLORS`). Rules from HANDOFF: **monochrome UI with Lucide icons, no emoji;
fields are 52 px; backdrop blur only on fixed chrome; nothing jumps, flickers or resizes when the keyboard opens.**

### L1. Character in one paragraph
Near-black (or cool off-white) ground with soft monochrome light blobs behind it; content is ink on ground with
**colour reserved for meaning** (red overdue/errors, green done/healthy, blue unread, gold Manager badge) and for the
**bots themselves** (each bot's orb colour). Fixed chrome (top bar, composer, chips, sheets, toasts, pill) is
**frosted liquid glass**: translucent tint, 22–30 px blur, 1 px bright rim, top specular gradient. Scrolling surfaces
(cards, bubbles, buttons) are **glass without blur**: tinted fill + specular gradient + rim + soft shadow. Primary
actions are **solid ink** (white on dark, near-black on light) with a subtle top highlight. Shapes are generous pills
and 20 px cards. Chat reads like ChatGPT/Claude: one pill composer, ink user bubbles, AI text straight on the page.

### L2. Colour tokens (effective values after all layers)

| Token | Dark | Light | Use |
|---|---|---|---|
| `--bg` | `#0b0b0d` | `#eef0f4` | screen ground (light was `#fff` in v2, glass layer sets `#eef0f4`) |
| `--bg2` | `#0b0b0d` | `#ffffff` | raised ground, `select option` bg |
| `--ink` | `#f2f2f4` | `#111114` | text, primary fills (solid buttons, user bubbles, "need you" banner, selected segments) |
| `--mute` | `#9b9ba3` | `#7c7c84` | secondary text, section labels |
| `--dim` | `#6b6b73` | `#a2a2aa` | tertiary text, timestamps, placeholders, idle dots |
| `--soft` | `rgba(255,255,255,.07)` | `rgba(0,0,0,.045)` | quiet fills (intro model pill, notes, pressed rows, attachment tiles) |
| `--soft2` | `#24242a` | `#e8e8ec` | solid quiet fill (Connectors back button, search pill) |
| `--line` | `rgba(255,255,255,.09)` | `rgba(0,0,0,.08)` | hairlines, card borders on plain surfaces |
| `--line2` | `rgba(255,255,255,.22)` | `rgba(0,0,0,.22)` | stronger borders, unchecked checkbox, grabber |
| `--gl` | `rgba(255,255,255,.055)` | `rgba(255,255,255,.62)` | glass fill (cards, inputs) |
| `--gl2` | `rgba(255,255,255,.09)` | `rgba(255,255,255,.78)` | glass fill, stronger (AI bubble, buttons, icon buttons) |
| `--glb` | `rgba(255,255,255,.14)` | `rgba(255,255,255,.9)` | glass rim/border |
| `--glb2` | `rgba(255,255,255,.26)` | `rgba(0,0,0,.12)` | focused glass border |
| `--chrome` | `rgba(14,14,18,.58)` | `rgba(255,255,255,.6)` | frosted fixed chrome fill |
| `--sheet` | `rgba(22,22,27,.93)` | `rgba(250,250,252,.94)` | sheets, toasts, notch pill, pop-up (frosted, nearly opaque) |
| `--spec` | `linear-gradient(180deg, rgba(255,255,255,.14), rgba(255,255,255,.02) 42%, transparent 60%, rgba(255,255,255,.04))` | `linear-gradient(180deg, rgba(255,255,255,.95), rgba(255,255,255,.4) 45%, rgba(255,255,255,.2))` | specular sheen layered over glass fills |
| `--ghl` | `inset 0 1px 0 rgba(255,255,255,.16), inset 0 -1px 0 rgba(255,255,255,.04)` | `inset 0 1px 0 #fff, inset 0 -1px 0 rgba(0,0,0,.04)` | glass inner highlight |
| `--gsh` | `0 12px 40px rgba(0,0,0,.45), 0 2px 8px rgba(0,0,0,.3)` | `0 10px 34px rgba(30,30,60,.10), 0 2px 6px rgba(30,30,60,.06)` | floating shadow (composer, toast) |
| `--od` | `#ff5a5f` | `#e5484d` | overdue, errors, danger text, unread-overdue dot |
| `--okc` | `#3ecf8e` | `#16a36a` | healthy/online/done, live dots (with 6–8 px glow) |
| `--blue` | `#3b82f6` | `#2f7bff` | unread dot; Manager glow and ping colour `#3b82f6` |
| `--r` | 18 px | | legacy card radius (v2 cards use 20) |

Other fixed colours: Manager badge gold `#d4a72c` (text) on 22 % gold (`color-mix`), Manager row tint 7 % gold;
warning `#e0a020`; danger button `rgba(255,59,48,.16)` fill, `rgba(255,90,80,.5)` border, `#ff6b62` text (light: `#d70015`
on `rgba(255,59,48,.1)`), red glow `0 4px 18px rgba(255,59,48,.18)`; review severities blocker `#ef4444`/`#f87171`,
major `#f59e0b`/`#fbbf24`, minor `#60a5fa`; terminal `#0a0a0a` bg / `#f2f2f2` fg (always dark), code-in-terminal
`#07070a`/`#e6e6ea`; computer view black `#000`; scrim `rgba(0,0,0,.35)` (notch `.28`); `theme-color` `#0b0b0d`.
**Bot palette** (`COLORS`, "the orbs in the ARGUS Agents art"): `#8b5cf6` violet, `#22c55e` green, `#f59e0b` amber,
`#ef4444` red, `#06b6d4` cyan, `#6366f1` indigo, `#3b82f6` blue, `#cbd5e1` silver; editor adds `#e8e8e8`, `#8a8a8a`
and any colour (conic rainbow swatch `#ff5d73 → #ffb648 → #3ee08f → #2fe0c8 → #5a8bff → #b28bff`).
**Background light blobs:** dark: radial white at 15 %/8 % (α .10), 92 %/30 % (α .06), 40 %/100 % (α .07) over `--bg`;
light: `#ffffff` at 12 %/6 %, `#dfe3ec` at 95 %/32 %, `#e6e9f0` at 40 %/100 % over `#eef0f4`.

### L3. Typography
- **Family:** `Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` (Inter is not bundled, so Android renders **Roboto**); monospace `ui-monospace, "Roboto Mono", Menlo, Consolas, monospace`; one serif moment: `Georgia, "Times New Roman", serif` 30 px/400, −0.3 px, line 1.2, for the Chat page greeting.
- **Base:** 15 px / 1.4. Inputs 16 px (no zoom on focus). Message text 15 px / 1.38.
- **Scale:** `h1.big` 32 / 750 / −0.8 px (team name) · gate h1 28 / 750 / −0.6 · setup h1 27 / 750 / −0.6 · plan price 26 / 750 / −0.6 · `h1` 26 / −0.4 · Chat greeting 30 serif · intro name 20 · `hello b` 20 · sheet title `h3` 19 · Connectors row title 17 / 500 · top-bar name `who b` 17 / 650 · chat row name `crow b` 16 / 620 · menu title 15.5 / 600 · body 15 · tool card title 13.5 / 560 · secondary 14–14.5 mute · chips 13.5 (`chipb`) / 11.5 (`chip`) · section label `sec` 12 / 650 / UPPERCASE / +1 px · settings `h2` 12 / 600 / UPPERCASE / +1.6 px · jobs card header 11.5 / 650 / UPPERCASE / +1 px · pill 11 · code 12–13 mono / 1.45.
- **Numbers that tick** (progress, counts, dates, chips, due labels, plan price) use `font-variant-numeric: tabular-nums` so widths never jump.
- Weights used: 400, 500, 520, 550, 560, 600, 620, 650, 700, 750 (variable-weight feel; in Compose use Inter variable or Roboto Flex to hit them).

### L4. Spacing, sizing, layout grid
- Screen side padding **16 px** (rows use 4 px inner padding so text aligns at 20 px); fixed chrome inset **12 px** from the edges.
- **Top bar:** fixed, height `62 px + safe-area-top`, padding 0 14 px, gap 10; contents: 38 px round avatar/`me` or back (`ib.flat` 30 px, 24 px chevron), `who` block (name 17 + status line 12.5 with a 7 px dot), trailing 38 px round icon buttons. Bottom hairline only on chat/jobs/settings views. Content starts at `68 px + safe-top`.
- **Composer:** fixed pill, `left/right 12 px`, `bottom 14 px + safe-bottom`, **height 58, radius 29**, inner padding 0 8, gap 8; left 42 px round "+" (glass), input 16 px, right 42 px round solid-ink send (turns into stop square while streaming; pulses when busy). Suggestion **chips row** sits 82 px above the bottom (8 px gap, 13.5 px text, radius 18, padding 8×13). Attachment bar 80 px above bottom, 52 px tiles. Content bottom padding 100 px (chat 150, Chat page 110).
- **Lists:** chat rows `crow` padding 9×4, gap 12, avatar 50 (md); task rows 12×4 with bottom hairline; Connectors rows min-height 76, gap 16, logo 46; menu rows 44 px icon tile + 2-line text, padding 9×6, gap 4.
- **Cards:** padding 14 (jobs card 12×14), gap between thread items 9 (Chat page 14), card margin-bottom 10, max-width 92 % in threads.
- **Bubbles:** max-width 82 %, padding 10×14.
- **Sheets:** padding 10 16 (20 + safe), grabber 40×5 radius 9, max-height 88 %.
- **Hit targets:** 38–56 px (icon buttons 38, composer buttons 42, FAB 56, Connectors back 48, trailing menu 44); **fields and gate buttons 52 px**.
- **Avatar sizes:** xs 18, sm 34, default 46, md 50, lg 72, xl 84 (orbs have no box; they overflow visibly).
- **Graph nodes** 184×84.

### L5. Radii
Composer 29 (pill) · gate/primary full-width buttons 26 (52 tall) · sheets 26 top corners (v1 24) · cards, job cards, plan cards, setup tiles 20 · user bubble `20 20 6 20`, AI bubble `20 20 20 6` (when bubbled) · chips 18 / pills 99 · pop-up banner 22 · system message 16 · big search, gate inputs 16 · chat row pressed 16 · tool cards, tool groups, computer view, attachment tiles 14 · menu rows 14 · buttons 13 (sm 11) · toast 13 · field inputs 12 · code blocks 12 · Connectors logo 13 (lg 15) · checkbox 7 (21 px) · icon buttons, FAB, avatars, `me`: circle · notch handle 11 (46×22) · progress bars 4–9.

### L6. Elevation, glass recipes (Compose translation)
- **Frosted chrome** (top bar, composer, chips, toast, sheet, notch pill, pop-up): fill `--chrome`/`--sheet`, `backdrop-filter: blur(22–30px) saturate(160–170%)`, 1 px `--glb` border, `--ghl` inset highlight, composer adds `--gsh` and a `--spec` overlay at 70 % opacity. → Compose: `Modifier.glassBlur` (API 31+ `RenderEffect`, fallback to the fill only), border 1 dp, a vertical gradient overlay for `--spec`; AM's `GlassKit.kt`/`BlazeGlass.kt` already implements this pattern (greyscale glass, 22 dp card) — retune its tokens to section L2.
- **Glass surface (no blur)**: `background: var(--spec), var(--gl)`, 1 px `--glb`, shadow `--ghl` + `0 6px 22px rgba(0,0,0,.12)` (dark cards `0 8px 26px rgba(0,0,0,.35)`).
- **Solid ink primary**: `linear-gradient(180deg, rgba(255,255,255,.2), transparent 60%), var(--ink)`, shadow `0 6px 18px rgba(0,0,0,.22), inset 0 1px 0 rgba(255,255,255,.25)`.
- **Inputs**: `--gl` fill, `--glb` border, `inset 0 1px 2px rgba(0,0,0,.12)`; focus → `--glb2` border + `0 0 0 3px var(--gl2)` ring.
- **Plain surfaces in threads** (tool cards, code blocks, computer view): `--bg` fill + 1 px `--line`, no shadow.
- Blur is **never** on scrolling content (performance rule).

### L7. Components catalogue
- **Top bar** (`.top`, `.who`, `.ib`, `.me`): avatar or back, name + status dot line (green "on", red "od"), round icon actions.
- **Composer pill** (`.cmd`) + **suggestion chips** (`.chipb`) + **attachment bar** (`.attc`).
- **Notch** grip + **Bots | Chat pill** (`.notch`, `.npill`; on segment = ink fill).
- **Chat row** (`.crow`): orb, bold name + right-aligned time, one-line preview (red/green variants), blue unread dot; Manager row gold-tinted with **Lead** badge (`.mbadge`).
- **"Need you" banner** (`.need`): full-width ink button, flag icon, bold count, names, trailing "Review ›".
- **Pins row** (`.pins`/`.pinb`): 72 px orbs with names, red overdue dot (13 px, 2.5 px ground ring).
- **Intro block** (`.intro`): 84 px orb, name 20, role line, model pill with health dot, portfolio.
- **Job card** (`.jcard`) with job rows (`.jrow`: 21 px rounded checkbox → ink when done + check, title, due label red when overdue), "Needs your ok" variant with paired buttons (`.btns`: glass + ink).
- **Messages**: user ink bubble; AI text on page (`.mdb` markdown); system message (dashed → glass, centred, 13 px mute) with action lines (check icon green / alert red); error message (alert icon, dashed border); typing dots (3 × 7 px, staggered pulse); streaming caret (7×15 ink block).
- **Tool card** (`.tc`): 40 px header (kind icon, title, status, chevron rotating 180°), body with `$ ` command and mute output; **tool group** (`.tg`, "N steps" counts, sub-groups); **Undo** chip; **review card** (`.rv`, severity pills and left-border findings); **computer view** (`.cv`, 16:10 black screen, live green dot, action line, Expand/Stop); **browser card** (URL line + screenshot); **attachment card** (preview/open/share/save).
- **Manager suggestion card** (`.mgr-card`: header, title, Do it / Not now / Stop suggesting), **Today strip** (`.mgr-today`), **delete confirm row** (`.mgr-confirm`).
- **Segmented filter** (`.seg`: pill buttons with counts, selected = ink).
- **Buttons**: `.btn` glass, `.btn.p` ink, `.btn.d` red glass, `.btn.sm`; full-width gate buttons (`.obtn` outline-glass with provider icon, `.pbtn` ink); FAB 56 ink circle.
- **Fields** (`.field` label 12 mute + 52-ish input), big search (`.search.big`), floating search pill (Connectors).
- **Bottom sheet** (`.sheet`) with menu rows (`.menu`), **toast**, **pop-up notification** (`.popn`: avatar 42, name + time, 2-line clamp, rim tinted by bot colour, glow `0 0 24px -6px <colour>`), **edge glow** (34 px gradients on both edges in the bot colour, 3 pulses / 2.6 s).
- **Settings cards** (`.card.glass` with `h2` section labels): Account/plan, Team, Workspace, Cloud computer, Connectors, My skills, Manager (profile + proactivity), Notifications, Code review, Memory, Sync (+ MCP endpoint copy), Appearance, Models (live list), Gemini, On-device (Needle), Data; footer "ARGUS A team 2.18 · offline-first".
- **Full-screen pages**: sign-in gate, setup, workspace loader (`.wsl`, triple-ring orbit spinner), Terminal (`.tv`, always dark, tabs, extra-keys rows), Connectors (`.cnp`, 3-column top bar 56/1fr/56, sections, floating search), computer view full screen.
- **Compose graph**: nodes (rect `--bg2` with `--line2` stroke; agent stroke ink 1.4; selected 2.4), ports (hollow in / filled out), edges ink 1.8 round (dep dashed 7 5, lead dotted 1 5 at 2.4, ghost 4 4 at .6), legend.
- **Avatars**: orb SVG (L9), ghost avatar (soft circle with "?"), Manager sphere.

### L8. Motion
- Sheet / gate / page enter: `up` keyframe, 0.2 s ease-out, from `translateY(40px)`, opacity 0 (Connectors 0.18 s).
- Toast: 0.25 s slide 20 px + fade. Pop-up banner: 0.35 s `cubic-bezier(.2,.9,.3,1.12)` from `translateY(-140%)`, drag to dismiss.
- Notch grip: transform 0.25 s `cubic-bezier(.2,.9,.3,1.2)` (overshoot), follows finger with no transition while pulling; pill opens 0.28 s `cubic-bezier(.2,.9,.3,1.15)` from `scale(.4,.5)` at top-centre origin, opacity .18 s.
- Pulse (typing dots, caret, busy send button): 1 s, 50 % opacity .35, dots staggered .2 s.
- Spinners: 1 s linear (`ring2`), workspace orbit three rings 1.2 s / 1.8 s reverse / 2.6 s.
- Edge glow: 2.6 s, three bright peaks (.85/.95/.9). Chevron rotate .15 s. Progress bars width .5 s. Theme switch bg/colour .2 s. Press feedback `scale(.97)`.
- `prefers-reduced-motion: reduce` disables all animation and transitions. No layout shift on keyboard open (adjustResize, fixed heights, flex-shrink 0 on columns, tabular numbers, one-line status line min-height).
- Compose: `tween(200, easing = FastOutSlowIn)` for sheets, `spring(dampingRatio ≈ 0.6–0.7, stiffness medium)` to mimic the 1.12–1.2 overshoot béziers, `infiniteRepeatable` for pulses; honour `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.

### L9. Iconography and imagery
- **Lucide v0.460.0** line icons (ISC), inline SVG via `ic(name, size)`; stroke width scales with size (≤16 px: 2.25, ≤20: 2, larger: 1.85), round caps and joins, `currentColor`, default size 1.15 em. ~85 icons in `icons.js` (shield-check, shield-alert, wrench, square-pen, message-circle, user(-plus/-round), pencil, plus, star, pin, trash-2, search, settings, x, chevrons, check, send, arrow-up, terminal, square-terminal, clock, calendar(-clock), triangle-alert, info, copy, download, upload, log-out, moon, sun, link, phone, github, mail, bot, cpu, sparkles, refresh-cw, rotate-ccw, ellipsis, filter, play, square, layers, git-branch, workflow, lock, list-checks, flag, zap, cloud, smartphone, maximize-2, plug, circle-check, circle-alert, notebook, music, hash, key, shield, arrows, clipboard-paste, a-arrow-up/down, rows-3, network) plus a few in chat.js/files.js/jobs.js (paperclip, image, camera, file, brain, users, eye, folder, bell, briefcase). v2.19 adds a custom **drawer** mark (three left-aligned strokes long/short/medium). **No emoji anywhere.**
  → Compose: AM uses Material icons; import Lucide as vector drawables (e.g. `lucide-android`/`compose-icons` Lucide set) and keep the stroke-by-size rule.
- **Bot orbs** (`botSVG`): 64×64 viewBox; halo `r31` radial (colour α .45 → 0); body `r20` radial (highlight = colour mixed 70 % white at 38 %/32 %, colour at 42 %, shadow = colour mixed 82 % with `#02030a`); inner core `r9` radial white → highlight → transparent at (34,33), opacity .85; rim `r19.5` 1 px stroke (colour mixed 45 % white, α .75); specular ellipse rx7 ry3.6 at (25,22) rotated −28°, white α .32; rings: back half in colour (stroke ×0.8, α ×.55) behind the body, front half in the rim colour in front; ring per face: Orbit −16°, Tilt 14°, Steep −34°, Pure 0° (no ring), Twin −20° (two rings 30×7.5 and 25×5), Polar 62°; default ring 30×8, stroke 2. Face from name hash if unset. Avatars on Home/intro get `drop-shadow(0 8px 16px rgba(0,0,0,.25))`.
- **Manager**: blue glass "A" sphere PNG, circular, glow `drop-shadow(0 0 6px rgba(59,130,246,.45))`; app icon is the same A-orb (adaptive + monochrome).
- **Connector logos**: brand SVG (Simple Icons CC0) centred 26 px on a 46 px rounded tile filled with the brand colour (1 px `--line2` border, inner top highlight), official icons may fill the tile, category glyph on `#2b2b30` when there is no logo; disabled apps at 45 % opacity.

### L10. Layout patterns and IA
- **Chat-first, three surfaces:** Home (team as chats) ↔ bot chat (jobs + approvals inline) ↔ Chat page (Manager), switched by the **notch pill**; everything else behind **+** (bottom sheet) or the profile/settings entry; Settings is one long scroll of glass cards with uppercase section labels; heavy tools (Terminal, Connectors, computer view, sign-in) are **full-screen pages** with their own top bars; small edits are **bottom sheets**.
- **One composer for everything:** natural language on Home becomes actions (with optional preview), `@Name` routes to a bot, `$ cmd` runs in the shell.
- **Inline over modal:** approvals, jobs, tool steps, reviews, computer screens, suggestions all render as cards inside the thread.
- **Calm by default:** tool cards start collapsed, steps group into one line, no auto-expand, no technical noise in replies; colour only for state.
- **Stability:** keyboard never moves chrome; ticking numbers are tabular; the page morphs instead of re-rendering.

---

## M. Gaps, risks and open questions

1. **Not verified on a real phone** (per A-team's own docs): overlay edge glow, heads-up avatars, Orgo resume, Codespaces/Gemini with real tokens, routines with the app closed, TZ in PRoot, the Manager UI on small screens, the adaptive icon, connectors OAuth loopback. Treat these as untested behaviour when porting.
2. **Partial items in A-team** (`docs/HANDOFF-v2.15.md` 7–11): background jobs not wired, parallel tool calls not started, step-budget settings field missing, narration not wired. AM already covers background jobs and parallel scheduling; don't port the WIP.
3. **Two connector models** (A-team server-side argus-connect vs AM on-device `Connectors.kt`): needs a decision in slice 3/5. My recommendation: argus-connect for third-party apps.
4. **Chats model conflict:** A-team (v2.19) moves to many saved Manager chats; AM is "one mind". Needs a product decision (slice 4/5).
5. **Memory/profile/chats/routines are local-only** in A-team and not in the sync payload: migration needs the JSON backup path or an export endpoint.
6. **Gateway context pruning:** confirm AM's direct gateway calls send `blaze_context: "full"` (none found by grep) or route through `/v1/runs`.
7. **Deployed legacy names** stay (`*.blaze-app.workers.dev`, `X-Blaze-*`, `blaze-auto`, `blaze-auth`): the unified app must use them until a coordinated redeploy.
8. **Needle runtime:** AM has no Cactus runtime; choosing Cactus Android SDK vs WebView-hosted wasm vs OfflineBrain affects APK size (+35 MB model) and RAM.
9. **Package/key change:** A-team users cannot upgrade in place (different package `ai.argus.agents`, different signing key); plan a migration release.
10. **Licences to carry over:** Termux proot (GPL-2.0, shipped as separate `.so`), talloc (LGPL-3.0), libandroid-shmem (BSD-3), terminal-emulator (Apache-2.0), xterm.js (MIT, only if kept), Lucide (ISC), Simple Icons (CC0) + brand trademarks — see `THIRD_PARTY_NOTICES.md`.
11. **What I did not read line-by-line:** `cactus.js` (63 KB vendor runtime) and the binary model/wasm; `evals/suites` task bodies (counted and sampled via README). Everything else was read or structurally indexed function by function.
