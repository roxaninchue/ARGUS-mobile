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

## Still to retire (owner to schedule; don't add more)

- **`ViewRouter.route` / `SkillMatch` / `SpecialistMatch`** still pick views, skill packs and specialists from the message's words (they add the `make` / `extras` groups). Next candidate: list them in the prompt and let the model load one.
- **The loop guards in `runLoop`** (RetryGuard, ClaimGuard, EchoGuard, ToolTruth.NUDGE / refusal, AutoContinue…). These are HARNESS MUSTS #4, the "Plain harness" toggle.
- **Small guesses:** `ThinkBudget.quick` (thinking budget from cue words), `Ack.wants`, `StopResume.isResume` ("continue"), `ToolTruth.directPhone`, `TurnContext.scheduleAsk`, and the Halo side lane (`sideSend`), which is unreachable from `send()` now.
- **The `BusyRoute` / `LookupLane` / `SmallTalk` / `ChatLane.fits` objects.** These are now unused by the chat turn. `LookupLane` is still used by BlazeQuery (`QueryRuntime`). Delete each one when nothing else needs it.

When you touch one of these, replace it with structure plus the model's own decision, and add a line to
`ModelFirstTest`.
