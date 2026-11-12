# P1 real-phone checklist (P1-QA-01)

> Phase P1 → 0.53.0. The owner signs this on real arm64 phones after a Gradle/CI build of the P1 tickets
> (06-team-plan §9, "Real arm64 phone": signed checklist per phase). Nothing here can be ticked from a desk: every
> line is done on the phone with the release-candidate APK.
> Source of each line: the P1 ticket rows in `06-team-plan.md` §6, the §9 P1 abridged list, and the Deviations in `overhaul.md`.

## Phones and build

| | Phone A (required) | Phone B (required) |
|---|---|---|
| Class | Galaxy A07 / A12 class, 4 GB RAM, Android 13 or 14 | Any Android 14 or 15 phone with GLES 3 |
| Model / Android | | |
| APK (version, versionCode, commit) | | |
| Fresh install or upgrade from 0.52.x | do **both**: upgrade first (keeps 0.52 chats), then a clean install | upgrade |
| Date / tester | | |

How to mark: `✓` works as written · `✗` + one line of what happened (screenshot or `adb logcat` file name) · `n/a` + why.
A line with ✗ is either fixed in a 0.53.x patch or moved to Deviations by the owner; the phase is signed only with no
open ✗. Phone-wide checks (end of the list) are done on both phones.

## 1. Shell and modes (P1-SH-01, P1-SH-02)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| S1 | Open the app (look on, the default) | Opens on **Team**; top bar: me · notch · search · bell · +; Orbit ground | | |
| S2 | Tap the notch; pick Chat, Life, Desk, Team | Pill drops with Team · Chat · Life · Desk, selected one in ink; each pick changes mode and closes the pill | | |
| S3 | Pull the notch down a little and let go | Pill opens (no voice) | | |
| S4 | Pull the notch down past ~140 dp | The voice call screen opens; hanging up returns to the same mode | | |
| S5 | Swipe left/right on the page body | Moves between the four modes in notch order; the Chat drawer edge (left 24 dp, Chat mode) does **not** swallow the swipe elsewhere | | |
| S6 | Open the keyboard in the composer on each mode | Composer rides on the keyboard; top bar, notch and page **don't move or jump**; closing the keyboard puts everything back | | |
| S7 | Back from Chat / Life / Desk | Returns to Team; Back on Team leaves the app (no loop) | | |
| S8 | Bell with something waiting (a question or approval in Chat) | Bell has a dot; tapping opens Chat | | |
| S9 | + (New) | Sheet with Bot, Project, Private chat; each opens its flow (a new job is Desk → Jobs → +) | | |
| S10 | Long-press me | Light ↔ dark toggles with a toast | | |
| S11 | Type in the Team composer and send "what's on today?" | Goes to ARGUS (routing, §6) and Chat opens with the reply streaming | | |
| S12 | Team chips: Add job, Memory, Routines | Add job fills "Add a job: "; Memory opens Memory; Routines opens Schedule | | |
| S13 | Every tappable thing in the shell with a finger | Nothing smaller than a fingertip; no mis-taps between search / bell / + | | |
| S14 | Settings → Appearance → ARGUS look **off**, restart | The 0.52 shell comes back unchanged; turning it on again restores the ARGUS shell | | |

## 2. Team mode and bots (P1-TM-01, P1-TM-02, P1-TM-03, P1-TM-04)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| T1 | Look at Team on a fresh install | ARGUS row first with the gold Lead badge; Tempo and Inbox seeded; header "N bots + Manager · N jobs today · N overdue" | | |
| T2 | Long-press ARGUS | Quick sheet: Open, Memory, Edit soul — **no Delete, no Pin** | | |
| T3 | Long-press another bot → Pin; then Unpin | It joins / leaves the pin strip; survives an app restart | | |
| T4 | Swipe a bot row toward the end | Pin / Unpin; the row snaps back | | |
| T5 | Long-press a pin and drag it sideways | Reorders by whole slots; order survives a restart | | |
| T6 | Switch Tempo and Inbox off, open Team | Empty state "Build your team" with New bot and Demo team | | |
| T7 | Demo team | Nova, Atlas and Echo (reporting to Nova) and Vega (face Tilt) appear; pressing it again adds no duplicates | | |
| T8 | **Create a bot**: + → Bot → Blank; name it, pick an orb face and a colour (also "any colour" wheel), reports-to, Add bot | Bot appears on Team with that orb; opening it shows its thread with the intro | | |
| T9 | New bot → From a template; New bot → Describe it | Template fills the editor (name made unique); Describe sends the text to ARGUS, which drafts the bot | | |
| T10 | **Message the new bot** from its thread | Reply streams in the bot's thread with the activity line, then a "Worked for …" chip; unread badge on Team when you're elsewhere | | |
| T11 | Edit the bot (long-press → Edit soul / editor) and save; try to edit ARGUS's name | Edits stick; ARGUS shows the read-only card (voice/permissions only) | | |
| T12 | Delete a bot from its sheet; ask ARGUS "delete Echo" | Button delete asks first; chat delete needs a yes in the next message; its jobs become Unassigned | | |
| T13 | Ask ARGUS "list my team" / "make Nova's label Research" | agents_list answers; agent_update changes Nova; "update the Manager's name" is refused | | |
| T14 | A bot thread: start fresh (new chat) | Thread clears to a new generation; old rows not shown; unread cleared | | |

## 3. Bot thread (P1-SH-03)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| H1 | Give a bot a task that uses tools (web search + a file) | While running: one live activity line in plain words (never a raw tool name like `web_search`); after: a work chip "Worked for Xm Ys · N steps" above the reply | | |
| H2 | Tap the work chip | Steps listed in plain words, grouped (reads, commands…) | | |
| H3 | A tool card with Undo (e.g. a saved file) → Undo | Undone with a receipt | | |
| H4 | Delegate between bots (one asks another) | HandoffCard "Atlas → Ledger" with the question and the reply | | |
| H5 | TalkBack on the thread | Activity line is announced politely, not every token; cards read as one item each | | |

## 4. Chat mode (P1-SH-04)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| C1 | Open Chat with no messages | Manager sphere, serif greeting for the time of day, suggested asks; tapping an ask sends it | | |
| C2 | Upgrade install: open Chat | 0.52 chat history is intact and is the first chat in the drawer | | |
| C3 | Open the drawer with the history button and with a swipe from the left edge | Both open it; Android's back gesture on the left edge still works **outside** the 200 dp strip | | |
| C4 | Drawer sections | Pinned / Today / Yesterday / Previous 7 days / Older; project chats listed with their project; dot on a chat with an unseen reply | | |
| C5 | Search chats | Titles first, then message text with a snippet | | |
| C6 | ✎ New chat while the newest is empty; then after a message | Empty: stays put. After a message: a new chat starts, the context is fresh | | |
| C7 | ✎ New chat while ARGUS is still replying | The reply finishes in its own chat (keeps streaming there); the new chat starts clean after it | | |
| C8 | Long-press a chat → Rename / Pin / Copy / Delete, then Undo within 5 s | Each works; Undo restores the deleted chat; after 5 s it's gone | | |
| C9 | Open an older chat and send | Goes to the newest chat with the toast "Sent in your newest chat" | | |
| C10 | Private (ghost) toggle on, ask something, toggle off | Private chat not saved or listed; normal chat back unchanged | | |

## 5. Life mode (P1-SH-05)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| L1 | Open Life on a day with reminders/events | Serif day line ("Thursday — three things today, two still ahead."), Today strip with dots, now mark, all-day chips | | |
| L2 | Tap the Today strip | Schedule opens | | |
| L3 | Tap a life tile (e.g. Health) | Sends "Set up my life team, starting with Health" to ARGUS and opens Chat | | |
| L4 | Weather and the latest answer panels | Weather shows only when the Home board has a reading under 3 h; the answer card opens Chat | | |
| L5 | Signed out; setup unfinished | Sign-in card and "Finishing setup · N left" bar | | |
| L6 | Leave Life open across a minute boundary and add a reminder | Strip updates within a minute without reopening | | |

## 6. Routing and the plan sheet (P1-TM-06)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| R1 | Team composer: "@Atlas book a hotel in Cape Town for Friday" | A job for Atlas due Friday + HandoffCard "Atlas is on it" in Atlas's thread; Atlas starts | | |
| R2 | "Nova, scan competitor prices" (name, no @) | Routed to Nova the same way | | |
| R3 | "add a job for Nova tomorrow: weekly brief" with **airplane mode on** | Added offline (exact rules / Needle on the phone), receipt shown | | |
| R4 | A mixed command ("Atlas book the hotel, done with water plants, move send deck to Monday") with the preview on | "Here's what I'll do" sheet lists each action; a name or job that doesn't match shows as a miss; Apply N applies only the real ones | | |
| R5 | Turn the preview switch off on the sheet; send another command | Applies directly with a receipt; a **delete** command still shows the sheet first | | |
| R6 | In a bot thread, a command-ish message | Applied in the thread with no sheet (A-team behaviour) | | |
| R7 | `$ uname -a` in the composer | Runs in the shell (Alpine) and the output comes back | | |
| R8 | "@Argus how are you" and plain chat | Goes to ARGUS as conversation, not a command | | |
| R9 | Needle fast path timing | The sheet / receipt shows where it was read (Exact, On this phone + ms, Cloud, Quick rules); on-phone reads well under a second on Phone A | | |

## 7. Jobs and Desk (P1-TM-05, P1-TM-07)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| J1 | **Delegate from ARGUS** ("ask Atlas to find flights LUN→CPT for Jan 5"), then **swipe the app away** from Recents | The job keeps running; the result lands in Atlas's thread **and** a notification arrives; a summary goes back to ARGUS | | |
| J2 | Same with the screen off for 10 min (Doze) | Job finishes or resumes; nothing lost; no duplicate job | | |
| J3 | Two bots given jobs at once | Run in parallel (one running job per bot); the phone stays responsive | | |
| J4 | Kill the app while a job is running, reopen | The stale run is marked failed in plain words and can be retried | | |
| J5 | A job whose site is unreachable (airplane mode mid-run) | Plain-words failure, retries after 1 / 5 / 15 min, then stops; the log shows each try | | |
| J6 | Desk opens on Jobs | Segments Inbox · Jobs · Routines · Library · Projects · Computers with counts; numbers line up (tabular) | | |
| J7 | Jobs filters: Needs you / Today / Tomorrow / Upcoming / Overdue / Done / All / By bot / Unassigned; search | Each shows the right jobs; counts ignore search; overdue dates in red | | |
| J8 | FAB → New job: title, "next friday", a bot, high priority | Due read back ("Fri 20 Nov"); job appears with the priority tick | | |
| J9 | Open a failed job → Retry; a job → Done; → Cancel job | Each works; the sheet's log shows times | | |
| J10 | Team → Unassigned / All jobs | Opens Desk → Jobs on that filter | | |
| J11 | Desk → Projects | Same Projects list and behaviour as 0.52 (open, new, project chat) | | |
| J12 | Routines, Library, Computers links | Schedule; Gallery + Downloads; Terminal + Browser open | | |

## 8. Settings (P1-SH-06)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| G1 | Open me → You page | Name, email · plan, Plan & usage, the three groups, Sign out, version; search pill at the bottom | | |
| G2 | **Old settings links**: from chat ask "open voice settings", "open memory settings", "open permissions"; tap a 0.52 deep link / notification that opens a settings page | Every pre-0.53 page id opens its page (aliases) | | |
| G3 | Search settings for "voice", "theme", "nothing-here" | Results list; the last shows the empty state | | |
| G4 | Appearance: Theme Match phone / Dark / Light | Applies at once | | |
| G5 | Appearance: Ground Plain / Orbit / Cobalt night | Ground changes on every mode; text stays readable on all three in both themes | | |
| G6 | Handedness left | Sheets and empty states put their main button on the left | | |
| G7 | Reduced motion on (and with the phone's animations off) | Orbs, buttons and progress bars stop animating | | |

## 9. A-team backup import (P1-MG-01)

Needs A-team v2.18 with a team (or its Demo team), then A-team → Settings › Data › **Copy backup** (save it as a file too).
Where: ARGUS me → Settings › System › **Backup & import** → A-TEAM (also found by searching "a-team" or asking ARGUS to open "ateam").
At least Phone A; Phone B if a second A-team phone is at hand.

| # | Do | Expect | A | B |
|---|---|---|---|---|
| M1 | **Import A-team backup** (file): pick the saved .json in the system picker | Preview lines: "4 bots: 4 new", "N jobs: N new. N open jobs wait for your OK in Desk before they start", the Gemini line for Vega, "Not imported yet: …"; a "Use A-team's theme (…)" switch when the backup has a theme; Import / Cancel | | |
| M2 | **Paste a backup instead** → paste → Read | Same preview as M1 | | |
| M3 | Import, then Team | Every A-team bot with its name, role as label, **orb face and colour** from its look, reports-to kept (Atlas and Echo under Nova); pins kept; **no second Manager** | | |
| M4 | A bot that used Gemini in A-team (Vega in the demo) | Its soul notes "ran on Gemini (<model>) with your own key"; no model pinned | | |
| M5 | Desk → Jobs | Jobs with their title, notes, bot, due, priority and deps; a job waiting on an open one is blocked; done jobs in Done; each job's log says "Imported from A-team" | | |
| M6 | Imported open jobs | **Nothing starts by itself** (they need your OK in Desk); after the OK the job runs | | |
| M7 | Name and theme | ARGUS's name is set from A-team only if ARGUS had none; theme follows A-team only with the switch on | | |
| M8 | **Import the same backup again** | Preview says "Already imported: nothing to change" (button Done); after it, no duplicate bots or jobs | | |
| M9 | Edit an imported bot in ARGUS, then import the old backup again | Your ARGUS edit is kept ("changed here since, kept") | | |
| M10 | An A-team bot whose name is already taken in ARGUS | Imported once with a number ("Nova is called Nova 2 here"), not twice on re-import | | |
| M11 | A broken file, a non-A-team JSON, a photo, an empty paste | "That backup can't be imported, so nothing was changed" with the reasons; Read is off for an empty paste; nothing changes | | |
| M12 | Bot emails | Not imported (A-team's made-up addresses), as decided in P0-MG-01 | | |
| M13 | After import: restart the app, and later update to the next build | Imported team and jobs survive | | |

## 10. Phone-wide (both phones)

| # | Do | Expect | A | B |
|---|---|---|---|---|
| W1 | Phone font at its largest (**200 %** on Android 14+, max on 13) + display size large; walk Team, a thread, Chat + drawer, Life, Desk → Jobs, a job sheet, the bot editor, Settings | Nothing cut off or overlapping; rows grow instead of clipping; buttons still reachable; sheets scroll | | |
| W2 | TalkBack walk of the four modes | Notch reads as a button with the mode; modes as tabs; each Team row is one item ("Nova, 2 unread, …"); pins have Move left / right actions | | |
| W3 | Dark and light on each ground | Contrast holds (no grey-on-grey); orbit image never makes text hard to read | | |
| W4 | Phone A: use the app for 15 min across all modes with a job running | No ANR, no crash, no visible jank on mode swipes; app not killed in the background | | |
| W5 | Rotate / split-screen once | No crash; layout recovers | | |
| W6 | Low-RAM smoke on CI for this build | `OK (7 tests)`, stall gate 700 ms green (`node tools/smoke/check-smoke.mjs` checks the gate itself is unchanged) | | |
| W7 | Shots: `BLAZE_SHOTS=1` run of `P1ShotsTest` (17 screens × 12 cells) reviewed | Owner reviewed `build/shots/P1ShotsTest/*` | | |

## Sign-off

| Phone | All lines ✓ or n/a | Open ✗ (ids) | Signed by | Date |
|---|---|---|---|---|
| A | | | | |
| B | | | | |
