# Lean harness (0.55.0)

Finn approved the design on 2026-11-22: work like opencode's harness for free models. Small and mid-size models pick
better from a short tool list, and a short, unchanging system prompt is cached by the providers, so the first token comes
sooner and every step costs fewer input tokens.

## What a lean turn sends
| Part | What | Where it lives |
|---|---|---|
| System prompt | `LeanTurn.PROMPT` (~1.2k chars, byte-identical every turn) + the stable rules (rapid message ids, verified results, secrets, the [context] block, identity), then a small live tail (name, language, memory, capped 1.5k) | on the phone (`agent/LeanTurn.kt`, `Agent.leanSystemPrompt`) |
| Core tools (10) | `ask_user`, `web_search`, `fetch_page`, `memory_search`, `memory_save`, `set_alarm`, `set_timer`, `message_contact`, `calendar_events`, `open_app`. Alarms, timers and messages never wait on a lookup | `LeanTurn.CORE` |
| Top 4 | the phone's four most-used other tools (ToolStats: clean + repaired calls), fixed for the turn | `LeanTurn.top` |
| Catalog tools | `find_tools` (search every tool and skill; found tools join from the next step) and `load_skill` (read one skill's steps) | `LeanTurn.findDef` / `loadSkillDef` |
| Also kept | tools this chat already called (follow-ups keep their tools) and what `find_tools` found this turn | `LeanTurn.filter` |

So "Hi!" and "alarm at 7" go out with ~12-16 tools instead of ~50, and the route line says `· lean`.

## The catalog (blaze.db)
`agent/LeanCatalog.kt`: table `lean_catalog` + FTS5 `lean_catalog_fts` (side tables like blaze_docs; schema version untouched).
- Gateway rows: `GET /v1/catalog` (gateway 0.9.38): the tools and skills the account's plan allows (`catalog_policy`), with
  a hash and an ETag. Synced in the background at most every 6 h (15 min after a failure); a turn never waits for it.
- Phone rows: every tool `Agent.tools()` registers, rewritten off the turn's path when the list changes, so the catalog works
  before the first sync, offline, and for tools the gateway doesn't list (MCP servers, the account's own).
- The gateway catalog data is generated from this repo: `node tools/gen-catalog.mjs` (tools from the sources, skills from
  `assets/skills/*/SKILL.md`), copied into Blaze-agent `gateway/src/catalog-data.ts`.

## find_tools and load_skill
- `find_tools` is a non-suspend `LeanTurn.find(...)`: it scores the catalog (FTS, bm25) and the turn's real tool list (name
  words x3, description words x1, a few synonyms such as bluetooth → device setting), marks up to 6 tools on the turn's
  `LeanTurn.State.found` and answers with one line per tool plus up to 3 skills. It never offers a tool the phone can't send.
- `load_skill` is a case in `Agent.runToolInner`'s `when` (with `find_tools`): it reads the skill as `use_skill` does; a
  catalog-only skill answers with its summary.

## Where it is wired (nothing in runLoop)
`Agent.runLoop` is at the JVM's 64 KB method limit, so no lean logic lives there and there is no `leanCall` helper:
- `Session.turnSystem()` (called from `requestMessages`, before ToolPick runs each step) decides whether the turn is lean
  (`LeanTurn.eligible`: main chat, not a background agent, specialist or routine; Settings `lean_harness`, default on), puts
  `LeanTurn.State` on the turn's `CallState` (forks share it) and returns the lean prompt.
- `ToolPick.core(simple, lean)` and `ToolPick.pick` read `LeanTurn.current()`: core + top + catalog tools + found + used.
- `LlmClient` adds `· lean` to the route label (`LeanTurn.tag()`).
- `Agent.tools()` adds the two catalog tools and hands the list to the catalog.

## Direct lane
Gateway 0.9.38 tickets carry `cat` (the plan's catalog hash) and the relay keeps the plan's whole catalog. `DirectLane.covers`
accepts a found tool when the phone's synced catalog is that same catalog (`LeanCatalog.covers`), so "turn on bluetooth"
runs `find_tools`, then `device_setting`, and stays direct. With an older gateway, or before the first sync, a tool outside
the ticket's own list takes the normal route for that step (never lost).

## On-phone readouts online
"speed stats", "tool stats" and "what's running" answer on the phone ONLINE too (`PhoneReadout`, `FastLane.readout`). Since
the 2026-11-16 model-first change the fast lane ran only offline, so online "speed stats" went to the model, which can't see
the speed log. Every other direct command still runs on the phone only offline.

## Limits stay on the gateway
The phone never enforces a plan: every model call still needs a ticket (or a charged request), a ticket only comes with
allowance left and is capped at today's remaining tokens, and the gateway drops any tool the plan doesn't allow.

## Phone check
1. "Hi!" → reply route shows `· lean`; "speed stats" afterwards shows ~12-16 tools sent and a lower first token than before.
2. "alarm at 7" → `· lean`, set_alarm runs at once (no find_tools), the alarm is set.
3. "turn on bluetooth" → a "Looking for the right tool" step (find_tools), then device_setting; the lane stays direct
   (needs gateway 0.9.38 deployed and one catalog sync).
4. "speed stats" online → answered instantly on the phone ("⚡ instant"), no model.
5. A send / delete / pay step still shows its approval card.
Switch off: Settings key `lean_harness` = false (blaze_self set_setting) brings back the full prompt and tool set.
