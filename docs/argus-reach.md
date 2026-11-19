# Where Argus reaches, and where it doesn't (2026-11-19)

Owner ask (19 Nov 2026, with the notch glance layer): go through the app's surfaces, list where the in-app agent (Argus)
can't act or can't see yet, and plan how to connect it **without handing the main Argus every tool**.

**Owner decision, 19 Nov 2026 (round 2): on-demand tool groups first, specialists only for long jobs.** Surface tools stay
with the main Argus as **on-demand groups**: a group's schemas are sent only in a chat that needs that surface, the way
the Stage group is (ToolRouter matches the words, the chat used it before, or the surface is open). There is **no handoff
for normal use**, because a handoff starts a second model and that costs a whole extra call on every request. A **surface
specialist** is only for a **long multi-step job** on its surface (for the browser: filling a form, logging in, a
checkout, a crawl over many pages), where a focused agent with a short tool list beats a crowded main turn and the extra
model start is small next to the job. Browser first: its tools stay with Argus as an on-demand group; only long browser
jobs go to the browser specialist. Plan only; nothing in this file is coded yet. The round-1 plan below (a specialist per
surface, `delegate` for everything) is superseded where it says "hand off" for ordinary requests.

How this audit was made: by reading the tool registry (`policy/ActionClass.kt`, about 260 declared tools), the tool groups
(`agent/ToolRouter.kt`), the views list (`ui/Views.kt`), the specialists (`agent/Specialists.kt`) and the shell's modes
(`ui/team/*`, `ui/argus/*`) on main `54bebc41`. It is a source reading, not a test on a phone: a "gap" means no tool or
prompt line was found for it, not that a user tried and failed. Rows marked *(not verified)* are inferences.

## The problem with "more tools on the main Argus"

- Every turn already sends the core, the harness groups (terminal, screen, device, messages, google, browser), the
  always-on groups (schedule, study), the groups the chat used before and the Stage while it shows cards
  (docs/MODEL-FIRST.md). The browser group alone is 14 schemas (13 `browser_*` and the secure form); music and video tools carry long action enums.
- Each new surface tool makes every turn heavier for every model, and a weaker free model picks the wrong one more often
  (HARNESS MUSTS #3: one short core, the rest loaded when needed).
- So new reach should mostly **not** become more schemas on *every turn*. It should become schemas sent *only in the
  turns that need them* (an on-demand group), which keeps the payload small without a second model start.

## Design: on-demand groups first (owner, round 2)

1. **Every surface is a ToolRouter group** with its words (as `stage` has: "timer", "hide the chat", …), and is also
   added while its surface is open or on screen (as `stage` is while the Stage or its shelf has cards) and when the chat
   used it before (`ToolRouter.usedIn`). Not matched = not sent. Measure each move with `ToolPickSizeTest` and the evals.
2. **Moving a harness group to on-demand** (the browser group first, then the media groups) is the payload win: the 14
   browser schemas stop riding every turn and come back the moment the chat talks about a page, a site, a link or a
   search on the web, or the browser / Stage browser card is open. The model can still ask for the group by name
   (the existing load-a-group route), so a missed word costs one step, not the capability.
3. **Each group has one `read` action** that returns what its surface shows now (the glance tool's `list` is the pattern:
   one tool, a few actions, ids, paged so the answer stays small). That is how Argus "sees" a surface without a screenshot.
4. **Specialists only for long jobs** (next section). Short requests ("open this page", "pause the music", "turn on dark
   mode") are always the main Argus with the group loaded, never a handoff.

## Specialists, for long multi-step jobs only

1. **A specialist per surface is a `Specialists.Spec`** (already a persistent bot definition since P1-TM-01: prompt /
   soul, `groups`, `managedBy` = the Manager, `modelPref`). New built-ins, only where a long job exists: `navigator`
   (browser, first), `dj` (music), `projectionist` (video), `inbox` (exists), `desk` (batches). Settings and Life get
   no specialist (their requests are short). They are not shown on the Team roster
   (`kind` WORKER with a `surface` flag, or a separate list); the user meets them only as "Argus" doing the thing.
2. **Each one gets only its own groups.** The engine already cuts a bot's tool list to its groups
   (`agent/engines/LocalEngine.kt` `ToolSubset.of`), and PolicyGate already classes every call, so a specialist can't
   reach outside its surface and its sends / deletes still show the same approval cards (with the specialist's name).
3. **The main Argus hands a long job off through the existing `delegate` tool** (target = the surface specialist, a
   self-contained brief), in the background with its card as `delegate` already works. No `wait=true` inline handoff is
   needed any more: anything short enough to wait for is done by the main Argus with the on-demand group.
4. **When it is "long"** (the main Argus decides, guided by one prompt line): browser = a form, a login, a checkout, a
   task over several pages or sites; music = building a playlist or a long DJ set; desk = a batch over many files or jobs.
   One page, one control, one setting = not long.
5. **Roll out one surface at a time** behind a setting `surface_specialists` (default off), browser first. Check with the
   evals (`evals/`, 34 cases) that short browser requests still stay on the main Argus and long ones hand off.

Risks to watch: a missed group word (mitigated by the load-a-group route and `usedIn`), and for long jobs lost context
(the brief must be self-contained, as `delegate` already requires) and two agents talking about one thing (the
specialist's result is returned to the main Argus, which writes the one reply).

## Surface by surface

Priority: **P1** = real missing reach or the biggest payload win, do first; **P2** = reachable today but heavy or
half-blind; **P3** = small gaps; **—** = leave as it is.

| Surface | Current reach | Gap | Proposed (round 2: on-demand group first; a specialist only for long jobs) | Priority |
|---|---|---|---|---|
| Settings (You page, Appearance, Language, Notifications & proactivity, Stage on/off) | The self-control tool's `set_setting` changes 28 named on/off keys (heads-ups pause included) and opens a settings page; phone settings via `toggle_setting` / `device_setting` | Appearance (light / dark, ground, reduced motion, hand), app language, the Stage switch and most of `PrefCoerce.BOOL_KEYS` can't be set by Argus; the key list in SelfControl is hand-kept and drifts from `PrefCoerce` | on-demand `settings` group: one `settings` tool, actions `read` (current values of the user-facing prefs) / `set` (validated by `PrefCoerce`, the same rules the Settings sync uses) / `open` (a page). No specialist (settings changes are short). Remove `set_setting` from the main self-control tool once it lands | P1 |
| Browser (built-in, Stage browser card, Ask sheet `page_*`) | Full: 13 `browser_*` tools and `browser_secure_input`, `page_*` for the Ask sheet, `stage_show kind=browser` | Not reach but weight: the browser group rides every turn (harness group), 14 schemas | **Owner, 19 Nov:** the browser tools stay with the main Argus as an **on-demand group** (loaded when the chat is about a page, a site, a link or a web search, or the browser is open; no handoff for normal browsing). Only long multi-step jobs (forms, logins, checkouts, several pages) go to a `navigator` specialist with `browser_*` + `page_*` + `browser_secure_input` | P1 |
| Music (Music view, Stage music card, Spotify) | `music_ops` (play, queue, playlists, DJ, EQ, crossfade…), `media_control`, `spotify_*`, `stage_show kind=music` | What the Music screen shows (queue, library page, now playing) isn't readable as data *(not verified: `Awareness` may carry now playing)*; the long `music_ops` enum rides in the media group | on-demand `music` group (`music_ops`, `spotify_*`, `media_download`, `cover_art`; `read` = now playing + queue); `media_control` stays on every turn. `dj` specialist only for long jobs (a playlist, a long DJ set) | P2 |
| Video / Movies (Video view, Stage video card) | `video_ops` (play, series, subtitles, translate subtitles, recap, settings), `movies`, `clip_studio`, Stage video card | Playback position / what is open isn't readable *(not verified)*; same weight issue | on-demand `video` group (`video_ops`, `movies`, `clip_studio`, `media_download`; `read` = what plays and where). `projectionist` only for long jobs (a clip edit, a batch of subtitles) | P2 |
| Notifications, bell and Inbox | `recent_notifications`, `notif_conversations` / `notif_read` / `notif_reply`, `reply_to_notification`, `post_notification`; heads-ups can be paused (self-control) | Argus can't list what waits in the app's own Inbox (policy cards, questions, connects); can't tune heads-up sources one by one | on-demand `inbox` group: those tools + an `inbox` tool (`read` the waiting cards by id, `answer` only what the user told it to). The existing `inbox` specialist keeps the long triage runs | P2 |
| Desk (Inbox · Jobs · Routines · Library · Projects · Computers) | Jobs via `add_job` / `update_job` / `retry_job` / `agent_status`; routines via the schedule group; files via file tools; Computers via terminal / `ask_pc` | Desk's Inbox segment is still a placeholder (same gap as above); Library (Gallery + Downloads) has no single "what's in my library" read | on-demand `desk` group: job tools + `file_list` / `file_search` + `gallery` read actions; `read` = the Jobs filter counts the Desk shows. A `desk` specialist only for batches (many files or jobs) | P2 |
| Projects | `project_docs_search` / `project_docs_read`, `update_brief`, chats inside a project | No tool found to create, rename or delete a project, or to move a chat into one | `project` actions (create / rename / move chat) in the on-demand `desk` group | P2 |
| Team (roster, bot threads) | `agent_create` / `agent_update` / `agent_delete` (asks first), `note_team`, `delegate`, jobs | Team page pins (`Bot.pinned`, set from the Team page's quick sheet) aren't reachable; another bot's thread can't be read by the Manager *(not verified)* | stays with the main Argus (it *is* the Manager); add `pin` / `unpin` to the existing team tools | P3 |
| Life (day line, Today strip, calendar, board, to-dos, life logs) | `life_todo`, `home_widget_*`, `life_logs`, the schedule group, weather | Section order / hiding and the calendar's picked day aren't controllable; fine for now | on-demand `life` group: `home_widget_*`, `life_todo`, `life_logs`; `read` = the day line and the board. No specialist | P3 |
| Chat history drawer | `search_past_chats`, `search_chat`, `read_turn` | Renaming, pinning or deleting a chat window (`ManagerChats`) has no tool | two actions on the main Argus's existing chat search tool (its group is already on demand) | P3 |
| Apps page, App Store, site apps | `store_search` / `store_install` / `store_update` / `store_open` … | Installing a site as an app, pinning an app to the phone's home screen (`SiteInstaller`) has no tool | `store` actions in the on-demand `desk` group | P3 |
| Gallery, Camera | `gallery`, `gallery_ops` (on this day, backup plan, trash), the camera tool, `camera_math` | small: none found that matters | — | — |
| Learn (flashcards, quizzes) | `flashcards` (decks, grading, sources), Stage quiz / flashcards cards | none found | already one tool; could move to a `tutor` specialist later (it exists) | — |
| Stage | `stage_show` / `stage_update` / `stage_move` / `stage_close` / `stage_layout` | none; cards taken off the Stage were lost until this branch (now on the glance shelf) | stays on the main Argus (Stage-first rule needs it in every turn) | — |
| Glance layer (notch) | **new on this branch:** one `glance` tool, actions list (paged: offset / limit, 8 by default) / pin / unpin / remove / restore, in the Stage group; the shelf keeps every card (round 2) | sent only when the Stage or its shelf has cards (or the model loads the Stage group): already the on-demand pattern | stays on the main Argus | — |
| Design studio | `design` (read / list / open / write) | none found | — | — |
| Memory | `memory_*`, `remember`, `forget`, `recall_memory` | none | — | — |
| Voice / call | `call_control`, the in-app call | none found | — | — |
| Terminal, phone control | full (terminal tools, screen tools) | none; heavy but harness by owner choice | — | — |
| Spotlight, Living canvas, onboarding | no tools (user-side search; a view of Argus's own work; first run) | not a gap: nothing for Argus to do there | — | — |

## Order of work

1. On-demand `settings` group (P1, real missing reach), with its `read` / `set` / `open` tool on `PrefCoerce`.
2. Browser group from harness to **on-demand** (P1, owner decision 19 Nov), measured with ToolPickSizeTest and the evals;
   then the `navigator` specialist for long browser jobs only, behind `surface_specialists`.
3. Music, video, inbox and desk as on-demand groups with their `read` actions (P2); their specialists only where a long
   job exists (`dj`, `projectionist`, the existing `inbox`, `desk` batches).
4. The P3 actions as small additions to the group that owns the surface.

Open question for the owner: whether the specialists for long jobs should ever show on the Team roster (today: no, the
user meets them as "Argus" doing the job, with the delegate card).
