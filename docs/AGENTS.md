# Argus agents (0.52.0): background agents, parallel tools, blueprint orchestration

Argus is one conversation agent that **never blocks**. Work that takes more than a couple of tool steps goes to
background agents; the chat keeps answering. The chat shows only replies and work pills.

```
 user ──► chat agent (fast lane, Session turn)
            │  quick answer: replies itself (parallel tools inside one reply)
            ├─ spawn_agent(task, specialist, inputs)  ─► BgAgents entry ─► hidden Session (own context, tool loop, budget)
            ├─ delegate(tasks[])                       ─► one BgAgents entry per specialist brief
            └─ orchestrate(goal, pipeline)             ─► BgAgents entry (specialist "orchestrator") ─► OrchestraRuns
                                                              planner ─► Blueprint DAG ─► Orchestrator (dataflow)
                                                              node agents = Session.runNode (specialist loops)
 results ──► Agent.agentFinished: pill moves to the bottom, result posted / main Argus writes a short follow-up
```

## 1. Background agents (agent/BgAgents.kt, ui/AgentPill.kt)

- **Start**: `spawn_agent` (task + optional specialist type + inputs), `start_agent` (alias without type), `delegate`
  (several specialists, never two on one target), `orchestrate` (a blueprint run). The chat agent's prompt
  (`BgAgents.leadLine`) tells it to reply at once in one short line and keep chatting; it never waits or polls.
- **Run**: each agent has its own hidden Session (own history, tool loop, modes, repairs) on the app scope, saved in
  `filesDir/bg_agents.json`; a run cut by Argus closing is re-queued with `resume = true` and told to check what is
  done first. `TaskWorker` (WorkManager, expedited) holds the process while work runs and restarts it after a kill.
- **Concurrency**: `BgAgents.cap()` = `AgentBudget.cap(totalMem, isLowRamDevice)`: 4 on 6 GB+, 3 on 4 GB+, 2 below,
  1 on low-RAM phones, one fewer under memory pressure. Network-only specialists have their own cap (3); phone
  specialists never run two at once (Dispatch).
- **Steer / cancel / progress**: `message_agent` (joins at its next step; a follow-up after it finished),
  `cancel_agent`, `list_agents` / `check_agent` answer from the agent's state — nothing blocks. The same actions are in
  the pill (Message, Stop).
- **Pill**: `AgentPill` = "● Working · Researcher · 5 steps · 40s ›" / "Worked · … ›"; tap = its newest steps
  (`Bg.log`, last 40) + Message / Stop. Replaces the old AgentCard.

### Removed from the chat (ui/ChatPolicy.STATUS_LINES = false)
The work line beside the typing dots (step pill → task chip → status → last step, which is where lines like "Waiting
for your task to finish" and "Sent. Waiting for Argus…" showed), the task chip ("Task · in progress"), the step pill
row beside the drop, Simple mode's hint line (now the same work pill), the model-written wait line (WaitNudge), the
"Got it, I've passed that to Argus…" steer bubbles and Halo's holding line. The pure helpers stay for their tests.
Messages sent while a turn runs still never queue behind it (BusyRoute: stop / steer / parallel answer).

## 2. Parallel, conflict-free tool calls (orchestra/ToolScheduler.kt, KeyedLocks.kt)

When a reply carries several tool calls, the independent ones run at the same time:

| Tool kind | Claim | Effect |
|---|---|---|
| web / network / reads of the user's data | none | fully parallel |
| file write (`file_write`, `file_edit`, `save_file`…) | `file:<path>` write, parent `dir:` shared | same path in order, different paths parallel |
| file read / list | `file:<path>` read; a listing takes its `dir:` alone | reads share; a listing waits for writes inside it |
| shell / code (`run_shell`, `run_code`…) | none (own session per call); `term:<id>` when it names a session | parallel; **kept in order with file calls** in one reply |
| screen (`tap`, `open_app`, `do_actions`…) | `screen` | one at a time, in the model's order, across all agents |
| browser (`browser_*`) | `browser` | one at a time |
| cards (`send_sms`, `ask_user`, approvals…) | `user` | never two cards at once (in one reply) |
| unknown tool | `tool:<name>` + `unknown` | safe default: unknown tools take turns, still beside network calls |

- Inside one reply: a dependency graph (call j waits for earlier conflicting calls), per-call timeouts, results in the
  model's order with their call ids, each call's step added to the pill the moment it starts.
- Across agents: real resources (screen, browser, files, folders, named terminal sessions) are held in the shared
  `KeyedLocks`; "user"/"unknown" are never held across agents, so one agent's approval card can't stall another.
- The chat loop prefetches every `prefetchable` call through the scheduler; screen actions, cards and agent tools
  stay in its in-order loop (approval batching, screen read-back). Specialists' tool batches run fully through it.

## 3. Blueprint orchestration (orchestra/Blueprint.kt, Orchestrator.kt, Pipelines.kt, OrchestraRuns.kt)

- **Blueprint**: one plan, a graph of nodes (`plan, folder, file, search, sort, review, stitch, rank, curate, build,
  verify, fix, export, section, task`) with deps, owned paths (`writes`), contracts and a role (specialist + tier).
  `Blueprints.validate`: no duplicate ids, no cycles, no two nodes writing one path without an order.
- **Store** (`BlueprintStore`): shared state of a run — node states/outputs/attempts, shared notes (research findings
  `finding/*`, claim checks `review/*`), contracts, and an event bus (Added, Ready, Started, Step, Done, Failed,
  Escalated, Cancelled, Finished). Snapshot/restore = persistence across process death (RUNNING → PENDING).
- **Dataflow**: a node starts when each of its deps **and everything that dep added** is done — never when its
  siblings are. Nodes add children as they finish (a folder adds its files, the sorter adds a reviewer per claim, a
  failing build gets a fixer), so nested agents start the moment their parent is ready. Deps on nodes that don't exist
  yet are waited for (a file in a folder not expanded yet); never-planned deps fail the node when the run settles.
- **Safety**: at most `maxParallel` node agents (the RAM cap); each node holds exclusive locks on the files it writes;
  failures retry with the error in context, then escalate (`AgentBudget.escalation`: build / verify / file failures
  get a fix agent with the error and the files it names, then a retry); a failed node cancels its dependents only;
  cancelling the run cancels every node agent and nested runs.
- **Pipelines** (`Pipelines.pick` or the model's `pipeline`):
  - `apk` (showcase): planner JSON (folders → files with contracts and imports) → folders made in Argus Terminal →
    one coder per file, each starting when the files it imports exist → `./gradlew assembleDebug` in the terminal
    (terminal-first route) → fixers → `testDebugUnitTest`.
  - `research`: planner queries → parallel searches (findings saved to the store) → sorter (outline + claims) →
    parallel reviewers (VERIFIED / DISPUTED / UNCLEAR) → stitcher (structured Markdown with tables / charts / sources)
    → export (`save_file`) → a ```doc card.
  - `media`: sources → parallel searches → dedupe / rank → a ```gallery with artwork.
  - `document`: outline → sections in parallel → stitch → export. `generic`: tasks with their own deps → stitch.
- **Tiers per role** (`ModelChoice.forRole`, only when the chip is on Auto): planners / reviewers / sorters →
  Inferno, coders / writers → Flare, searches / rankers → Spark.
- **In the app**: `orchestrate` starts a `BgAgents` entry; `OrchestraRuns.run` builds the store (or restores the
  snapshot), runs the Orchestrator with `Session.runNode` agents, streams node events into the pill, and returns the
  rich result (doc card / gallery / build summary), posted as the agent's reply.

## 4. Rich replies (ui/RichMedia.kt, RichMediaViews.kt)
New fenced blocks: ```gallery (artwork list or poster grid), ```links (link cards), ```doc (document card with
Open / Share), ```details (collapsible section); existing: ```chart (bar / line / pie / scatter, Compose canvas),
```table, ```compare, ```tool, ```choices, ```form, inline pictures between paragraphs, picture rows / decks,
diagrams. `RichMedia.PROMPT` tells the model to show rather than tell and keep prose to 1–3 sentences. A block that
can't be parsed falls back to its salvaged text, never raw JSON.

## Tests (fake tools)
`orchestra/ToolSchedulerTest`, `OrchestratorTest` (children start before their parent's siblings finish; a cross-folder
import waits until planned and written; no two agents write one file at once on real threads; retries with the error;
build failure → fixer → success; failure cancels dependents only; skip; cancellation cascades into every agent and a
nested run; parallel cap; restore; orphan deps; events), `PipelinesTest` (research, media, APK, generic end to end),
`composer/TiersTest`, `ui/RichMediaTest`, `ui/RichMediaShotsTest` (screenshots, ARGUS_SHOTS=1).
