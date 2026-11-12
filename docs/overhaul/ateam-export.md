# A-team → ARGUS: export format and backup mapping (P0-MG-01)

> Status: **v1, 2026-11-12, for owner review.** Code: `team/ATeamExport.kt` (detect, validate, the pure mapping helpers).
> Fixtures: `app/src/test/resources/ateam/` (Demo team). Test: `ATeamExportTest` (JVM, 6/6). Importers: P1-MG-01
> (backup: bots + jobs + nickname/theme), P3-MG-01 (v1: memory, profile, routines), P8-MG-01 (A-team "Move to ARGUS").
> Source read: a-team @ 7c4b459a, `app/www/app.js` (addAgent, addTask, demo, Copy backup / Import), `manager.js`
> (ensureState, profile, routines), `memory.js`, `jobs.js`, `chat.js`.

## 1. The two files

| | A-team backup | `argus-team-export.v1` |
|---|---|---|
| Made by | Settings → Data → **Copy backup** (every A-team version today) | Settings → Data → **Export for ARGUS** (new, A-team v2.19.x, P3-MG-01) |
| Holds | `{ "agents": S.agents, "tasks": S.tasks }` exactly. Fields starting with `_` are included, so `_chat` (every bot's chat) comes along | bots + look, jobs, settings, profile (stated / inferred), bot and team memory, routines, proactivity, Manager chats only when the user ticks "Include Manager chats" |
| Never holds | settings, memory, the profile (`argus.profile.v1`), routines (`argus.manager.v1`): A-team keeps those on the phone only | tokens, keys, the vault, `settings.api`, gateway fields (A-team drops these on load) |
| Detected by | an object with an `agents` array and no `format` | `"format": "argus-team-export", "v": 1` |

Anything else is refused with "not an A-team backup or argus-team-export v1". A `v` above 1 is refused, never guessed.

## 2. `argus-team-export.v1`

```jsonc
{
  "format": "argus-team-export", "v": 1,
  "exportedAt": 1794470400000,                   // ms epoch
  "source": { "app": "a-team", "version": "2.19.0" },
  "bots": [{
    "id": "k3n9q2amhw1x", "name": "Nova", "role": "Research lead", "email": "nova@argus.ai",
    "portfolio": "…", "notes": "", "prompt": null,           // custom instructions or null
    "lead": null,                                            // bot id it reports to (A-team lead / reportsTo)
    "look": { "color": "#22c55e", "face": null },            // face 0..5 = Orbit, Tilt, Steep, Pure, Twin, Polar; null = from the name
    "gemini": null,                                          // A-team per-bot Gemini model (gm), own key
    "builtin": "manager",                                    // only on the Manager
    "createdAt": 1794466800000, "u": 1794466800100
  }],
  "jobs": [{
    "id": "t1a2b3cmhw30", "title": "Competitor pricing scan", "bot": "k3n9q2amhw1x",
    "due": "2026-11-12", "priority": "high",                 // low | normal | high
    "status": "todo", "run": null,                           // status todo|done; run queued|running|waiting|done|failed|null
    "deps": [], "notes": "", "createdAt": 0, "doneAt": null, "u": 0
  }],
  "settings": { "nickname": "Finn", "theme": "dark", "tz": "Africa/Lusaka", "pins": ["<bot id>"] },
  "profile": [{ "id": "p…", "kind": "rule", "text": "Never send email without asking first", "context": "",
                "stated": true, "at": 0, "src": "learn" }],  // stated = !inferred
  "memory": { "team": [{ "id", "text", "at", "src", "by" }], "bots": { "<bot id>": [ … ] } },
  "routines": [{ "id": "r…", "title": "Review Q4 deck", "kind": "remind",   // remind | task | brief | followup
                 "text": "…", "sched": { "type": "weekly", "time": "08:00", "days": ["mon"] },
                 "tz": "Africa/Lusaka", "bot": "manager", "active": true, "next": 0, "createdAt": 0 }],
  "proactivity": { "on": true, "at": "07:30", "quiet": ["22:00", "07:00"], "cap": 3, "mutes": [] },
  "chats": { "manager": [{ "role": "user", "content": "…", "t": 0 }] }       // optional
}
```

The names follow A-team's own (`S.agents`, `S.tasks`, `argus.profile.v1`, `argus.manager.v1`, `S.memory`) so A-team's
exporter is a copy with renames, not a translation: `agents→bots`, `agentId→bot`, `color/face→look`, `gm→gemini`,
`inferred→stated` (negated), `settings.me→nickname`, `_chat→chats` (Manager only, user-ticked). Dropped on export:
`x`, `y` (Compose graph positions), `managed_by`, `model`, `modelPref`, `tools`, `upd`, `queuedAt`, `S.sync`,
`settings.api`, `settings.instant`, `settings.maxSteps`, `settings.mgrBackup`, `settings.setupDone`, `view`, `catalog`.

## 3. Rules (both files unless marked; `ATeamExport.validate` returns one line per break)

1. Exactly one Manager: id `manager` or `builtin: "manager"`.
2. Bot ids are unique and non-empty; every bot has a name.
3. `lead` is null or another existing bot (not itself).
4. Colour is `#rrggbb`; face is null or 0..5. (v1: every bot has `look`.)
5. Job ids are unique; a job's bot is null or exists; `deps` name existing jobs, never itself, and form no loop.
6. `due` is null or `YYYY-MM-DD`; priority low/normal/high; status todo/done (done needs `doneAt`); run is null or
   queued/running/waiting/done/failed.
7. No key named token, api, apiKey, api_key, password, secret, vault, gateway, gw or key anywhere in the file.
8. v1 profile: kind is one of A-team's ten (name, style, goal, project, preference, person, date, place, rule, note);
   text 1–400 characters; `stated` is a boolean; neither text nor context looks like a secret (A-team's SECRET
   patterns: tokens, `password: …`, card-length digit runs).
9. v1 memory: every key under `bots` is a bot; each item's text is 1–600 characters and not a secret.
10. v1 routines: kind remind/task/brief/followup; a valid schedule (once: date + HH:MM; daily: HH:MM; weekly: days
    from sun..sat + HH:MM; cron: 5 fields); the bot exists; a time zone is set.
11. v1 proactivity: `quiet` is two HH:MM times; `cap` ≥ 0.
12. v1 chats: keys are bots; roles are user or assistant only (sys rows, tool parts and errors are not exported).
13. v1 settings: every pin is a bot.

A file that breaks any rule is not imported at all, and the importer shows the list. Nothing is half-imported.

## 4. Mapping into ARGUS

### 4.1 Bots → `team.db bots`
| A-team | ARGUS | Notes |
|---|---|---|
| Manager (`id`/`builtin` = manager) | the existing ARGUS Manager (BotKind.MANAGER) | Never a second Manager. Its locked look (gold, Twin) is ignored; its memory and chats go to the ARGUS Manager |
| other bot | new row, `kind = CUSTOM`, id `at:<A-team id>` | The `at:` prefix makes re-import an upsert (idempotent, P1-MG-01) |
| name, role | `name`, `label` = role | |
| portfolio, notes, prompt | SOUL.md draft (P3-AG-03): Who = role + portfolio, How = prompt, notes appended | Until P3 lands, kept in the bot's notes |
| look.color / color | `avatar.color` | |
| look.face / face (null → A-team `agentFace`: sum of the name's char codes mod 6) | `avatar.face` (OrbFace has the same order) | `ATeamExport.faceOf` |
| lead / reportsTo | `reports_to = at:<lead>`; a lead of `manager` → the ARGUS Manager | |
| gemini (gm) | not applied: a note on the bot ("used Gemini <model> with your own key in A-team") | P3-AG-06 decides pins |
| email | dropped (A-team bots' made-up addresses) | |
| createdAt, u | `created_at`, `u` | |
| settings.pins | `pinned = 1` | |

### 4.2 Jobs → `team.db jobs`
| A-team | ARGUS | Notes |
|---|---|---|
| id | `at:<id>` | |
| title, notes | `title`, `notes`; `brief` = notes or title | |
| bot / agentId | `bot_id` (mapped; null stays null) | |
| due, priority | `due`, `priority` | Same formats |
| status done | `status = 'done'`, `run = DONE`, `done_at` | |
| status todo | `status = 'todo'`, `run = QUEUED`, **`confirmed = 0`** | Imported work never starts by itself: the user confirms it first (Desk shows "From A-team: start?") |
| run failed | `run = FAILED` | |
| deps | `deps` (mapped ids) | |
| — | `source = 'a-team'` | |

### 4.3 Profile → `blaze.db memories` + `mem_scope` (P3-AG-02)
| kind | scope |
|---|---|
| name, style, rule, preference | `user:core` |
| person | `user:people` |
| place | `user:places` |
| date | `user:dates` |
| goal | `user:goals` |
| project | `user:projects` |
| note | `user:notes` |

`stated` → `mem_scope.stated`; `context` is kept in the line's text as A-team shows it ("text (context)").
`settings.nickname` becomes a `user:core` name line ("Call me Finn", stated) if the profile has none.
`settings.tz` sets the ARGUS time zone only when ARGUS has none.

### 4.4 Memory → `blaze.db memories` + `mem_scope`
`memory.team` → `team`; `memory.bots[<id>]` → `bot:at:<id>`; `memory.bots.manager` → `bot:<ARGUS Manager id>`.
Duplicates (same text, case-insensitive, same scope) are skipped, as A-team's own `MEM.add` does.

### 4.5 Routines → `team.db routines` (P6-PR-01)
| A-team | ARGUS |
|---|---|
| active = false | not imported |
| sched | `schedule` text: `once YYYY-MM-DD HH:MM`, `daily HH:MM`, `weekly mon,wed HH:MM`, `cron <5 fields>` (`ATeamExport.schedule`) |
| kind remind / task / brief | `kind = CRON`, `prompt` = text (remind: a notification; task: the bot runs it; brief: the morning brief) |
| kind followup | `kind = CHECKIN` |
| tz | `timezone` |
| bot | `bot_id` (mapped) |
| next | recomputed by ARGUS; A-team's value is ignored |

`proactivity` → the `proactivity` row: `enabled = on`, `quiet = "22:00-07:00"`, `daily_cap = cap`, `mutes`.

### 4.6 Chats (optional)
Only the Manager's chat, and only when the user ticked it in A-team, or (backup) agreed on import. It lands as
`message` rows in a new generation of the ARGUS Manager thread, under a "From A-team" divider. Other bots' `_chat`
in a backup is not imported (it holds tool output and file paths from A-team's workspace).

## 5. Fixtures

`app/src/test/resources/ateam/`:
- `demo-team.backup.json`: what A-team's **Demo team** button builds (`demo()` in app.js: Nova, Atlas and Echo
  reporting to Nova, Vega; eight jobs with "Draft Q4 strategy deck" waiting on "Competitor pricing scan", and one
  done "Weekly gateway report"), followed by Copy backup. The clock is fixed at 2026-11-12 09:00 CAT and ids are
  fixed. Vega was edited to face Tilt with a Gemini model, and the Manager has a short chat, so those paths are
  covered. **Built by hand from the code, not copied off a phone.** Swap in a real Copy backup when one is to hand.
- `demo-team.export.v1.json`: the same team as v1, plus seven profile lines (two inferred), memory for the team, two
  bots and the Manager, four routines (weekly, daily, cron, and one inactive once), proactivity, and the Manager chat.

## 6. Open points for the owner
- `confirmed = 0` on imported open jobs (nothing starts by itself). The alternative is to queue them as A-team did.
- Bot emails are dropped.
- Non-Manager chats in a backup are not imported.

## 7. As built in P1-MG-01 (backup import, 2026-11-12)
`team/ATeamImport.kt` (`ATeamImport.read` → `ATeamImporter.preview` / `apply`) + `ui/argus/settings/BackupPage.kt`
(Settings › Backup & import › Import A-team backup, ARGUS look; file via SAF or pasted text). Both files are taken; bots,
jobs, and from v1 the nickname, theme and pins. Differences from §4:
- Re-import is last-writer-wins on `u`: the file's `u` (and `createdAt`) is kept as the row's own, a row is written only
  when it is new or the file's `u` is newer. Same file twice = nothing written; an edit made in ARGUS afterwards wins.
- `portfolio` goes to `bots.portfolio` (team.db v3, P1-TM-04), not the SOUL. The SOUL = prompt, then "Notes: …", then the
  Gemini note ("In A-team this bot ran on Gemini (<model>) with your own key. ARGUS picks the model for now.").
- A taken name gets a number ("Nova 2"); `run` running → QUEUED, waiting → WAITING; a queued job with an open dep lands
  "blocked"; each new job gets the step "Imported from A-team". Nickname only when ARGUS has no name; theme unless the
  user switches "Use A-team's theme" off on the preview (system / auto → Match phone).
- Not imported yet (the preview says so): chats (the Manager's too), profile, memory, routines, proactivity → P3-MG-01.
