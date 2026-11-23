# Argus for Android

Argus is an AI assistant app for Android, written in Kotlin with Jetpack Compose (one activity, Material 3). The
package name stays `com.blaze.assistant` for install and update identity; everything the user sees is called Argus.

Current version on `main`: **0.55.6 (versionCode 215)**, built green by CI but held back (not released). Latest
green build for the phone check: **0.55.5 (versionCode 214)**. See [STATUS.md](STATUS.md) for what is built, released and pending.

## Where it is going

Argus is becoming a replacement for a PC: a native desktop on a connected monitor or TV (Argus Desk), keyboard and
mouse first, desktop-class apps, and heavy work offloaded to a PC or cloud box. The plan, phases and the Phase 0
slim-down decisions are in [docs/PC-REPLACEMENT.md](docs/PC-REPLACEMENT.md).

## What the app does today

- **Chat with a lean agent loop.** Each turn sends a short, cache-friendly system prompt with a small set of core tools
  plus `find_tools` and `load_skill`, so the model fetches anything else it needs (docs/LEAN-HARNESS.md). Models come
  from the Argus gateway (a Cloudflare Worker, kept in a separate GitLab project). Cloud tools (web search, page fetch,
  `find_tools`, `load_skill`) run inside the gateway's loop; phone tools come back to the phone as one signed, ordered
  batch and the turn resumes in the gateway. The model decides which tools to use: nothing in the app routes a
  message by its words (docs/MODEL-FIRST.md).
- **Living UI.** A second view next to Chat that shows the agent's work as a tree of cards: the prompt, browser
  pages, files, step logs, results and the answer. Version 0.2.4; the 0.3.0 plan is in
  docs/overhaul/living-ui-0.3.0-plan.md (not started).
- **Team bots.** The user can create bots for a role (a researcher, a writer, a coach), each with its own chat, and
  hand work to them; background agents keep working while the chat answers (docs/AGENTS.md).
- **Built-in browser** the agent drives in the background, with the live page visible on a card.
- **Phone control** through an AccessibilityService (read the screen, tap, type), alarms, timers, calendar, contacts,
  messages.
- **Proactive features:** reminders, routines, alarms and real heads-ups. The unasked "check-in" notifications
  (getting-to-know-you questions, call offers, routine ideas) are off by default since 0.55.6.
- Also: memory with full-text search (docs/MEMORY.md), a terminal with Alpine Linux in proot, Music, Video and
  Gallery apps, a Store tab, voice, and anonymous in-app feedback that the gateway files as GitLab issues.

## Build

Read [BUILDING.md](BUILDING.md) before pushing anything that will build, and docs/overhaul/BUILDING.md for the
"build clean, first try" checklist. In short:

1. JDK 17 (CI also uses 21), Android SDK 34, Go 1.26 for the two native libraries (`wgproxy/`, `blazerouter/`).
2. Build the Go libraries as the "Go libraries" step in `.github/workflows/mobile-build.yml` does.
3. `./gradlew :app:testDebugUnitTest --continue` and `./gradlew :app:assembleRelease`.

Builds normally run on GitHub Actions on the mirror `roxaninchue/ARGUS-mobile`, which follows GitLab `main`. A commit
whose message contains `[skip ci]`, or that only touches `*.md` / `docs/`, starts no build. No signing key lives in
this project: releases are signed outside it with the private release key.

The YouTube Store plug-in (Gradle module `:youtube`) is optional and private. `youtube/` is git-ignored; Gradle
includes it only when `youtube/build.gradle.kts` exists, so the app builds without it.

## Layout

| Path | What it holds |
|---|---|
| `app/` | The Android app. Code under `app/src/main/java/com/blaze/assistant/` (agent loop, UI, phone, browser, terminal, memory, team, proactive, update…); JVM and Robolectric tests under `app/src/test/`; in-app docs sources in `app/blaze-docs/` |
| `storeapi/` | Store source interface used by the Music / Video stores |
| `gateway/` | An old copy (0.3.1) of the gateway. The live gateway is developed and deployed from its own GitLab project; do not edit this copy |
| `evals/` | Agent evals copied from A-team (Node 22 + Python 3, run by hand; not in CI). See `evals/README.md` |
| `tools/` | Repo checks and scripts: the rename guard (`tools/check-no-blaze.mjs` + `tools/rename/`), the do-not-port check, smoke-test scripts, generators |
| `store/` | The Store tab's app catalog (`store/catalog.json`, `CATALOG_SCHEMA.md`) and the scripts that build and verify it |
| `wgproxy/`, `blazerouter/` | Go sources for the native libraries (browser VPN, on-phone model router) |
| `docs/` | Design and reference docs; index in [docs/README.md](docs/README.md) |
| `.github/workflows/` | CI on the GitHub mirror (`build.yml` Build APK, `mobile-build.yml`, smoke and Home checks) |

## Where to look

- [STATUS.md](STATUS.md): current state at the top, then the history of every version.
- [HANDOFF.md](HANDOFF.md): how to pick this project up today.
- [CHANGELOG.md](CHANGELOG.md): every version, newest first.
- [AGENTS.md](AGENTS.md): rules for anyone (person or agent) changing code.
- [overhaul.md](overhaul.md): the overhaul plan, harness rules and the handoff log.
- [docs/README.md](docs/README.md): index of all docs, including the archive.

## Licence

PolyForm Noncommercial 1.0.0, see [LICENSE.md](LICENSE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
