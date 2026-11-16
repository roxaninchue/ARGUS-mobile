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

## Still to retire (owner to schedule; don't add more)

These are the pre-routers left in the main chat. Each one guesses intent before the model does:

- `FastLane.tryHandle` instant rules online (`agent/Agent.kt`, fast lane before the model call)
- `Media.directPrompt`, where "draw a …" goes straight to the image service
- `ToolTruth.toolShaped` / `Duo.route` tags deciding lanes
- `SearchRoute.webNote` and the search guard rewriting `web_search` calls
- `BusyRoute` / `LookupLane` objects, now unused by the chat (delete them once nothing else needs them)

When you touch one of these, replace it with structure plus the model's own decision, and add a line to
`ModelFirstTest`.
