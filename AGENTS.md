# For agents working in this repo

Start with **STATUS.md** ("Current state" and "Standing rules") and **HANDOFF.md** (how to build, test, check CI and
commit). **Since 2026-11-23 (Finn): every change goes on its own branch, then a merge request into `main`; the GitLab
pipeline (`.gitlab-ci.yml`: checks, compile, `:app:testDebugUnitTest`) must be green before it merges. No `[skip ci]`
on those branches.** The merge commit into `main` carries `[skip ci]` so the GitHub mirror builds no APK until Finn
says build. Direction: docs/PC-REPLACEMENT.md.

Read **docs/MODEL-FIRST.md** before changing anything the model sees or anything that routes a user's message.
Build the system around the model. No keyword routers that guess intent, no shouting or contradictory prompt rules,
and no second lane answering beside the main one. `ModelFirstTest` guards this.

Skills and specialists reach the model as shelves (docs/SKILL-SHELF.md): each line is the first sentence of a skill's
description or a specialist's prompt, capped at 10 words after the dash, so write that sentence to say when to use it.

How a chat turn works today: docs/LEAN-HARNESS.md. Agent loop and background agents: docs/AGENTS.md. Harness rules and
the handoff log: overhaul.md. All docs: docs/README.md.
