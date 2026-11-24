# Bots and teams lost after clearing storage (0.55.14, 2026-11-24)

Status: gap closed in 0.55.17 (not built yet); the lost Health team itself can't be recovered.

## What the user saw
While working around the sign-in crash, Finn cleared Argus's storage. After 0.55.16 he signed back in with cloud sync on:
memory came back and Argus still talked about his Health Coach and its daily 7:00 check-in, but My team showed only
Argus. The Health team was gone.

## Versions
- Affected: every build before 0.55.17.
- Fixed: 0.55.17 (versionCode 226), bots, teams and routines sync.

## How we found it
Finn's screenshots (24 Nov 13:43-13:45): Team page empty except Argus; Argus's greeting cites the Health Coach from memory.
Code read: `CloudSync.adapters` synced only chats, memory, settings and designs. Bots and SOULs live in team.db, routines in
the `routines` pref, neither had an adapter.

## Root cause
Cloud sync (0.40.0) was built before the team (P1, 2026-11-17) and was never extended to it. Memory synced, so memory
outlived the bots it described.

## Fix
New kinds `bot` and `routine` (connect/SyncAdapters.kt `BotAdapter`, `RoutineAdapter`; wiring in CloudSync.kt; change
hooks in TeamStore and Routines). Routines gained a stable `uid` and an edit stamp `u`.

## Guard
- BotRoutineSyncTest: bots and teams sync, Argus and subagents never; newest wins; routines travel without shell jobs or
  calendar ids.
- Rule: a new kind of user data (anything the user makes) gets a sync adapter in the same release, or a line in
  CHANGELOG saying it stays on the phone.

## Lessons
- Clearing storage is a real path when a build crashes; anything that isn't synced is lost then.
- Memory that refers to data that didn't come back confuses Argus (it promised a check-in from a coach that no longer
  exists).
