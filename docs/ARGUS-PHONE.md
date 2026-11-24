# Argus Phone: the vision

Owner vision (Jake, 2026-11-24). **This is a plan, not code.** Nothing here is built yet. Read it before you touch
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
- `phone_instances`: list, new, open, close, checkpoint, restore, reset, duplicate.
- `phone_app`: open an app in an instance, read its state, run one of its actions (or a batch of them).
- Today's browser tools (`phone_open`, `phone_read`, `phone_act`, `phone_wait`, `phone_screenshot`, `phone_handoff`,
  `phone_status`) stay, and become the browser app's actions over time.
- `phone_handoff` grows a `kind`: credentials, choice, approval, control (section 4a). Control is the last resort.

## 8. Suggested order of work

Each step on its own branch and merge request, Gate green on the head commit, commits `[skip ci]` until the owner says
build (AGENTS.md).

| Step | What ships |
|---|---|
| 1 | Instance manager around today's browser: cold / warm / live, checkpoint, restore, reset, crash isolation; `phone_instances` |
| 2 | Credentials card from the page's own field flags, then choice and approval cards; `phone_handoff` kinds |
| 3 | The app contract, the Argus Phone home screen, the browser as the first app |
| 4 | Files (root switch, install from inside, terminal link) and Terminal as apps |
| 5 | Music, Movie, Calendar as apps |
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
2. Should instances be per task by default (a fresh one for each task), or one long-lived instance that tasks share
   unless asked?
3. Should Files open the user's whole shared storage on first use, or only after they allow it once in Argus Phone?
4. Keep the name "Argus Phone"?
