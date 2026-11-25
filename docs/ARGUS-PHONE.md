# Argus Phone: the vision

Owner vision (Jake, 2026-11-24; sections 3g, 3h, 5d to 5g added the same day). Jake: "After all, it is its phone.". **This is a plan, not code**, except steps 1 and 1a (instances, locks, deep state, the background service), which are on branch `feat/phone-instances` (MR !80): [PHONE-INSTANCES.md](PHONE-INSTANCES.md). Read it before you touch
`browser/phone/`, `terminal/`, `mediaapps/`, the Home board, or how the chat hands work to the user, and check new
Argus Phone work against the hard rules in section 1.

Related: [PC-REPLACEMENT.md](PC-REPLACEMENT.md) (Argus Desk, Phase 0 removals), [MODEL-FIRST.md](MODEL-FIRST.md),
[LEAN-TOOLS.md](LEAN-TOOLS.md), [TERMINAL-CHECKPOINTS.md](TERMINAL-CHECKPOINTS.md),
[overhaul/account-spaces-plan.md](overhaul/account-spaces-plan.md), [UI-RULES.md](UI-RULES.md).

## 0. In one paragraph

Argus Phone is Argus's own small phone, running inside the app: its own screen, its own home screen, its own apps and
its own windows. Argus drives it at full speed, and the user can watch it, take over and hand it back at any moment.
Today it is a browser (0.45.19, `browser/phone/`). It becomes a light operating system for the agent: a home screen,
several instances that can each break and be restored the way a terminal can, and Argus's own features (Browser,
Terminal, Files, Music, Movie, Calendar and more) as apps inside it. Work moves between Argus and the user through cards
in the chat, not through "please type your password".

## 1. Hard rules (most important first)

1. **Argus controls everything in Argus Phone, fast.** Every app inside Argus Phone gives Argus a structured surface:
   a compact state model it can read and typed actions it can call. Pixels and screenshots are for checking, never
   the main way to drive. If Argus can't do something an app's UI can do, that's a bug in the app's action list.
2. **Every instance stays light.** Only what the user is watching renders. Nothing runs that nobody needs. An idle
   instance costs disk, not RAM. A low-RAM phone (Finn's Samsung A12 class, under 3.4 GB, `PhonePolicy.LOW_RAM_BYTES`)
   must run it smoothly. This rule beats features: if a feature can't stay inside the budgets in section 6, it waits.
3. **Breakable and recoverable.** Any instance can crash, hang or be wrecked by a bad step without touching the chat,
   the other instances or the app. Every instance can go back to a checkpoint, the way the terminal can
   (TERMINAL-CHECKPOINTS.md).
4. **Control means Argus Phone, not the user's real phone.** Argus Phone is sandboxed on its own virtual display.
   This matches Phase 0 in PC-REPLACEMENT.md: the AccessibilityService phone control is removed and stays removed.
   "Control over everything" is control over everything *inside* Argus Phone and Argus's own apps.
5. **Model first, lean tools.** No keyword routers guessing which app or instance a message means (MODEL-FIRST.md).
   New tools go in the discoverable catalog, found with `find_tools`, never in every turn (LEAN-TOOLS.md). A few
   general tools with an `app` / `action` argument, not one tool per button.
6. **Secrets never reach the model.** Passwords, cards, codes and keys go from the Secure Form card or the vault
   straight into the field (`SecureInput`, `SecretVault`). The model sees that a field was filled, never the value.

## 2. Where it starts from (main, 0.55.17)

What already exists and should be reused rather than rewritten:

| Piece | File | What it gives us |
|---|---|---|
| Own screen | `browser/phone/PhoneSurface.kt` | Private VirtualDisplay + Presentation, frames only on change, double-buffered, no MediaProjection |
| Fast reads | `browser/phone/DomCache.kt`, `PhoneScripts.kt` | Live page model kept by an injected bridge as diffs; reads with no page round trip |
| Fast acts | `browser/phone/PhoneEngine.kt` | Event-based waits (react 400 ms, quiet 120 ms), no fixed sleeps |
| Policy | `browser/phone/PhonePolicy.kt` | Modes OFF / AGENT / WAITING / USER, per-site profiles, low-RAM config, idle release after 5 min, confirm gate, masked snapshots |
| Handoff | `PhoneBrowser.handoff`, `PhoneBrowserActivity.kt`, `PhonePipHost.kt` | Take control / Hand back, live view, picture-in-picture |
| Timings | `browser/phone/PhonePerf.kt` | Real p50 / p95 on the device ("page read", "act to verified") |
| Secure Form card | `ui/SecureFormCard.kt`, `SecretVault` | Masked fields in the chat, Keystore-sealed, never shown to the model |
| Terminal | `terminal/TermSessions.kt`, `Distros.kt`, `Linux.kt`, `TermCheckpoints.kt` | Real PTY tabs that outlive the screen; Alpine plus Ubuntu, Debian, Arch, Fedora, Manjaro under PRoot; Android sh / BusyBox; checkpoints and rollback |
| Media | `mediaapps/` (Music, Movie) | Players, libraries, sources, downloads |
| Batches | gateway `/v1/loop`, signed phone batches (0.9.39) | Several phone steps in one round trip |

## 3. Pillar 1: more than a browser

### 3a. A home screen
Argus Phone opens on its own home screen, not a blank page:
- An app grid and a dock (Browser, Terminal, Files, Music, Movie, Calendar first).
- An instances strip (section 5a): each running instance with its name and state.
- A status line: who is in control (Argus / waiting on you / you) and the step Argus is on.
- Opening an app is a launch inside Argus Phone, not a jump out to the main app's screen.

### 3b. Apps are views over the same engines
An Argus Phone app is **not** a copy of a feature. Music in Argus Phone plays through the same `MusicService` and
library as the main Music screen; Terminal uses the same `TermSessions`; Calendar uses the same calendar tools. One
engine, several hosts: the main app, Argus Phone, and later an Argus Desk window (PC-REPLACEMENT.md 1c). Build each app
once so it can sit in any of the three.

### 3c. The app contract
Every Argus Phone app implements one small interface (name to be settled in code review; sketch):

```kotlin
interface PhoneApp {
    val id: String                       // "files", "terminal", "music", ...
    fun state(): JSONObject              // compact model Argus reads: what's on screen, selection, focus, errors
    fun actions(): List<AppAction>       // typed actions with arguments; everything the UI can do
    suspend fun act(a: String, args: JSONObject): ActResult   // verified result, like PhoneEngine's
    fun checkpoint(): JSONObject         // small, enough to restore where it was
    suspend fun restore(cp: JSONObject)
    fun render(on: Boolean)              // draw only while someone watches
    val weight: Weight                   // LIGHT / MEDIUM / HEAVY, for the instance budget
}
```

The browser becomes the first `PhoneApp`: its `state()` is the DomCache model, its actions are today's `phone_act`.

### 3d. Files: unrestricted, connected to the terminals
- Full access: Android shared storage (all-files access, which PC-REPLACEMENT.md keeps), app storage, and every
  installed Linux root.
- A **root switch** at the top: Android, Alpine, Debian, Ubuntu, Arch, Fedora, Manjaro. Installed ones open; ones that
  aren't installed show **Install** (it runs `Distros.install`, with progress, pause and resume). Only what this
  phone's CPU supports is listed (`Distros.installable()`).
- Paths line up with the terminal: "Open terminal here" opens a tab in that distro at that folder, and the Files app can
  follow the active terminal tab's working directory.
- Copy, move, rename, delete, zip / unzip, open with an Argus Phone app, share to the chat. Deletes go through the
  confirm gate and the checkpoint (rule 3).

### 3e. Terminal
The same terminal, as an app: tabs per distro (Android sh, BusyBox, each installed Linux), install other distros from
inside, checkpoints and rollback as today. Argus runs commands through the same session the user sees, so the user can
watch or type into it.

### 3f. Music, Movie, Calendar, and more
Music and Movie as apps over `mediaapps/`, Calendar over the calendar tools. Later candidates, one per merge request:
notes, the code editor and documents (shared with Argus Desk phases 2 and 3).

### 3g. Office: our own Docs, Sheets and Slides, fully controllable by Argus
Argus Phone ships its own office apps, built to rule 1 from the start: anything the user can do in them, Argus can do
through actions, without tapping pixels.
- **Docs** (word processing): headings, styles, lists, tables, images, comments, track changes, find / replace,
  page setup. Opens and saves `.docx`, `.odt`, `.md`, `.txt`; exports PDF.
- **Sheets** (spreadsheets): cells, ranges, formulas with a real calculation engine, number formats, sorting and
  filters, multiple sheets, charts, CSV. Opens and saves `.xlsx`, `.ods`, `.csv`.
- **Slides** (presentations): layouts, text boxes, images, shapes, themes, speaker notes, present mode (on the phone,
  cast, or Argus Desk). Opens and saves `.pptx`, `.odp`; exports PDF.
- **PDF**: view, search, annotate, fill forms, sign, merge / split.

How they stay controllable and light:
- Each document is an **open, structured model** in memory (paragraphs and runs; cells and formulas; slides and
  shapes), and that model is the app's `state()`. Argus reads a compact view of it (an outline, the visible range, the
  current slide), never a picture, and asks for more only when it needs it.
- Actions are document-level and batchable: `insert`, `replace`, `style`, `set_cells`, `fill_formula`, `add_chart`,
  `add_slide`, `layout`, `export`. One call can write a whole section, a whole table of cells or a whole deck.
- Every edit goes through one undo history shared by the user and Argus, so the user can undo Argus's step and
  the other way round; edits are checkpointed with the instance (rule 3).
- Native Kotlin models and Compose renderers, with OOXML / ODF import and export. No heavy office suite in memory:
  a headless converter in a Linux root (for example LibreOffice) is allowed only on demand for rare conversions, and
  is shut down after.
- Same apps as Argus Desk phase 3 (PC-REPLACEMENT.md 1c): one engine, a phone host and a desk host.

### 3h. Custom apps Argus builds
Argus can build its own apps inside Argus Phone, for the user or for itself, and they become first-class apps: on the
home screen, in instances, and controllable like every other app (rule 1).
- **Format**: a custom app is a small web app (HTML, CSS and JavaScript or TypeScript) in a sandboxed WebView, with a
  manifest (name, icon, permissions, version) and an **app bridge** that implements the app contract (3c): the app
  declares its `state()` and `actions()` so Argus drives it through `phone_app`, never by tapping. The bridge is
  how an app reaches files, the terminal, the vault (through cards only) and other apps, each behind a permission
  in its manifest.
- **Right language for the job**: the UI is always web; heavy work runs as a backend in a Linux root (Python, Node,
  Go, Rust, whatever fits, installed there on demand) and talks to the UI through the bridge. Argus picks the
  language per app from what it must do (data and scripts: Python; real-time or web APIs: Node / TypeScript;
  speed-critical: Go or Rust) and says why in the app's README.
- **Designed properly**: every custom app uses the Argus design kit (BRAND.md tokens, the glass design system, the
  UI-RULES.md sheet and scroll rules), light and dark, portrait and landscape, accessible labels. Argus checks its
  own build with screenshots on the Argus Phone screen before calling it done.
- **Built like real software**: each app is a folder in the shared workspace (`Apps/<name>/`) under git, with a test
  page; every change is a commit, and a broken version rolls back to the last good one. Builds run in a Linux root
  in Argus Phone, never in the chat.
- **Light**: a custom app is cold (files only) until opened, shares the one WebView engine, and its backend process
  stops when the app goes cold.
- Built on what exists: the Design view's live preview and Site apps (`siteapps/`) are the starting point, not a
  second app system.

## 4. Pillar 2: a smoother handoff between Argus and the user

The aim: the user is asked for the least possible, in the easiest place, and Argus never stalls without saying why.

### 4a. The handoff ladder (Argus tries these in order)
1. **Fill it silently.** A saved login, card or key for this site is filled from the vault (`fill_login`).
2. **A card in the chat.** Anything the user can answer in a form gets a card, not Take control:
   - **Credentials card**: login, password, one-time code, card details, API key. Raised the moment the page's bridge
     reports a password / card / code field (DomCache already flags `pw` and `card`). It names the site and the field,
     uses the Secure Form card, fills the exact field through `SecureInput`, and offers "Save for this site".
   - **Choice card**: a question with options the page offers (a size, a time slot, an account picker).
   - **Approval card**: anything the confirm gate stops (pay, send, delete, submit), with what will happen.
3. **Take control.** Only for what no card can carry: a captcha, a drawing, a slider puzzle, a page that won't take a
   filled field. The live view opens on the same WebView (no reload); one tap hands back.

### 4b. Rules for every handoff
- The card is tied to its instance and step. Answering it resumes that exact step; nothing is re-asked.
- When the user hands back, Argus gets a fresh read plus a short "what changed while you had it" so it doesn't redo
  their work.
- Waiting is visible: the instance shows "Waiting on you" on its chip, its live card and, when the user is away, a
  notification. A stopped task closes its cards (the 0.30.5 rule).
- A message the user sends mid-task steers the running instance; it doesn't start a second one on the same page.

## 5. Pillar 3: connected to the rest of the app

### 5a. Instances
- An instance is one Argus Phone: its own home screen, open apps, browser sessions and terminal tabs, like a window or
  a terminal tab. Several can exist; each task can get its own, or reuse one by name.
- Each has a state: **cold** (only its checkpoint on disk), **warm** (kept in memory, nothing drawing), **live**
  (drawing because someone watches). Moving between them is cheap and automatic (section 6).
- Each can be **checkpointed, reset, restored, duplicated or closed**, by the user or by Argus. A crashed instance
  shows "This instance stopped" with Restore and Reset, and nothing else is affected.
- Instances belong to the signed-in account's space (account-spaces plan): switching accounts never shows another
  account's instances, cookies or files.

### 5b. Where instances show up
- **Chat**: a live card per instance the task uses (status, the step, Watch / Take control).
- **Home board**: an Argus Phone tile with running instances.
- **Argus Desk**: each instance can be a desk window on the second screen.
- **Notifications** only for "waiting on you" and finished work.

### 5c. Shared data
Apps inside Argus Phone see the same library, sources, calendar, files and vault as the main app. Nothing is synced
twice; nothing is kept in a second copy.

### 5d. Running in the background until killed
- Once started, Argus Phone keeps running when the user leaves the app, until the user shuts it down. It runs as a
  foreground service with its own notification (service type to be settled against Android's rules; the app already
  declares `dataSync` and `mediaPlayback`).
- **The notification**: "Argus Phone · 3 instances running" plus what Argus is doing, or "Waiting on you". Tapping
  it opens Argus Phone on the instance last watched.
- **Expanded, it offers**:
  - **Shut down**: checkpoints every instance, closes them, stops the service and removes the notification.
  - **Close all instances**: checkpoints and closes every instance but keeps Argus Phone on; the next time it is
    opened it starts one fresh instance.
  - (Optional) **Pause Argus**: Argus stops acting everywhere; the instances stay as they are.
- Lightness in the background: the service holds no surfaces and draws nothing; instances follow the cold / warm / live
  rules in section 6. If Android kills the process anyway, every instance comes back from its last checkpoint the
  next time Argus Phone starts, and the notification says so.

### 5e. No clashes between instances, and no lost work
Instances are separate so they can't trample each other, and saved work lives outside them so closing or deleting one
never deletes anything important.
- **Separate by default**: each instance has its own scratch folder, its own terminal tabs, its own open apps and
  undo history, and its own browser tabs. A crash or a bad step stays in that instance.
- **Work lives in the shared workspace, not in the instance**: files the user or Argus saves (documents, downloads,
  code, exports) go to shared folders that every instance and the main app see. An instance holds only scratch and
  open state.
- **Deleting an instance never deletes work**: before it goes, anything unsaved or still in its scratch folder is
  moved to `Recovered/<instance name>/` in the shared workspace and its last checkpoint is kept for 7 days. Only an
  explicit "delete recovered files" removes them.
- **Writing the same thing from two instances**: one instance holds a short write lease on a file at a time; another
  instance opens it read-only or works on a copy. When two edits still collide, the second is saved as a conflict
  copy next to the file, never over it, and the user (or Argus) merges. Office documents can later merge edits
  through their models.
- **Browser sessions**: site logins are shared across instances by default, so the user signs in once; an instance
  can be marked private to get its own empty profile. Two instances on the same site don't share tabs or forms.
- **Linux roots**: one root per distro is shared, so installed tools exist everywhere; installs and upgrades
  (apk / apt / pacman / dnf) take a per-distro lock so two instances never run them at once.
- **Sync**: every change to the shared workspace is visible at once in every instance; instances never keep their
  own copy of a shared file.

### 5f. Argus's power over instances
Argus can, on its own or on the user's word:
- **Turn Argus Phone on and off**: start the service and a first instance when a task needs it; shut it down when
  told to ("shut down the phone"), checkpointing first.
- **Spin up new instances** for parallel work (one per task or per site), within the budgets in section 6; when the
  budget is full, the oldest idle instance goes cold rather than a new one being refused.
- **Use existing instances**: pick one by name or by the task it belongs to, instead of starting over on a page or
  folder that is already open.
- **Close, reset, restore or duplicate** an instance, and **close all**.
- Anything that would discard unsaved work asks first, through an approval card; a plain close checkpoints and
  recovers as in 5e, so it needs no question.

### 5g. Who owns an instance, and Argus's deep view of it
- **Argus decides** whether a task gets a fresh instance or reuses one (Jake, 2026-11-24). No user setting.
- **Locks**: Argus itself, a background agent, a team member or a routine can **lock an instance** for a long task.
  A lock names its owner and task, and is renewed while the owner works (a lease); if the owner stops or crashes, the
  lease runs out and the instance is free again, after a checkpoint. Others can read a locked instance but not act
  in it; the user can always take control or break a lock, with an approval card if work is in progress.
- **Deep state awareness**: Argus can read, for every instance, at any time and cheaply: owner and lock, cold / warm /
  live, open apps and each app's state summary, the page or folder each is on, running terminal commands and their
  exit codes, unsaved changes, pending cards (waiting on the user), memory use, last checkpoint, last error, and a
  short history of recent steps. That snapshot is what `phone_instances` returns, so choosing "reuse or new" is
  informed, never a guess.

## 6. Speed and lightness budgets

Targets to measure on a low-RAM phone (A12 class) with `PhonePerf` extended to every app. Numbers are starting targets,
to be confirmed by the first measurements and then guarded by tests.

| What | Target |
|---|---|
| Read an app's state (from its model, no screenshot) | p50 under 50 ms |
| Act until verified (tap, type, run) | p50 under 500 ms, p95 under 1.5 s |
| Open an app inside an instance (warm) | under 150 ms |
| Switch the watched instance | under 100 ms to first frame |
| Cold instance restore from checkpoint | under 1.5 s |
| Live frames | only on change; at most 5 fps while acting, 2 idle (1 / 3 on low RAM), as today |
| Live (drawing) instances at once | 1 on low RAM, 2 otherwise; others warm or cold |
| Warm instances at once | 2 on low RAM, 4 otherwise; the oldest goes cold first |
| Idle warm instance goes cold | after 5 min (`PhonePolicy.IDLE_MS`), never while the user drives or Argus waits on them |

How to stay light:
- One engine per kind, shared: one WebView profile store (per-site profiles as today), one PRoot per distro with tabs
  sharing it, one media service.
- No process, VM or display per instance unless it is live. A cold instance is a checkpoint file.
- Frames only for the watched instance; Argus itself works from state models, so it needs no frames.
- Several steps per round trip (phone batches) instead of one tool call per tap.

## 7. Tools Argus gets (catalog, not every turn)

Kept small, all found with `find_tools`, all described in one line each on the shelf:
- `phone_power`: on, off (checkpoints first), status (running or not, instances, what each is doing).
- `phone_instances`: list (with the deep state of 5g), new, use (by name or task), lock / unlock, open, close, close all,
  checkpoint, restore, reset, duplicate.
- `phone_build_app`: create or change a custom app (3h); scaffolds, builds, screenshots and commits it.
- `phone_app`: open an app in an instance, read its state, run one of its actions (or a batch of them). Office apps
  work through this too.
- Today's browser tools (`phone_open`, `phone_read`, `phone_act`, `phone_wait`, `phone_screenshot`, `phone_handoff`,
  `phone_status`) stay, and become the browser app's actions over time.
- `phone_handoff` grows a `kind`: credentials, choice, approval, control (section 4a). Control is the last resort.

## 8. Suggested order of work

Each step on its own branch and merge request, Gate green on the head commit, commits `[skip ci]` until the owner says
build (AGENTS.md).

| Step | What ships |
|---|---|
| 1 | Instance manager around today's browser: cold / warm / live, checkpoint, restore, reset, crash isolation; background service and notification; `phone_power`, `phone_instances`. Built: [PHONE-INSTANCES.md](PHONE-INSTANCES.md) (MR !80) |
| 1a | Instance locks (leases) and the deep state snapshot (5g). Built with step 1: [PHONE-INSTANCES.md](PHONE-INSTANCES.md) |
| 1b | Shared workspace, Recovered folder, write leases, conflict copies, per-distro install locks (5e). Built: [PHONE-INSTANCES.md](PHONE-INSTANCES.md) step 1b |
| 2 | Credentials card from the page's own field flags, then choice and approval cards; `phone_handoff` kinds. Built: [PHONE-INSTANCES.md](PHONE-INSTANCES.md) step 2 |
| 3 | The app contract, the Argus Phone home screen, the browser as the first app |
| 4 | Files (root switch, install from inside, terminal link) and Terminal as apps |
| 5 | Music, Movie, Calendar as apps |
| 5b | Office: Docs, then Sheets, then Slides, then PDF (3g), each with its model, actions and OOXML import / export |
| 5c | Custom apps: manifest, app bridge, design kit, build in a Linux root (3h) |
| 6 | Instances as Argus Desk windows; Home board tile |

## 9. Do not

- Drive the user's real phone (accessibility, overlays, usage stats): removed in Phase 0, stays removed.
- Make pixel tapping or screenshots the main way Argus works an app.
- Copy an engine to make an app; wrap the one that exists.
- Render frames nobody watches, or keep a cold instance's process alive.
- Add a keyword router to pick the app or instance, or put new tools in every turn.
- Ask the user to type a secret in the chat, or show a secret's value to the model.

## 10. Open questions for Jake

1. Today's rule says Argus Phone does no gaming and no banking (owner, 23 Oct). Does that still stand now that it is
   more than a browser?
2. Answered (Jake, 2026-11-24): Argus decides per task, and any agent can lock an instance for a long task (5g).
3. Should Files open the user's whole shared storage on first use, or only after they allow it once in Argus Phone?
4. Keep the name "Argus Phone"?
5. After Android kills the process, should Argus Phone restart itself, or wait until it is opened or a task needs it?
6. How long should Recovered files and deleted instances' checkpoints be kept (7 days proposed)?
