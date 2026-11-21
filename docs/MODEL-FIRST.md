# Model-first: build the system around the model

**Owner rule, 16 Nov 2026. Applies to everything built in this repo, by any person or agent.** It extends "⛔ HARNESS MUSTS" in overhaul.md.

> Unlike previous versions of the app, where we tried to force the model: none of that. We build the system around the
> model, not put in contradictory instructions and try to bend every model.

Earlier versions of ARGUS (and Blaze before it) put keyword rules and regexes in front of the model to guess what the user
meant, and stacked up prompt orders ("ALWAYS…", "NEVER…", "answer EVERY…") to patch the results. Each patch fought the
others, and the model ended up looking dumb when the system around it was the problem. Don't do that again.

## Do

- **Give the model the user's words as they are, in order, with what it needs to act:** the conversation, the tools with
  clear schemas, the results, and facts about the situation ("this message was sent while you were working").
- **Let the model decide what a message means:** whether it's a stop, a correction, a new ask, a search, or small talk.
- **Guarantee behaviour with structure, not prompt orders.** Nothing gets dropped because the queue delivers every message,
  not because the prompt shouts "answer every message". Use one turn at a time per chat, delivery state, a Stop button,
  approval cards, timeouts, and budgets.
- **Keep prompt text short, factual, and consistent.** Describe the tools, the user, and the situation. If two lines can
  pull in different directions, delete one.
- **When a model gets something wrong, first fix what it was given** (missing context, a confusing tool result, a bad
  schema, a contradictory line). Add another rule only as a last resort.

## Don't

- Regex or keyword routers that decide intent before the model reads the message (stop/steer/side classifiers,
  "google X" → search, "draw X" → image, "is this chit-chat?" lanes) in the main chat.
- A second lane or model that answers the same message beside the main one.
- Prompt lines in capitals or absolutes added to override a behaviour ("ALWAYS", "NEVER", "EVERY", "you MUST").
- Rules that contradict each other or the model's own judgement, or that are tuned to bend one particular model.
- Hiding the user's words from the model or rewriting them before it sees them.

## Still fine (these are structure, not mind-reading)

- Safety and permission gates that don't guess intent: approval before spending money or sending messages, the secrets
  vault, and the do-not-port list.
- Exact, explicit UI actions: buttons, the Stop control, chips the user taps.
- Exact device commands the user typed in full while **offline**, when no model is reachable.
- Plain facts in the prompt: the date, the user's name, what a message id means, and what a tool returns.

## The incident that set the rule (0.53.x, 16 Nov 2026)

The owner sent "Let's login to Instagram", then "Oh wait", then "Google first", and got these replies:

1. "Oh wait" got a good reply ("No worries. What do you need instead?").
2. "Google first" matched `SearchRoute`'s `^google (.+)` regex. The quick-lookup lane searched "first" plus "Instagram"
   from earlier in the chat and posted a GitHub repo called InstaFirst. The model never saw the message.
3. A prompt line ("Answer EVERY user message… never leave one out") made the model re-answer the cancelled Instagram
   request.

**Fix:** mid-turn messages go to the model as they are, through `ChatBurstLive.follow` (the `BusyRoute` regex is no
longer consulted in `send()`). The keyword-triggered lookup lane is gone from the chat, and the model calls `web_search`
itself. `ChatBurst.RULE` and `laneLine` were deleted. The guard test is `ModelFirstTest`.

## Round 2: the other pre-routers (owner: "yes remove them all", 16 Nov 2026 22:58 CAT)

Removed from the turn in `Agent.kt`:

- **The fast lane while online.** `FastLane.tryHandle`'s instant rules now run only offline, when no model is reachable.
- **`Media.directPrompt`.** "Draw a …" no longer goes straight to the image service. The model calls `generate_image`.
- **`MemoryIntent.isListRequest`.** "Show me your memories" no longer gets a canned dump. The model uses `memory_search` / `recall_memory`.
- **The no-tools chat lane (`ChatLane.fits`).** It was a regex stack deciding that "hi" or a short question needed no tools. `lite` is now always false, so every turn gets the full lane.
- **Lane guesses by `ToolTruth.toolShaped` / `Duo.kindFor`** in `startTurn` and the side lane.
- **The `web_search` refusals.** `SearchRoute.webNote` refused "this is about ARGUS" questions, and `SearchGuard.check` refused queries that drifted off the guessed subject. The model's query now runs as written.
- **`runLookup`** and its helpers, which the chat no longer used.

## Round 3: tools by structure, not words (16 Nov 2026 23:06 CAT)

Which tools a turn gets no longer depends on the message's words.

- **Every turn sends:** the core (`ToolPick.ESSENTIAL` + `HARNESS`); `ToolRouter.HARNESS` groups (terminal, screen, device, messages, google, browser); `ToolPick.ALWAYS_GROUPS` (`schedule`: calendar, reminders and routines, so an agent can check commitments itself); the groups this chat already used; and the Stage while it shows cards.
- **The model loads anything else itself.** `load_tools` lists every group, and `find_tool` searches every tool. A call to a tool it wasn't sent widens the set.
- **Gone from the turn:** `ToolRouter.guess`, `LearnedRoutes.predict`, `ToolRouter.fallback`, `TurnContext.scheduleAsk`/`scheduleFollowUp` as group switches, the `ExecRoute.route` terminal guess, Simple mode's word-based group removal, and FastActions' group swap.
- **In `ToolPick`:** the cue regexes are no longer read, and over the cap, structure decides (core, then must, which includes the schedule tools, then used, then cue, then the registry's order), never word overlap.
- **The 60 KB gateway limit is unchanged:** `MAX_TOOLS` stays at 48.

## Agent-first persona (16 Nov 2026, owner: "a goal, not something to hardcode")

Seen: "I've completed my CIT exam 🥳" got only "Nice work! 🎉 CIT done and dusted." plus a memory save. The exam could have
been on the calendar or in reminders, and something else may have been next. The model wasn't wrong: what it was given
told it to treat such a message as small talk ("small talk needs no tools", "on small talk offer 1 to 3 next steps",
"answer the question first, that is the whole reply").

The fix is what the model receives. Nothing detects "exam", "completed" or "finished", and nothing rewrites the reply.

- **One short core persona, `AgentPersona.CORE` (1.6 KB; it was 2.6 KB bundled and 4.5 KB in the remote copy phones had cached).**
  It says who ARGUS is (an agent that acts on the user's phone and accounts) and how it works: notice what a message touches
  in the user's commitments, check before assuming, act or offer, then reply briefly and warmly. It contains no absolutes
  and no capitals. It is the default personality's first system message (`assets/persona-v1.json`, byte-identical,
  version 2.0.0 so it wins over the remote 1.0.x copy). A personality the user picked keeps its own voice and gets
  `AgentPersona.PICKED` (the agent role + how it works + the shared basics + identity).
- **Situational facts already there:** the `[context]` block on every turn has today's schedule (passed items marked) and
  the next item after today. Calendar, reminder and routine tools go out every turn (round 3).
- **Tool descriptions say what they can do with a finished or stale entry:** `calendar_events` finds an event before
  `move_event` / `delete_event`; `list_routines` finds a reminder before `delete_routine` or `process` changes it;
  `delete_routine` and `delete_event` name a done or cancelled entry as a use. There is no separate "complete" state:
  done means delete, or leave a past one-off as it is.
- **Deleted because they fought the goal:** the persona's "on greetings, small talk… offer 1 to 3 next steps",
  "every reply… ends with a concrete, named offer", the duplicated schedule and weather lines, and its NEVER lines;
  `Duo.ASSISTANT_RULES`' "ASSISTANT, NOT A CHAT BUDDY… on small talk… offer 1 to 3" block; the same small-talk sentence
  at the end of `TurnContext.RULE`; and "and small talk" in `HarnessPrompt.CORE`'s "need no tools" line.
- Whether a message is actionable is still the model's call. Guard: `ModelFirstTest.agentFirstPersonaIsShortCalmAndShared`
  and `theModelSeesTodayAndNextEveryTurn`.
- **Open:** the remote `https://blazeinchue.pages.dev/persona.json` (1.0.7) should be replaced with this core, or retired.
  The app ignores it until it publishes a version above 2.0.0.

- 2026-11-18: the Learn safety net (`ViewRouter.shouldConvert` / `cardsFrom` turning a text reply into a deck, plus its re-prompt) is gone from the turn; `ViewRouter`'s helpers stay only for their old tests. (2026-11-21: deleted with the rest of `ViewRouter`, round 6.)

## Round 4: skills by the model, the skill shelf (20 Nov 2026)

Design and reasoning: **[SKILL-SHELF.md](SKILL-SHELF.md)**. Which skills a turn sees no longer depends on the message's words.

- **Every turn sends the shelf** (`SkillShelf.promptBlock()`, SLOW tier so it caches): every skill that's switched on, one
  line each (name: the first sentence of its description), the user's own first. The model reads one with `use_skill`
  (now in the lean chat kit) or replays a learned one with `run_skill`; `find_skill` searches.
- **Gone from the turn:** `SkillMatch.forTurn` / `selectForTurn` (word score + `SkillIntent` task regex + `SkillGuard.strongHit`),
  `SkillMatch.lookupBlock` / `SkillIndex.promptBlock` (the per-message "best matches"), `SkillMatch.learnedOnly` (the
  plain-device-action path), the state / self-question skill blanking, and pasted skill bodies.
- **Gone from the tools:** `SkillGuard.gate` (Agent `skillGate`), which refused `use_skill` / `run_skill` and told the model a
  skill was "unrelated" when the words didn't overlap. Tool results state facts; they don't argue with the model.
- **Kept:** `find_skill` (the model's own search), `SkillGuard.Freq` (counts finished work), a matched specialist's packs.
- Guard: `ModelFirstTest.skillsAreAShelfNotAGuess`; `SkillShelfTest`.

## Round 5: specialists by the model, the specialist shelf (20 Nov 2026)

Design, cost and numbers: **[SKILL-SHELF.md](SKILL-SHELF.md) §13**. Which specialists a turn sees no longer depends on the
message's words either.

- **Every turn lists every specialist** (`SpecialistShelf`): the built-ins in their fixed order, then the user's own,
  one line each from the specialist's own prompt. The list sits in `delegate`'s description with the ids as an enum,
  and `delegate` rides every turn (`ToolPick.ESSENTIAL`); the system prompt keeps a one-line pointer. The model decides
  whether to delegate; a specialist reads its own skill packs in its own run (`Specialists.brief`).
- **Gone from the turn:** `SpecialistMatch` (specialists and their packs picked from the words), `SkillIntent.taskIntent`,
  `SkillGuard.unnamed`, the skill word score (`SkillMatch.match` / `score` / `block`), and `turnSpecs` / `turnSkills`
  with the `extras` group they added. `SkillGuard.Freq` counts a task as covered only by what the model did.
- **Shelf cost, both shelves:** cut 1 puts them first in the cached SLOW tier (deterministic order; the voice-turn rule
  moved to TURN so switching voice / typing no longer breaks the prefix); cut 2 moves the specialist list into
  `delegate`; cut 4 makes every line `name — use when …`, capped at 10 words after the dash by `SkillShelf.useWhen`;
  thin lines were then fixed in the skill descriptions and specialist prompts, not in code. ShelfBenchTest per
  request: 8,926 chars after cut 2, 6,697 after cut 4, 6,972 with the sharper lines (≈ 1,743 tokens). Cut 3 (send the
  shelves once, then a diff) was dropped: the history is resent every request, so it saved nothing and moved the
  shelves out of the cache (uncached chars 55,642 → 76,823 in the bench).
- Guard: `ModelFirstTest.specialistsAreAShelfNotAGuess`; `SpecialistShelfTest`, `ShelfBenchTest`.

## Round 6: views by the model, the view shelf (21 Nov 2026)

Design, examples and cost: **[VIEW-SHELF.md](VIEW-SHELF.md)**. Which view a turn uses no longer depends on the message's words.

- **Every turn sends the view shelf** (`ViewShelf.promptBlock(stageOn)`, STATIC tier, 1,481 chars): every view, one line
  each (`id — what it holds — the tool that writes there`), with one head line that plain answers stay in chat. The model
  decides whether work belongs in a view and calls that view's tool.
- **A current design is a fact, not a guess.** While a design is current (by structure: the last turn made or changed
  it, Design is showing, or it changed in the last 30 minutes), the turn carries one `CURRENT DESIGN: …` line and the
  model decides whether the message is about it (`ViewShelf.currentDesign`).
- **Gone:** `agent/ViewRouter.kt` (`route` and its ten cues, `hint` / "REQUIRED VIEW THIS TURN", the 3,890-char `PROMPT`
  with its HARD RULE, `isFollowOnEdit` / `followOnHint`, `routeAll`, the Learn safety net helpers), `Session.turnView`
  and the tool groups it switched on, `PromptProfile.views` / `viewRouted` / `viewsShort`.
- **Kept, by structure:** `ClaimGuard` reads `ViewShelf.specs`; whether a false claim is about the request now asks what
  the model reached for this turn (`ClaimGuard.viewsTried(turnTools)`) instead of `route()`.
- Seen with the shipped `route()` before the change: "remind me what we decided about the logo" → Schedule ("create a
  routine"), "how much does it cost to fix the deck at my house?" → Learn (10-25 flashcards), "show me how a car engine
  works" → Design (a 3D model), while "remember to pay ZESCO every month" got no view at all, nor did a message in Bemba or Nyanja (the cues are English).
- Guard: `ModelFirstTest.viewsAreAShelfNotAGuess`; `ViewShelfTest`.

## Still to retire (owner to schedule; don't add more)

- **`VisualRoute.inChat` → `VisualRoute.HINT`** still adds an in-chat-diagram hint from the message's words. Next candidate (VIEW-SHELF.md §9): the view shelf's head and `RichReplies.PROMPT` already say it. Skills (round 4), specialists (round 5) and views (round 6) are done.
- **The loop guards in `runLoop`** (RetryGuard, ClaimGuard, EchoGuard, ToolTruth.NUDGE / refusal, AutoContinue…). These are HARNESS MUSTS #4, the "Plain harness" toggle.
- **Small guesses:** `ThinkBudget.quick` (thinking budget from cue words), `Ack.wants`, `StopResume.isResume` ("continue"), `ToolTruth.directPhone`, `TurnContext.scheduleAsk`, and the Halo side lane (`sideSend`), which is unreachable from `send()` now.
- **The `BusyRoute` / `LookupLane` / `SmallTalk` / `ChatLane.fits` objects.** These are now unused by the chat turn. `LookupLane` is still used by BlazeQuery (`QueryRuntime`). Delete each one when nothing else needs it.

When you touch one of these, replace it with structure plus the model's own decision, and add a line to
`ModelFirstTest`.
