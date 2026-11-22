# Account spaces: one space per account on the phone

Status: **plan only, not started** (2026-11-22, 23:40 CAT). Jake chose "separate space" per account. No code changed yet.
Do not start coding until Jake approves this plan (standing rule: discuss first).

## The bug (found 2026-11-22 on Jake's phone)

Jake signed out of one Argus account and signed in to another. The new account still saw the old account's chats
("Hey Roxan!"), its chat list, memory and settings.

Cause, in `connect/BlazeAccount.kt`:

- `signOut()` calls `setSession(null, null)`, which only clears the session token (`SESSION`) and the stored user (`USER`),
  signs the terminal CLI out and recomputes `AccountGate`. Nothing else on the phone is touched.
- Everything Argus keeps lives in app-wide storage with no owner: `team.db` (chats, `TeamSchema.NAME`), `mind.db`
  (memory), `longchat.db` (chat search index), the `"blaze"` shared prefs (`Settings`), and files under `filesDir`
  (`chats/`, `secret_vault.json`, `skills.json`, `plan_memory.json`, `screen_memory.json`, `taste.json`, `watchers.json`,
  `servers.json`, `money/`, `myvoice/`, ...).
- On a fresh sign-in `setSession` runs `KeySync.syncSoon()` and `SkillSync.syncSoon()`. `SkillSync` merges and **pushes
  local skills up** ("sent yours up"), and `KeySync` does the same for the MCP server list (the only thing it still syncs
  since v0.25.0). So the previous account's skills and MCP servers can be copied **into the new account** on the server.
  The leak goes both ways.

## How other apps do it

- ChatGPT, Claude and Gemini keep chats and memory on their servers under the account, and the phone holds a cache that is
  wiped when you sign out.
- Gmail and other multi-account apps keep a separate local copy per account and switch between them.

Argus is local-first ("I live on this phone"), so it follows the Gmail model: the data stays local, but each account
gets its own space.

## Proposed design: park and restore

Pointing every store at a per-account folder would touch dozens of singletons (`TeamDb.get`, `Mind.Db`, `Settings`, every
`File(filesDir, ...)`). Instead, keep all code reading the usual paths, and swap what sits at those paths when the
account changes:

1. **Owner tag.** A device-level pref `space_owner` (outside the account data, for example its own small prefs file
   `argus_device`) records the user id that owns the data currently at the live paths. It is set at sign-in.
2. **Sign out.** Run the sync one last time, then move the account-owned data (the list below) into
   `noBackupFilesDir/spaces/<userId>/`. Clear `space_owner`, then restart the process
   (`ProcessPhoenix`-style: start the launch intent and `Runtime.exit`) so no singleton keeps the old data in memory.
3. **Sign in as X.**
   - If `spaces/X/` exists, move it back to the live paths.
   - If not, start empty with first-run defaults.
   - Then set `space_owner = X`, restart the process, and only after that run `KeySync`/`SkillSync`, so they only ever see
     X's own data.
4. **Guard.** At app start, if a session is present and `space_owner` is a different user (or blank while data
   exists), do not sync and do not show the data. Park it under its owner (or under `spaces/unowned/` when unknown)
   first. This also catches a crash halfway through a switch.
5. **Moves are renames** on the same filesystem (fast, atomic per file). Each step writes a small `switch.json` journal so
   an interrupted switch finishes on the next start.

### Account-owned data (moves with the account)

The source of truth for this list should be `SetupGate`'s existing "copy shared_prefs and Argus's own data files" list
(`setup/SetupGate.kt` around line 98), reused rather than duplicated. It covers:

- databases: `team.db`, `mind.db`, `longchat.db` (plus `-wal`/`-shm`/`-journal`)
- shared prefs: `"blaze"` (Settings), `"blaze_tasks"`, `"blaze_phone"`, `"store"`
- files: `chats/`, `secret_vault.json`, `skills.json`, `plan_memory.json`, `screen_memory.json`, `taste.json`,
  `watchers.json`, `servers.json`, `turn_feedback.json`, `tool_stats.json`, `state.json`, `money/`, `myvoice/`,
  `orchestra/`, `backup/`

### Device-level data (stays put, shared by all accounts)

Proposed: downloaded models (`offline-brain`, `wake-model`, `spk-model`, `needle`), the Linux rootfs (`usr/`, `home`),
media caches (`mediaapps/`), `"terminal"`, `"browser"`, `"linux_ready"` and `blaze_browser_trackers` prefs, and
accessibility/hand settings. **Check each one with Jake**, especially the terminal home and the phone browser's cookies,
which may hold personal sessions.

## Open questions for Jake

1. **Jake's phone right now** holds Roxan's data while signed in as "L". On the first run of the fix, which account owns
   it? Options: (a) Roxan's account (we know the name from the chat; the fix can't tell by itself), (b) a one-time "Whose
   data is this?" screen, or (c) park it as `unowned` and offer it back to the next account that signs in.
2. Should the phone browser's cookies and logins and the terminal home be per account (safer) or shared (simpler)?
3. Should there be a visible account switcher (Gmail-style, no sign-out needed), or is sign-out then sign-in enough for
   now?
4. Server side: have skills or MCP servers already been pushed into the wrong account? Check the "L" account's skills on
   the gateway and remove anything that came from Roxan's phone data. This is only done on Jake's word.

## Tests to add

- A pure `AccountSpace` planner (no Android): given the owner, the incoming user and the existing spaces, it returns the
  moves. Unit-test it the same way as `ModelListTest`.
- `KeySync`/`SkillSync` refuse to run when `space_owner` doesn't match the signed-in user.
- A list-parity test: every file `SetupGate` backs up is either in the account list or explicitly in the device list,
  so a new store can't silently be left shared.

## Constraints (from AGENTS.md / STATUS standing rules)

- Do not touch `applicationId`. Add nothing inside `Agent.runLoop`.
- The build follows the normal build-commit checklist and only happens on the owner's word.
