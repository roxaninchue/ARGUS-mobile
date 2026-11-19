# Where Argus reaches, and where it doesn't (2026-11-19)

Owner ask (19 Nov 2026, with the notch glance layer): go through the app's surfaces, list where the in-app agent (Argus)
can't act or can't see yet, and plan how to connect it **without handing the main Argus every tool**. The core design is
embedded **surface specialists**: one small Argus per surface (music, browser, video, …), each with only its own few
tools, that the main Argus hands work to.

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
- So new reach should mostly **not** become more schemas on the main Argus. The main Argus should know *which surface
  can do it* and hand off; the surface's specialist carries the tools.

## Design: surface specialists

1. **A specialist per surface is a `Specialists.Spec`** (already a persistent bot definition since P1-TM-01: prompt /
   soul, `groups`, `managedBy` = the Manager, `modelPref`). New built-ins: `dj` (music), `projectionist` (video),
   `navigator` (browser), `settings`, `inbox` (exists; widened), `desk`, `life`. They are not shown on the Team roster
   (`kind` WORKER with a `surface` flag, or a separate list); the user meets them only as "Argus" doing the thing.
2. **Each one gets only its own groups.** The engine already cuts a bot's tool list to its groups
   (`agent/engines/LocalEngine.kt` `ToolSubset.of`), and PolicyGate already classes every call, so a specialist can't
   reach outside its surface and its sends / deletes still show the same approval cards (with the specialist's name).
3. **The main Argus hands off through the existing `delegate` tool**, widened rather than a new tool:
   - targets include the surface specialists (`delegate target=dj task="play something calm for studying"`);
   - a new `wait=true` runs the handoff inline and returns its result in the same turn (today `delegate` always runs in
     the background with its own card; "pause the music" can't wait for a background card);
   - the main prompt gets one roster line ("Surfaces: dj (music), projectionist (video), navigator (browser), settings,
     inbox, desk, life: hand off with delegate") instead of their schemas.
4. **Each specialist has one `read` action on its own tool** that returns what its surface shows now (the glance tool's
   `list` is the pattern: one tool, a few actions, ids the model can act on). That is how it "sees" the screen without a
   screenshot.
5. **Keep a tiny fast path on the main Argus** for one-word controls that must feel instant: `media_control`
   (play / pause / next) and `stage_show` stay on the main list; anything longer goes to the specialist.
6. **Roll out one surface at a time** behind a setting `surface_specialists` (default off): when on, that surface's group
   leaves the main pick (ToolPick / ToolRouter) and is only in its specialist's subset. Check each step with
   `ToolPickSizeTest` (payload) and the evals (`evals/`, 34 cases) before turning the next one on.

Risks to watch: an extra model call per handoff (latency; mitigated by `wait=true` on a fast route and the fast path),
lost context (the brief must be self-contained, as `delegate` already requires), and two agents talking about one thing
(the specialist's result is returned to the main Argus, which writes the one reply).

## Surface by surface

Priority: **P1** = real missing reach or the biggest payload win, do first; **P2** = reachable today but heavy or
half-blind; **P3** = small gaps; **—** = leave as it is.

| Surface | Current reach | Gap | Proposed specialist + tools | Priority |
|---|---|---|---|---|
| Settings (You page, Appearance, Language, Notifications & proactivity, Stage on/off) | The self-control tool's `set_setting` changes 28 named on/off keys (heads-ups pause included) and opens a settings page; phone settings via `toggle_setting` / `device_setting` | Appearance (light / dark, ground, reduced motion, hand), app language, the Stage switch and most of `PrefCoerce.BOOL_KEYS` can't be set by Argus; the key list in SelfControl is hand-kept and drifts from `PrefCoerce` | `settings`: one `settings` tool, actions `read` (current values of the user-facing prefs) / `set` (validated by `PrefCoerce`, the same rules the Settings sync uses) / `open` (a page). Remove `set_setting` from the main self-control tool once it lands | P1 |
| Browser (built-in, Stage browser card, Ask sheet `page_*`) | Full: 13 `browser_*` tools and `browser_secure_input`, `page_*` for the Ask sheet, `stage_show kind=browser` | Not reach but weight: the browser group rides every turn (harness group), 14 schemas | `navigator`: `browser_*` + `page_*` + `browser_secure_input`; main keeps `stage_show` and hands off "go to / fill / log in". Needs the owner's OK, since the harness groups were an owner call (14 Nov) | P1 |
| Music (Music view, Stage music card, Spotify) | `music_ops` (play, queue, playlists, DJ, EQ, crossfade…), `media_control`, `spotify_*`, `stage_show kind=music` | What the Music screen shows (queue, library page, now playing) isn't readable as data *(not verified: `Awareness` may carry now playing)*; the long `music_ops` enum rides in the media group | `dj`: `music_ops`, `spotify_*`, `media_download`, `cover_art`, `stage_update` on the music card; `read` = now playing + queue | P2 |
| Video / Movies (Video view, Stage video card) | `video_ops` (play, series, subtitles, translate subtitles, recap, settings), `movies`, `clip_studio`, Stage video card | Playback position / what is open isn't readable *(not verified)*; same weight issue | `projectionist`: `video_ops`, `movies`, `clip_studio`, `media_download`; `read` = what plays and where | P2 |
| Notifications, bell and Inbox | `recent_notifications`, `notif_conversations` / `notif_read` / `notif_reply`, `reply_to_notification`, `post_notification`; heads-ups can be paused (self-control) | Argus can't list what waits in the app's own Inbox (policy cards, questions, connects); can't tune heads-up sources one by one | widen the existing `inbox` specialist: those tools + an `inbox` tool (`read` the waiting cards by id, `answer` only what the user told it to) | P2 |
| Desk (Inbox · Jobs · Routines · Library · Projects · Computers) | Jobs via `add_job` / `update_job` / `retry_job` / `agent_status`; routines via the schedule group; files via file tools; Computers via terminal / `ask_pc` | Desk's Inbox segment is still a placeholder (same gap as above); Library (Gallery + Downloads) has no single "what's in my library" read | `desk`: job tools + `file_list` / `file_search` + `gallery` read actions; `read` = the Jobs filter counts the Desk shows | P2 |
| Projects | `project_docs_search` / `project_docs_read`, `update_brief`, chats inside a project | No tool found to create, rename or delete a project, or to move a chat into one | add `project` actions (create / rename / move chat) to `desk`, not to the main Argus | P2 |
| Team (roster, bot threads) | `agent_create` / `agent_update` / `agent_delete` (asks first), `note_team`, `delegate`, jobs | Team page pins (`Bot.pinned`, set from the Team page's quick sheet) aren't reachable; another bot's thread can't be read by the Manager *(not verified)* | stays with the main Argus (it *is* the Manager); add `pin` / `unpin` to the existing team tools | P3 |
| Life (day line, Today strip, calendar, board, to-dos, life logs) | `life_todo`, `home_widget_*`, `life_logs`, the schedule group, weather | Section order / hiding and the calendar's picked day aren't controllable; fine for now | `life`: `home_widget_*`, `life_todo`, `life_logs`; `read` = the day line and the board | P3 |
| Chat history drawer | `search_past_chats`, `search_chat`, `read_turn` | Renaming, pinning or deleting a chat window (`ManagerChats`) has no tool | two actions on the main Argus's existing chat search tool, or `desk` | P3 |
| Apps page, App Store, site apps | `store_search` / `store_install` / `store_update` / `store_open` … | Installing a site as an app, pinning an app to the phone's home screen (`SiteInstaller`) has no tool | `store` actions in `desk` | P3 |
| Gallery, Camera | `gallery`, `gallery_ops` (on this day, backup plan, trash), the camera tool, `camera_math` | small: none found that matters | — | — |
| Learn (flashcards, quizzes) | `flashcards` (decks, grading, sources), Stage quiz / flashcards cards | none found | already one tool; could move to a `tutor` specialist later (it exists) | — |
| Stage | `stage_show` / `stage_update` / `stage_move` / `stage_close` / `stage_layout` | none; cards taken off the Stage were lost until this branch (now on the glance shelf) | stays on the main Argus (Stage-first rule needs it in every turn) | — |
| Glance layer (notch) | **new on this branch:** one `glance` tool, actions list / pin / unpin / remove / restore, in the Stage group | sent only when the Stage or its shelf has cards (or the model loads the Stage group) | stays on the main Argus | — |
| Design studio | `design` (read / list / open / write) | none found | — | — |
| Memory | `memory_*`, `remember`, `forget`, `recall_memory` | none | — | — |
| Voice / call | `call_control`, the in-app call | none found | — | — |
| Terminal, phone control | full (terminal tools, screen tools) | none; heavy but harness by owner choice | — | — |
| Spotlight, Living canvas, onboarding | no tools (user-side search; a view of Argus's own work; first run) | not a gap: nothing for Argus to do there | — | — |

## Order of work

1. `settings` specialist (P1, real missing reach), with its `read` / `set` / `open` tool on `PrefCoerce`.
2. `delegate` gains surface targets and `wait=true`; the roster line in the prompt.
3. `navigator` (P1) behind `surface_specialists`, measured with ToolPickSizeTest and the evals; ask the owner first,
   since it moves a harness group.
4. `dj`, `projectionist`, the widened `inbox`, then `desk` (P2).
5. The P3 actions as small additions to the specialist that owns the surface.

Open questions for the owner: whether the browser group may leave the main pick (it was put there on purpose), and
whether surface specialists should ever show on the Team roster.
