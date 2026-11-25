# Argus Phone instances (steps 1 + 1a)

What landed for steps 1 and 1a of the Argus Phone plan ([ARGUS-PHONE.md](ARGUS-PHONE.md), section 8; the vision doc
arrives with MR !79). Branch `feat/phone-instances`, MR !80. Not built yet (no APK until the owner says build).

## In one paragraph

Argus Phone can now hold several **instances**. Each one is a small, separate Argus Phone: for now its open app is the
browser (page, title, site, scroll), plus its recent steps, its lock, its waiting cards and its last error. Only one
instance is attached to the one live WebView at a time; the others are a few kilobytes in memory (warm) or only a
checkpoint file on disk (cold). Switching checkpoints the current instance and restores the other one into the same
WebView machinery, so the per-site profiles (sign-ins) work exactly as before. A background service keeps Argus Phone
running until the user shuts it down from its notification. Argus drives all of it with two catalog tools,
`phone_power` and `phone_instances`.

## Where the code is

Package `browser/phone/instances/` (beside the browser it wraps; when the app contract of step 3 lands, Argus Phone as a
whole can move out of `browser/` in one rename).

| File | Android? | What it does |
|---|---|---|
| `Instance.kt` | no | The model: `Instance`, `Tier` (cold / warm / live), `Owner`, `Lease`, `BrowserState`, `PendingCard`, `Step` (a ring of 12), `Budget` |
| `Leases.kt` | no | Locks as leases with an injected clock: acquire, renew, touch (every act by the owner renews), release, break, `canAct` refusal line |
| `InstanceStore.kt` | no | Checkpoints on disk: `filesDir/phone/instances/<id>/checkpoint.json`, versioned (`v: 1`), atomic (temp + rename), the previous one kept for corrupt-file recovery, an index, closed instances kept 7 days in `.closed/` |
| `InstanceRegistry.kt` | no | The instances in memory, lookup by id / name / task (exact first), the budget (`enforce`) and crash isolation (`isolate`) |
| `DeepState.kt` | no | The compact per-instance snapshot of ARGUS-PHONE.md 5g; secret-looking URL query values masked |
| `ArgusPhoneCore.kt` | no | The manager: power on / off, close all, new, use (switch), checkpoint, restore, reset, duplicate, close, locks, the 30-second sweep, the browser-tool hooks |
| `PhoneInstanceTools.kt` | no | The catalog tools `phone_power` and `phone_instances` |
| `ArgusPhone.kt` | yes | Binds the core to `PhoneBrowser` (wrapped with three small functions: `scrollY`, `restorePage`, `releaseNow`) and to the service; runs phone_* tools inside the active instance |
| `ArgusPhoneService.kt` | yes | The foreground service and its notification |

Tests: `PhoneInstancesTest` (JVM, 26 tests). The existing phone tests (PhoneBrowserTest, PhoneToolSimTest, SiteResolverTest, PhonePerfTest) run unchanged over the wrapped tools.

## Rules it keeps

- **Light.** Budgets from ARGUS-PHONE.md section 6: live 1 / warm 2 on a low-RAM phone (`PhonePolicy.LOW_RAM_BYTES`),
  live 2 / warm 4 otherwise. Over budget, the instance idle longest steps down first (live to warm to cold). An instance
  idle for `PhonePolicy.IDLE_MS` (5 min) goes cold, the same 5 minutes after which PhoneBrowser frees the WebView. Never
  demoted: one the user drives, one waiting on the user (hand-off or a pending card), one whose lock is active and
  working (renewed in the last 90 s), and the one the user is watching. At most 16 instances in all (cold ones cost
  only disk; the cap stops a runaway loop filling it). With one WebView, at most one instance is really live.
- **Breakable and recoverable.** Any failure inside an instance (an exception or an `Error` such as out of memory, but
  not a cancellation) is caught, written as that instance's last error, and the instance is marked restorable. Nothing
  reaches the agent loop, the chat or the other instances. `restore` brings back its last checkpoint; `reset` starts it
  clean after checkpointing it, so `restore` undoes a reset. A torn or corrupt checkpoint falls back to the previous one
  and is kept aside as `checkpoint.bad.json`; a corrupt index is rebuilt from the folders.
- **After Android ends the process**, nothing is attached: every instance comes back cold from its checkpoint the next
  time Argus Phone is opened or a task needs it, and `phone_power status` says how many came back.
- **Locks.** Argus (this chat), a background agent, a team member or a routine can lock an instance (default 5 min,
  1 to 60). The owner comes from the runtime (the chat id or the background agent's id), never from the model's words.
  Others can read a locked instance (`list`, `phone_read`) but not act in it: they get a plain refusal line naming the
  owner, the task and when the lock ends. A lapsed lock is checkpointed and freed by the sweep. Breaking someone else's
  lock asks the user (an approval card); the user's own actions on the Argus Phone screen are never blocked.
- **Secrets never reach the model.** Deep state carries no field values; URL query values named like token, code,
  session, key, password or signature show as `***`.
- **Model first, lean tools.** Two catalog rows with an `action` enum, registered in `Agent.tools()` only; not in
  `LeanTurn.CORE`, not in the prompt. Instance lookup is exact on what the model passed (id, name, task); nothing reads
  the user's message.

## The tools

- `phone_power`: `on` (start the service and a first instance), `off` (checkpoint every instance, free the WebView,
  everything cold, stop the service; the list is kept for the next `on`), `status`.
- `phone_instances`: `list` (status line + one deep-state object per instance), `new` (name, task; becomes the active
  one), `use` (by name, id or task), `lock` / `unlock` / `renew` (minutes, task, `break`), `open` (show it to the user on
  the Argus Phone screen), `close` (checkpoint, keep 7 days), `close_all`, `checkpoint`, `restore`, `reset`, `duplicate`
  (same page, no lock, no cards).
- `phone_open`, `phone_read`, `phone_act`, `phone_screenshot` and `phone_wait` take an optional `instance`; without it
  they act in the active instance. The first acting tool (`phone_open`, `phone_act`, `phone_handoff`) turns Argus Phone
  on and makes the first instance when there is none. Each call records its step in the instance, renews the caller's
  lock, and marks typing that was not submitted as unsaved (cleared when the page changes).
- Questions (approval cards) only where work could be lost or someone else's work stopped: closing or resetting an
  instance with typing not submitted, close all or shut down while another owner's lock is working, breaking a lock.

Deep state of one instance (keys appear only when they say something):

```json
{"id":"i…","name":"Bank","task":"check balance","tier":"live","active":true,"mode":"waiting_on_user",
 "lock":{"by":"background agent Research","kind":"agent","task":"check balance","ends_s":60,"working":true},
 "apps":[{"app":"browser","url":"https://bank.example/login?session=***&next=/home","title":"Sign in","site":"bank.example"}],
 "cards":[{"kind":"take_control","text":"Enter your code"}],"mem_kb":92160,"checkpoint_s_ago":5,
 "steps":["…"],"kept_because":"it waits on the user","idle_s":30}
```

`mem_kb` is an estimate per tier until PhonePerf measures it on a low-RAM phone. Running terminal commands join the
snapshot with the Terminal app (step 4).

## The background service

`ArgusPhoneService`, foreground service type **`specialUse`** with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation in
the manifest (permission `FOREGROUND_SERVICE_SPECIAL_USE`). Why not the types the app already declares: `dataSync` is
for transfers and is capped at 6 hours a day for apps targeting Android 15, and this service syncs nothing;
`mediaPlayback` plays nothing. Google Play needs the special-use reason in the Play Console declaration too (see "For
the owner" below). `WaveB4NoLinuxTest` now allows exactly one `specialUse` service, this one.

- Notification: "Argus Phone · N instances running", then what Argus is doing ("Phone: Tapping “Next”"), "Waiting on
  you: “Bank”" when an instance waits on the user, or "You're in control of …". Redrawn at most once a second. While
  the account lock is on it shows only "Running".
- Tap: opens Argus Phone (PhoneBrowserActivity) on the instance watched last. With nothing on screen it reopens that
  instance's page; with another instance on screen it switches, unless the user is in control or another owner's lock
  is working there.
- Expanded actions: **Shut down** (as `phone_power off`) and **Close all instances** (checkpoint and close every
  instance, keep the service; the next use starts one fresh instance).
- It holds no surface and draws nothing. Not sticky: after Android ends the process it waits until Argus Phone is
  opened or a task needs it (ARGUS-PHONE.md open question 5).
- Android 12+ refuses to start a foreground service from the background (for example a background agent's turn while
  Argus is closed). Then the instances still work, the service starts the next time, and `phone_power status` says so.

## Not in this step

- Private instances with their own empty browser profile (5e): every instance shares the per-site sign-ins, as today.
- Credentials / choice / approval cards from the page (step 2): `cards` holds only the hand-off for now.
- The home screen, the app contract, Terminal / Files as apps (steps 3 and 4).
- Instances per account space: they follow the account-spaces plan when it is built.
- Measured timings for switch and restore (ARGUS-PHONE.md section 6): a switch frees the WebView and makes a new one
  for the other instance (so one instance never sees another's back history); measure it on a low-RAM phone first.

## Phone check (after a build)

**Build to check (24 Nov 2026, 22:31 CAT):** commit `5459ca2c`, GitHub Actions Build APK run 45478728211 and Gate run
45478727974 both green on the mirror `roxaninchue/ARGUS-mobile`. APK: artifact `argus-debug-signed-5459ca2c…` (52 MB zip),
https://nightly.link/roxaninchue/ARGUS-mobile/actions/runs/45478728211/argus-debug-signed-5459ca2c8f32b10c841af2b33933357044456f8f.zip
(debug-signed with the public CI debug key; the unit suite for this branch runs in the sharded Gate, not in Build APK).

**Results:** pending (owner, on the phone). Record each as pass / fail with a note next to its number.

1. In chat: "open the-internet.herokuapp.com on your phone". The notification "Argus Phone · 1 instance running" shows
   what Argus is doing.
2. "Start a second Argus Phone instance called Shop and open example.com in it", then "go back to the first one": the
   herokuapp page comes back where it was (scroll too).
3. "List your phone instances": two, one live and one warm or cold, with pages and steps.
4. Lock: "lock the Shop instance for 10 minutes". From a background agent, ask it to use Shop: it is refused and says
   who holds it.
5. Leave Argus for 6 minutes: the instances go cold; the notification stays. Tap it: Argus Phone opens on the instance
   watched last, on its page.
6. Expand the notification: Close all instances (the notification stays; the next phone_open makes one fresh
   instance), then Shut down (the notification goes).
7. On a low-RAM phone: switching back and forth stays smooth and the app's memory doesn't climb with each instance.
8. Force-stop Argus while an instance is open, reopen, "phone status": the instances came back from their checkpoints.

## For the owner

- `specialUse` needs a Play Console declaration (the reason text is in the manifest). If it should not be used, the
  fallback is `dataSync` like AgentKeeper and JobKeeper: one line in the manifest and one in `ArgusPhoneService`, and
  the 6-hour daily cap applies once the app targets Android 15.
- Open question 5 (restart after Android ends the process) is answered "wait until opened or needed" for now.

## Step 1b: shared workspace, Recovered, write leases, install locks (2026-11-25)
Built on top of steps 1 + 1a (branch `feat/phone-1b`). ARGUS-PHONE.md 5e.
- **Shared workspace**: the agent's home (`filesDir/home`, `~` to the terminal and file tools). Every instance and the
  main app see it; lasting work goes there.
- **Scratch per instance**: `filesDir/phone/instances/<id>/scratch/`; `phone_instances list` shows each instance's
  `scratch` path.
- **Recovered**: close, close_all and reset move whatever is still in an instance's scratch to
  `~/Recovered/<instance name>/` (`<name> 2`… if taken) before anything else happens, and the reply says so. The
  checkpoint is kept 7 days as before; Recovered files stay until someone deletes them (`Workspace` in `Leases.kt`).
- **Write leases and conflict copies**: `FileLeases` (in `Leases.kt`, one per `ArgusPhoneCore`): one instance holds a
  5-minute write lease per file; another instance's save, or a save of a file changed since it was read, goes to
  `name (conflict from <instance> YYYY-MM-DD HHMM).ext` next to it, never over it. Close / reset frees the instance's
  leases. Nothing writes files from inside an instance yet (apps arrive in steps 3 and 4); they use this when they do.
- **Per-distro install locks**: `InstallLocks` (in `terminal/LinuxPm.kt`): `Terminal.run` takes the distro's lock for
  package changes (apk add/del/upgrade/update/fix, apt/apt-get install/remove/purge/upgrade/update…, dpkg -i/-r,
  pacman -S/-R/-U, dnf install/remove/upgrade…), so two installs never touch one Linux root at once; reads run free.
- **Tests**: `PhoneInstancesTest` (close moves scratch to Recovered, names never overwrite, one writer per file and
  conflict copies) and `LinuxReadyTest.installsAndUpgradesTakeTheDistroLock`. New code lives in existing files so the
  rename guard gains nothing.
- **Not in 1b**: private instances with their own empty browser profile; Recovered clean-up UI ("delete recovered
  files"); LinuxReady's own install step does not take the lock yet (it runs once, at set-up).

## Step 2: handoff cards (2026-11-25)
ARGUS-PHONE.md 4a, the handoff ladder. Branch `feat/phone-handoff-cards`.
- `phone_handoff` takes `kind`: `sign_in`, `code`, `choice`, `control` (default, the old Take control).
- **sign_in**: `Handoffs.signIn` reads the page's own fields (the account field: email, phone or username; the password,
  from the bridge's password / sensitive flags). A saved login for the site fills silently; otherwise the Secure Form
  card (the same card as `browser_secure_input`, "Save for this site" included) asks, and the answers are typed as
  `{{secret:name}}` placeholders, so the model never sees a value. Nothing is submitted: the model taps the page's
  button, which the confirm gate still guards. Card fields are never asked for (they stay Take control).
- **code**: the same for a one-time code (never saved).
- **choice**: the question with 2 to 5 of the page's options as a choice card (ask_user's card); returns the pick.
- **Approval**: unchanged, the confirm card on pay / book / send / submit / delete taps (PhonePolicy.gate).
- A cancel stops the sign-in, and no new card for that site for 10 minutes (the 0.30.5 rule).
- A page with a password field says how to fill it in the read (`fill_login`, else `phone_handoff kind=sign_in`), and
  the "no saved login" reply points at the card instead of Take control.
- Code: `HandoffCards` + `Handoffs` in `PhoneBrowserTools.kt`; the agent's cards in `Agent.kt` (`phoneCards`, next to
  `phoneInInstance`). Tests: `PhoneToolSimTest` (sign-in card, saved login, cancel, card fields, choice, read hint).
- **Not in step 2**: the instance chip's "Waiting on you" (the card and the notification show it today); a fresh read
  plus "what changed while you had it" after Hand back; CredAsk swapping a typed-in-chat password ask for the card
  while Argus Phone (not the background browser) has the page.

