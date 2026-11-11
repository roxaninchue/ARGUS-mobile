# P5-TM-01 — TeamBus (ticket log)

> Branch `overhaul/p5-tm-01`, cut from `overhaul/p5` @ ed67a51e. Code commit `ad7b0d89`. Ticket row: `06-team-plan.md` §6.6.
> Covers BH-12 (bot trigger envelope), BH-17 (provenance badges), BH-42 (stay_silent), BH-45 / BH-57 (send_to_bot, hop cap),
> MM-13 (bots talking to bots), NEW-05 (HandoffCard + team-talk line). `Agent.kt`, `memories` schema, CI and versions untouched.

## What was built
| File | What |
|---|---|
| `team/BotMessaging.kt` (pure) | The rules: `MAX_HOPS = 3`, `THREAD_BUDGET = 20`, `maxHops(bot)`; `Provenance` (user · bot · routine · voice · system ↔ Row `from` string) + `badge()` + `trigger()` (BH-12) + `botEnvelope()` (the receiving turn's prompt); `HandoffStatus` (live states) + `Handoff` (card data, `fields()/body()/of()`, `title`, `talk`, `spoken`); `talkLine()` ("Atlas ↔ Ledger · 3 messages"); `Redact.pass()` (scope redaction); `sideDoor()` + `looksLikeToolCall()` (no side doors); `schemas()` for `send_to_bot` / `stay_silent`. |
| `team/TeamBus.kt` (pure over ports) | The runtime: `TeamBus.send/exec/staySilent/endTurn`, reads `thread/threadsOf/transcript/talk/pairs`, `viewer(bot)`; the seams `BotRunner`, `BotReply`, `MemoryView`, `ThreadBudget`; `BusThread` (a `bot_threads` row), `BusState`, `BotThreadPort` + `MemoryBotThreadPort`. |
| `ui/team/HandoffCard.kt` (Compose, ARGUS kit only) | `HandoffCard(h, fromColor, toColor, transcript, onOpen, fromOrb, toOrb)`: both orbs joined by a dotted lead edge that flows while live, ask, status line (polite live region), reply preview, "n private notes withheld", the collapsed talk line expanding to the transcript with provenance badges, "Open conversation". `talkItems(rows, names)` maps transcript rows. |
| `policy/ActionClass.kt` | `send_to_bot` → WRITE_LOCAL, `stay_silent` → READ (keeps ActionClassCoverageTest honest for the new tools). |
| `test/.../team/TeamBusTest.kt` | 22 JVM tests (below). |

## API for later tickets (Rooms TM-02, Workers TM-03, People SH-04, voice P8-VC-01)
```kotlin
val bus = TeamBus(team, threads /*BotThreads*/, botThreadPort, runner = BotRunner { id, prompt, budget -> … BotReply(text, calls) },
                  memory = MemoryView { botId -> visibleLines(botId) }, later = { work -> executor.post(work) })
bus.send(from = "atlas", to = "Ledger", message = "Budget left?", expectsReply = true, thread = null, via = null, originJob = jobId) // → Sent
bus.exec(name, argsJson, callerBot, via = currentThreadBudget, jobId)   // tool adapter; Out(ok, text, threadId, silent)
bus.staySilent(botId, via)                                              // BH-42
bus.endTurn(botId)                                                      // clears the top-level turn's LoopGuard
bus.thread(id); bus.threadsOf(botId); bus.transcript(threadId); bus.talk(botId); bus.pairs(sinceMs)
```
- `Sent` = `Replied(thread, reply, withheld, text)` · `Delivered` · `Silent` · `Failed` · `Refused(why, text)`; `text` is what the model reads. `Why` = EMPTY, UNKNOWN_BOT, SELF, ROOM, DISABLED, HIDDEN, HOP_CAP, BUDGET, THREAD, WAITING_ON_YOU, TOOL_CALL, LOOP.
- `ThreadBudget` (handed to the runner): `threadId, rootId, depth, maxHops, botId, chain, originJob, left, canSend, charge(n), charged, silenced`. Pass it back as `via` for any send_to_bot / stay_silent made inside that turn. `canSend = false` → drop send_to_bot from that turn's tools (`BotMessaging.schemas(canSend)`).
- `chain` = bots above the turn, nearest first → `Policy.Ctx(trigger = Trigger.BOT, chain = chain.map { Subject.of(team.get(it)!!) })` gives the lowest permission along the chain (05 §7.2). Policy itself is not imported here (keeps the pure closure small).
- Rooms: reuse `BotMessaging.STAY_SILENT`, `ThreadBudget`-style budgets, `Provenance`, `badge`, `Redact.pass` (per member viewer) and `HandoffStatus`; rooms are refused by send_to_bot (`Why.ROOM`, "post_to_group").
- Workers / subagents: a hidden bot may message only its `parentId ?: reportsTo`; only the parent may message a hidden bot (by id). Subagent reports = `bus.send(subId, parentId, …)`.
- People / Connections: `talk(botId)` → other, threads, messages, lastAt, asked, answered; `pairs(since)` for "Atlas ⇄ Ledger · 4 today".

## Rules as built (decisions, owner unavailable — taken from 05 §2.4 / §7.2 / §10.2 and BH semantics)
1. **Depth**: a turn started by the user / routine / voice is depth 0; a message sent from it is depth 1. A turn at depth ≥ `maxHops` (3, or the sender's lower `Permissions.maxHops`) is refused "answer the user directly" (BH `ctx.depth >= 3`). So at most 3 hops: A→B→C→D.
2. **Budget**: one pool of 20 model calls per *root* exchange; child threads (hops made inside a receiving turn) draw from the same pool and mirror it in their `budgetLeft`. The engine may charge as it goes (`ThreadBudget.charge`); after the turn the bus charges `BotReply.calls − charged` (min 1 when nothing was charged). Pool < 1 → send refused (`BUDGET`); a turn that empties it ends the thread `OUT_OF_BUDGET` (its reply is still delivered, with "answer the user now").
3. **Ends**: `stay_silent` (or `BotReply.silent`, or a blank answer) → SILENT; a one-way message (`expects_reply=false`) ends DONE (silence there counts as delivery); an exception in the runner → FAILED. An answered thread (ANSWERED) stays continuable by either of its two bots with `thread=<id>` while budget remains — "returns a result" is read as the one-way case, because BH follow-ups in one thread need it open.
4. **Loops**: `agent/LoopGuard` reused as is (one per root exchange, one per top-level turn keyed `turn:<bot>`): the same ask to the same bot that got the same answer twice is not sent a third time. Plus: a bot can't message a bot that is waiting on it up the chain (`WAITING_ON_YOU`: just reply), nor answer through send_to_bot in the thread it is answering.
5. **Redaction** (05 §2.4 "data scope check"): the ask is redacted with the *receiver's* `MemScopes.Viewer`, the reply with the *asker's*. Candidates = the lines the writer can see (`MemoryView`). Withheld: (a) prompt-block shaped lines `- #12 [money] …` whose id or label maps to a scope the reader can't see ("your notes" = writer's `bot:<id>`, "from Atlas" = `bot:atlas`); (b) the text of each hidden line (≥ 8 chars, case/whitespace-insensitive); (c) leftover `#id` refs. Share tags pass (Ledger's `share:travel` budget line reaches Atlas). Replaced by `[withheld: a note you can't see]`; counts reach the card ("1 private note withheld") and the tool result. Paraphrase can't be caught deterministically — the receiver's own memory reads stay scope-checked by P3-AG-01 regardless (logged as a known limit).
6. **No side doors**: payloads that are tool calls (`{"tool":…}`, `{"name":…,"arguments":…}`, fenced JSON) are refused; the receiver runs as itself through `BotRunner` with its own tools; `BotMessaging.sideDoor(caller, tool, group, roster)` is the dispatch check for TeamHooks ("belongs to Ledger, not you … ask Ledger with send_to_bot"); team/jobs/memory groups and the two bus tools are every bot's.
7. **Visibility**: ONE live HandoffCard per exchange in EACH bot's thread (row kind card, type `handoff`), updated in place (same seq) through ASKING → WORKING → REPLIED / DELIVERED / SILENT / OUT_OF_BUDGET / FAILED, carrying the collapsed talk line — rather than a card plus a separate event row, to keep threads quiet. The asker's card holds what it wrote + the (redacted) reply; the answerer's card holds the (redacted) ask + its own reply, because thread rows are model context. Unread / preview are touched on creation and on the final state only. The full transcript lives in team.db `rows` under the pseudo bot id `bt:<threadId>` (no schema change; bot ids never contain `:` except `worker:`), each row `role = "bot"`, `from = "bot:<id>"`, `thread`, optional `withheld`.
8. **Provenance**: Row `from` strings `user` · `bot:<id>` · `routine:<id>` · `voice` · `system`; P1-TM-02's plain bot ids still parse. Badges: "from Juno", "routine Morning brief", "said on a call"; none for the user, system notices or the thread bot's own messages.
9. **Who**: any enabled, visible teammate (incl. ARGUS) by name/id via `TeamStore.find`; self, rooms, disabled bots refused; hidden rule in "API" above.
10. **ActionClass**: `send_to_bot` WRITE_LOCAL and **not** FREE_TEXT (a request like "budget for the trip" must not become a SPEND card; the receiver's own actions go through Policy with trigger BOT, which never runs SEND/SPEND/DELETE unconfirmed). `stay_silent` READ.

## Seams (replace when earlier phases land)
| Seam here | Real thing | Note |
|---|---|---|
| `BotRunner` fun interface | P0-AG-01 `BotEngine` / `TeamHooks` | TeamHooks: `BotRunner { id, prompt, b -> engine(bot(id)).turn(prompt, trigger = BOT, chain = b.chain, budget = b) }`; charge `b.charge()` per model call, stop when false; pass `b` as `via` to tool calls; drop send_to_bot when `!b.canSend`. |
| `BotThreadPort` / `MemoryBotThreadPort` | P0-TM-01 TeamDb `bot_threads` | 05 §9.2 columns + P5-local `root, parent, messages, updated_at, expects_reply, card_a, card_b` (extra columns in the TeamDb migration). |
| `MemoryView` | P3-AG-01 `MemScopeStore` lines visible to a bot | default empty = nothing to redact. |
| `later` | job executor / `BgAgents` | default runs inline. |
| Bot types | `team/Models.kt` (P0-TM-01) | compiled against an UNCOMMITTED scratch copy of 05 §9.1 (+ `DEFAULT_RULES`), as P1/P3 did. |

## Tests run (kotlinc 2.0.21, JDK 17, JUnit 4.13.2, org.json 20240303; scratch Models.kt + a verbatim ClaimGuard.succeeded stub for OneShot, both outside the repo)
| Test | Result |
|---|---|
| `team.TeamBusTest` | **22 / 22 OK** — ask/reply + cards in both threads + talk line + envelope; follow-up updates the same cards; hop cap (4th hop refused, depths 1-2-3, shared root, Policy chain); own maxHops; budget 20 → OUT_OF_BUDGET → thread refused; hops share the pool + empty pool refuses + engine charging; nested hop spends the same pool; ask redaction (private health line withheld, share:social passes, asker keeps own words); reply redaction (share:travel passes, private + block line withheld, team line passes); Redact rules; stay_silent; expects_reply=false (returns at once, ASKING live → DELIVERED); WORKING while running; runner failure; LoopGuard ping-pong + endTurn; waiting-on-you / answer-through-send; who can be messaged (self, unknown, room, disabled, hidden subagent ↔ parent, ARGUS); no side doors; provenance badges + triggers; talk line + card round trip + spoken; talk/pairs stats; schemas. |
| `team.RowStoreTest` + `team.TeamStoreTest` (regression, compiled together with the new files) | 25 / 25 OK |
| `policy.ActionClassCoverageTest` | 5 / 6 — the one failure is **pre-existing on `overhaul/p5`** and not from this ticket: `add_job, agent_delegate, create_task, done_job, retry_job, update_job, update_task` (P1-TM-05 `JobTools`) have no ActionClass since P2-SF-01 was merged in. `send_to_bot` / `stay_silent` are covered. Left for the lead (one `c(WRITE_LOCAL, …)` line) to avoid conflicting edits. |
| `agent.LoopGuardTest`, `memory.MemScopeTest` | not re-run: unchanged files, test closures pull Mind / BlazeDb. |

## Left for a device build / later
- `HandoffCard.kt` is not compiled here (no Compose jars in the sandbox); checked by reading against the kit's signatures. Needs a shots test (HandoffCard asking / working / replied / withheld, light + dark) on the owner's build — P5-QA-01.
- Wiring (needs P0-AG-01): register `BotMessaging.schemas()` in the bot tool list, route `send_to_bot` / `stay_silent` to `bus.exec`, call `sideDoor` before `Policy.decide`, `endTurn` at the end of a user turn, construct `TeamBus` in the team singleton with the TeamDb port, render `handoff` cards with `HandoffCard` in the thread (P1-TM-03 / thread screen), animate the Team list edge for 2 s on a new card (04 §6 #3).
- P1-TM-05 `agent_delegate` still posts its own simple handoff card (`title`/`to`/`job`); it can switch to `Handoff` fields when the thread screen lands.
