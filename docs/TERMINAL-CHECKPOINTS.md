# Terminal checkpoints (rollbacks)

Branch `feat/terminal-checkpoints`. A checkpoint is a snapshot of the terminal's files that can be rolled back to, when the
user asks or when the agent broke something.

## What a checkpoint covers
- `home`: the phone shell's home (`~`, also `/root/phone` inside every Linux).
- Each installed Linux's own folders: `/root`, `/home`, `/etc`, `/opt`, `/srv`, `/usr/local` (scopes `<distro>-root`, …).
- With `system=true` (saved on request only): the whole Linux, installed packages included (`<distro>-system`).
- Never: caches and dependency folders that rebuild themselves (`.cache`, `.gradle`, `.m2`, `.npm`, `node_modules`,
  `__pycache__`, `.venv`…), files over 64 MB, and new content past 256 MB in one snapshot. A rollback leaves all of
  these exactly as they are (never deleted, never rewritten).

## How it is stored (`terminal/CheckpointStore.kt`, pure JVM, `CheckpointStoreTest`)
Like git: each file's content once, deflated, under `filesDir/checkpoints/objects/<sha256>`; one index per scope per
checkpoint (kind, exec bit, size, mtime, hash/link target, path). Snapshots are incremental (size+mtime match reuses the
hash, with the racy-git rule for files touched within 2 s of the index). A snapshot equal to the newest one is not taken
twice. Keeps the newest 25 automatic and 25 saved checkpoints; unreferenced content is collected.

## When they are taken (`terminal/TermCheckpoints.kt`)
- Automatically before every agent tool that can change files: `run_shell`, `run_code`, `file_write`, `file_edit`,
  `file_delete`, `download_file`, `git` (Agent.runToolInner, after the command is approved). The tool result ends with
  `[checkpoint cp-N holds the files as they were before this; terminal_checkpoint action=rollback id=cp-N undoes it]`,
  so the model knows where to go back to. The tool waits at most 20 s; a first big snapshot finishes in the background.
- On request: the `terminal_checkpoint` tool (`save`, `list`, `changes`, `rollback`, `delete`) or the Terminal's ⋮ menu
  → Checkpoints (save now, automatic on/off, tap one to see what changed and roll back).

## Rollback
Changed files are rewritten, new files removed, deleted files brought back, exec bits and symlinks restored. `paths`
restricts it to some paths. A checkpoint of the current state is taken first, so every rollback can be undone. The
agent's rollback / delete asks the user first (confirm card). Background jobs still running are named in the result.
