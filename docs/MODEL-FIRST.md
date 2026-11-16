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

## Still to retire (owner to schedule; don't add more)

- **`ViewRouter.route` / `SkillMatch` / `SpecialistMatch`** still pick views, skill packs and specialists from the message's words (they add the `make` / `extras` groups). Next candidate: list them in the prompt and let the model load one.
- **The loop guards in `runLoop`** (RetryGuard, ClaimGuard, EchoGuard, ToolTruth.NUDGE / refusal, AutoContinue…). These are HARNESS MUSTS #4, the "Plain harness" toggle.
- **Small guesses:** `ThinkBudget.quick` (thinking budget from cue words), `Ack.wants`, `StopResume.isResume` ("continue"), `ToolTruth.directPhone`, `TurnContext.scheduleAsk`, and the Halo side lane (`sideSend`), which is unreachable from `send()` now.
- **The `BusyRoute` / `LookupLane` / `SmallTalk` / `ChatLane.fits` objects.** These are now unused by the chat turn. `LookupLane` is still used by BlazeQuery (`QueryRuntime`). Delete each one when nothing else needs it.

When you touch one of these, replace it with structure plus the model's own decision, and add a line to
`ModelFirstTest`.
