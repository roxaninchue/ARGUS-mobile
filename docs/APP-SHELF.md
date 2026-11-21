# App shelf: the apps you connect reach ARGUS

**21 Nov 2026 · Hark for Finn · MODEL-FIRST round 7.** Owner ask: "Find a feature or a specific part of the app that needs
work… in-depth reasoning about the design, how it will work for users. Don't overengineer the way the models work: code
simply, build around them, but make them aware of what's there. Update the Argus docs they read."

Code: `agent/AppShelf.kt` (pure), `connect/ConnectedApps.kt` (network), small hooks in `Agent.kt`, `ToolPick`, `Modes`,
`Specialists`, `policy/ActionClass.kt`, `PolicyCalls`, `PolicyRuntime`, `Cards`, `connect/ArgusApps.kt`.
Tests: `AppShelfTest` (10), `ModelFirstTest.connectedAppsAreAShelfNotAGuess`.

---

## 1. What is broken today

Settings › **Apps & connectors** (`ConnectionsPage` → `GatewayAppsSection`) shows the account's full catalogue from
argus-connect 0.2.0 (`GET /v1/me/apps`): 70 apps, 434 actions. Since 15 Nov it replaced the five built-in accounts. The
page tells the user: *"Tap one to connect it… sending or posting always asks you first."*

But **nothing in `agent/` reads that list.** Checked on main at 0.54.4:

| What the model is given | Source | Covers argus-connect apps? |
|---|---|---|
| `Connectors.promptBlock()` ("Connected accounts you can use with tools: …") | `connect/Connectors.kt` | No: only the five legacy on-phone accounts (Google, GitHub, Spotify, Notion, Slack), and only once connected the old way |
| `Connectors.tools()` | same | No: legacy tools only |
| `Mcp.tools()` | `connect/Mcp.kt` | Only if the user hand-adds argus-connect as an MCP server with a token, and then all its tools flood the turn |
| `request_app_connection` card | `policy/Cards.kt` | Checks `Connectors.connected` only, so a card for "Notion" never resolves when Notion is connected on the Apps page |
| `about.md` (what ARGUS reads about itself, in the app's docs folder) | the built-in docs | Not mentioned |

So a user connects Notion, Todoist or Google Calendar on the page, says *"add this to my reading list in Notion"*, and
ARGUS either says it can't, opens the browser and tries to log in to notion.so, or asks the user to connect Notion again.
The page makes a promise the agent can't keep. That's the single most visible gap between what the app shows and what
ARGUS can do, and it gets worse with every app the catalogue adds.

## 2. Who it's for and what they'll notice

- **Someone who connected an app and expects ARGUS to use it.** "What's on my Todoist today?" → ARGUS reads Todoist and
  answers. "Move my 3 pm with Stefano to 4" → it finds the event in Google Calendar and moves it (one card if they're
  not on Autopilot). "Email Mwila the notes" → a draft card with the recipient and the text; nothing goes until they tap Send.
- **Someone who hasn't connected anything.** ARGUS knows no apps are connected and that the catalogue exists. Asked to
  do something in Trello, it can say so in a line and put up the Connect card (which opens Apps & connectors), instead of
  guessing a browser route. It doesn't nag: whether to offer is the model's call, from the facts.
- **A non-English speaker.** A request in Nyanja or Bemba works the same way, because nothing matches English words to
  decide that Notion is involved; the model reads the message and the shelf.
- **Bots and specialists.** The Inbox manager (and any bot with the `connectors` group) gets the same two tools.
- **Plan mode.** Reads still run (search Notion, list events); anything that changes is refused with the usual Plan line.

What they see in the chat: a step line such as **"Checking what Notion can do"**, then **"Notion · search"**, and the answer.
Approval cards look like every other card in the app (an approval card, or a draft card for sends).

## 3. Design: a shelf of facts and two plain tools

Same pattern as skills (round 4), specialists (round 5) and views (round 6): tell the model what exists, give it tools
with clear schemas, let it decide. Nothing reads the message to decide whether an app is involved.

### 3.1 The shelf (every turn, SLOW tier, cached)

```
APPS the user connected in Apps & connectors (id — what it holds). app_tools with an app id lists its actions and their
arguments; app_run runs one. Actions that change, send or delete something show the user an approval card first.
- google_calendar — Events on your Google calendars
- notion — Search, read and write Notion pages, blocks and databases
Keyless, always on: hackernews, open_meteo, wikipedia.
Other apps can be connected: app_tools with no app lists them all, and request_app_connection asks the user to connect one.
```

- One line per **connected and switched-on** app, `id — first sentence of its catalogue description` (≤ 90 chars), sorted
  by id so the bytes change only when the list changes (prompt cache).
- **Keyless** apps the user left on are named on one line: they cost nothing to use and the model should know they exist.
- **Nothing connected** → one line saying so, plus the tail. That is what makes connect offers possible without a nudge
  rule: the model knows the catalogue exists and has the card tool.
- **Signed out** → no shelf and no tools (nothing would work).
- Caps: 30 app lines, 3,400 chars. Typical: 2–6 apps ≈ 450–750 chars (≈ 110–190 tokens). It sits right after the legacy
  connectors line, after the skill and specialist shelves, so it never breaks their cached prefix.

### 3.2 Two tools (ride every turn while signed in)

| Tool | Does | Policy class |
|---|---|---|
| `app_tools(app?)` | One app's actions: full name, `[reads]` / `[changes]`, one-line description, each argument with type, `*` if required, enum values, and its description. No app → the catalogue, ready apps first, the rest with their category. | READ |
| `app_run(tool, arguments)` | Runs one action (`POST /v1/me/execute` with the account's session token) and returns the app's JSON, cut at 12k chars with a note to ask for less. | the action's own class (§4) |

Why two tools and not 434 functions:

- **The request limits.** The gateway takes 60 KB per request and `ToolPick.MAX_TOOLS` is 48. Five connected apps are
  already 40–60 actions with long schemas; Google's eight apps alone have about 50.
- **The prompt cache.** Tool schemas sit before the history. Putting each app's actions in the tool list would change the
  cached prefix whenever the user connects or disconnects something, and every turn would pay for schemas it doesn't use.
- **Accuracy.** A model picking from 300 similar functions does worse than one that sees six app lines and reads the one
  app's actions when it needs them. `app_tools` is cheap (one call, cached 10 minutes) and gives the model the exact
  argument names, so `app_run` calls are right the first time.

Why not argus-connect's own "meta" MCP mode (`argus_search_tools` / `argus_execute_tool`): its execute tool carries no
read-only hint, so every call would be either an approval card (reads included) or none at all (sends included). The
phone needs to know per action, and `tools/list` with `?apps=<id>` gives exactly that (`annotations.readOnlyHint`).

Why not a keyword route ("notion" in the message → turn on Notion's tools): that is the pattern MODEL-FIRST removed in
rounds 1–6. It fails on other languages, on "my notes app", and on every app the regex doesn't list.

### 3.3 The flow

```
turn starts ──► system prompt: … SKILLS · SPECIALISTS · connectors · APPS shelf (cached) …
               tools: core + app_tools + app_run
model: "Todoist is on the shelf" ──► app_tools(app=todoist)
   phone ──► POST argus-connect/mcp?apps=todoist  {tools/list}   (Bearer bz_… session token)
   ◄── actions with readOnlyHint ─► cached 10 min, rendered as text
model ──► app_run(tool=todoist_list_tasks, arguments={filter:"today"})
   PolicyRuntime: AppShelf.classOf → READ → runs
   phone ──► POST argus-connect/v1/me/execute {name, arguments}
   ◄── {ok, data} ─► JSON to the model
model answers in plain words
```

Credentials never reach the phone or the model: argus-connect holds them per account, encrypted (AES-GCM, `ENC_KEY`).
The phone only sends the ARGUS session token it already uses for the Apps page.

### 3.4 Keeping the list fresh

The shelf reads the cached list (no network on the send path). If the list is older than 30 minutes, a background refresh
starts and the next turn has it. The Apps page refreshes it on open and after every connect, and every refresh drops the
cached action lists, so a newly connected app's actions are read fresh.

## 4. Approvals: structure, not prompt orders

The page promises "sending or posting always asks you first". That promise is kept by the policy, not by asking the
model to remember. `app_run` is classed by the action it runs (`AppShelf.classOf`, used by `PolicyCalls.classify`):

| The action | Class | What the user gets (main chat, default settings) |
|---|---|---|
| catalogue says read-only (`GET`, `readOnlyHint`) | READ | runs, no card |
| name's verb sends: send, reply, post, publish, comment, share, invite, notify, forward, dm… | SEND | draft card (recipient, subject, body read from the action's own arguments), nothing goes until Send |
| name's verb deletes: delete, remove, trash, destroy, revoke, clear, purge, unsubscribe | DELETE | approval card, even on Autopilot |
| anything else that changes (create, update, move, append…) | WRITE_REMOTE | runs on Autopilot; a card when Autopilot is off |

This reads the **catalogue's action names**, never the user's words, so it is the kind of permission gate MODEL-FIRST
keeps ("Still fine: safety and permission gates that don't guess intent"). The app id is cut off first, so a word in
the app's name never counts as the verb. When an action's list hasn't been read yet, the policy reads it before
classing (one cached call). Plan mode lets `app_run` through only for actions the catalogue marks read-only.

Known edge: a few read actions are `POST` in their API (Notion search, Calendar freebusy). The catalogue marks them
"changes", so off Autopilot they get a card. That's the safe direction; §8 has the one-line catalogue fix.

## 5. What the model is told, and what it isn't

Told (facts): which apps are connected and what each holds; that more exist and how to list them; that changes,
sends and deletes put a card in front of the user; per call, the actions' real argument names; plain results and plain
errors ("notion isn't connected for this account. request_app_connection asks the user to connect it.").

Not told: when to use an app, that it must check apps first, or any ALWAYS / NEVER line. No new guard, nudge or rewrite
of the model's reply. If a model ignores a connected app and opens the browser instead, the first fix is the shelf line's
wording (MODEL-FIRST: "first fix what it was given").

## 6. Phone checks (after the next build)

| # | Say this | Pass means |
|---|---|---|
| A1 | Connect Todoist (or Google Tasks) on Apps & connectors, then: "what's on my to-do list today?" | `app_tools` once, one `app_run` read, an answer with the real tasks, no card |
| A2 | "Add 'call ZESCO' to my Todoist for tomorrow" | one create action; on Autopilot it runs, off Autopilot one card |
| A3 | With Gmail connected: "email mwila@… that I'll be late" | a **draft card** with recipient and text; nothing sent before Send |
| A4 | "Delete my 3 pm event" (Google Calendar) | an approval card even on Autopilot |
| A5 | Nothing connected: "what's on my Trello board?" | a short line plus the Connect card; Connect opens Apps & connectors; once connected, the card resolves and ARGUS carries on |
| A6 | Plan mode: "check my Notion for the logo notes, then tidy them" | the search runs; the edit is refused with the Plan line |
| A7 | The same as A1 in Nyanja or Bemba | same result |
| A8 | Signed out | no APPS block, no `app_tools` / `app_run` in the request (check the debug request log) |

## 7. Cost

Shelf ≈ 110–190 tokens for a typical 2–6 apps, cached with the rest of the SLOW tier. The two tool schemas ≈ 1,000 chars.
`app_tools` adds one round trip the first time an app is used in 10 minutes. No extra model calls.

## 8. Next (not in this change)

1. **argus-connect: let the catalogue mark POST reads** (`readOnly: true` on Notion search, Calendar freebusy, search APIs)
   and, better, a `kind: "send" | "delete"` per action, so the phone stops reading verbs. Then `AppShelf.classOf` takes the
   catalogue's word first. Needs an argus-connect deploy (Finn confirms deploys).
2. **Bots with their own app grants** (`BotAppGrant`, source `argus-connect`): pass a bot's allowed apps as `?apps=` so a
   bot sees only its share. Today a bot with the `connectors` group sees the account's apps.
3. **Retire the legacy on-phone Connectors** once everyone has moved to the Apps page: their prompt line and tools then go,
   and `request_app_connection` checks only the account's list.
4. **Desktop and CLI**: the same shelf and two tools against the same endpoints, so every client uses apps the same way.
5. **Skills from argus-connect** (`/v1/me/skills`, the Argus originals) onto the skill shelf.

## 9. Code map

| File | What changed |
|---|---|
| `agent/AppShelf.kt` (new, pure) | shelf text, the two tool defs, action list parsing / rendering, `classOf`, results, step line |
| `connect/ConnectedApps.kt` (new) | cached list → shelf, `tools/list` per app (cached), `/v1/me/execute`, `classOf` for the policy |
| `connect/ArgusApps.kt` | `call` is internal (shared); a refresh drops cached action lists |
| `agent/Agent.kt` | +1 SLOW part (`apps`), +2 tool defs when signed in, +2 dispatch branches (no change to `runLoop`) |
| `agent/ToolPick.kt` | `core()` adds `AppShelf.turnNames()` so the two tools are never cut by the cap |
| `agent/Modes.kt` | Plan: `app_tools` allowed; `app_run` only for read-only actions |
| `agent/Specialists.kt` | the two tools belong to the `connectors` group |
| `policy/ActionClass.kt` | `app_tools` READ, `app_run` base WRITE_REMOTE |
| `policy/PolicyCalls.kt`, `PolicyRuntime.kt` | `app_run` takes its action's class; `request_app_connection` sees apps connected on the Apps page |
| `policy/Cards.kt` | a send through `app_run` gets a real draft card (recipients and body from the action's arguments; Edit writes back there) |
