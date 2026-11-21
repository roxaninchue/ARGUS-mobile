# View shelf: ARGUS decides where work goes

Status: implemented on branch `feat/view-shelf`, 21 Nov 2026 (MODEL-FIRST round 6). Follows
[MODEL-FIRST.md](MODEL-FIRST.md), whose *Still to retire* list names it first ("`ViewRouter.route` still picks a view
from the message's words … the model already has the view tools"), and [SKILL-SHELF.md](SKILL-SHELF.md) §12 item 2.
Same idea as the skill and specialist shelves: show the model what exists, let it choose, keep the code small.

---

## 1. What a view is, and why it matters

ARGUS has screens that hold work: a **Learn** deck, a **Design** with a live preview, a **Schedule** of reminders and
routines, the **Gallery**, **Memory**, **Skills**, **Projects**, the background **Browser**, and the **Stage** (live
cards on the chat itself: a timer, music, a quiz). Putting work there instead of pasting it into chat is one of the
best things about ARGUS: a deck comes back before you forget it, a design can be orbited and changed, a reminder
actually fires. So "which view, if any, does this belong in?" is a real decision on many turns.

Today that decision is made by a regex before the model reads the message.

## 2. What is wrong today

`agent/ViewRouter.kt` (v0.23.0, patched through 0.46.1) has three parts:

| Part | What it does |
|---|---|
| `route(text, followOn)` | Ten regex cues in priority order (Stage, Learn, Schedule, Design, Gallery, Skills, Projects, Archive, Memory, Browser). The first that matches is "the required view". Two patches on top: a diagram asked for in chat is taken back out of Design (`VisualRoute`), and "make a picture of a car" is moved from Design to Gallery. |
| `hint(spec)` | A per-turn order appended after the system prompt: "REQUIRED VIEW THIS TURN: Learn … Do NOT write the questions and answers in chat." 135 to 743 characters. |
| `PROMPT` | The full VIEWS block, 3,890 characters, with "HARD RULE … CREATED … Never dump it". Sent **only on a turn the regex routed**. Every other turn got `PromptProfile.viewsShort`: one 312-character line naming the views, with nothing about what each holds or which tool writes there. |

And around it: a routed view switched on its tool groups plus the `make` cue; a design "follow-on" (`isFollowOnEdit`)
was a second regex of 60 edit verbs that had to open the message; and `ClaimGuard.relevant` asked `route()` again to
decide whether a false "I've updated the design" was about the request.

### 2.1 It gets real messages wrong, both ways

Run through the shipped `route()` (21 Nov 2026, main `0c088b41`):

| The user says | Routed to | What the model was then told |
|---|---|---|
| "remind me what we decided about the logo" | Schedule | "Create it with create_routine … and say when it will run." |
| "every day I wake up tired, why?" | Schedule | the same |
| "how much does it cost to fix the deck at my house?" | Learn | "Put the cards in a Learn deck … 10-25 cards … Do NOT write the questions and answers in chat." |
| "show me how a car engine works" | Design | "Make it for real with the design tool: 3D … 8-40 parts" |
| "can you explain what a slide deck is?" | Design | the same |
| "help me study for my CIT102 exam" | Learn | flashcards, though a study plan or an explanation may be what helps |
| "remember to pay ZESCO every month" | nothing | the one-line view list; "every month" isn't in the cue, and "remember to" isn't "remember that/this/my/me" |
| "my sister's birthday is on Friday, don't let me forget" | nothing | the same |
| a message in Bemba or Nyanja | nothing | the same; the cues are English |
| "the fins look wrong, can they be swept back a bit?" (right after a 3D rocket) | nothing | no follow-on: the message doesn't open with an edit verb, so the model may build a second rocket |

Each wrong row is one more lookbehind away from "fixed" (the Learn cue already carries `(?<!case )study`,
`(?<!slide )(?<!pitch )deck`), and each fix makes the next collision harder to see. That is the pattern MODEL-FIRST
says to stop.

### 2.2 The model is told less when it needs more

On an unrouted turn, which is most turns, the model sees the view *names* and nothing else, so it can't know that
Learn does spaced repetition, that Schedule takes a monthly bill, or that the Stage can hold a timer. On a routed turn
it gets 4,600 characters of capitals and orders, built for the regex's guess.

### 2.3 Orders fight the model's judgement

"REQUIRED VIEW THIS TURN" is an order in the prompt. When the guess is right, the model already had the tool and its
description ("flashcards … whenever the user wants flashcards, a quiz or something to study"). When it's wrong, the
model must choose between the user's words and a system order, and usually obeys the order. The reply then looks
dumb, and the system around the model is the cause (MODEL-FIRST's incident, again).

### 2.4 It breaks the prompt cache

The VIEWS part was in the TURN tier and swapped between 312 and 3,890 characters whenever a routed turn came and went,
so every switch invalidated the cached prefix from that point on.

## 3. The idea

**Put the views on a shelf, every turn, and let the model decide.**

One short, factual block in the cached prefix lists every view: its id, what it holds, and the tool that writes there.
The model reads the request, the conversation and the shelf, and calls the tool when the work belongs in a view.
Nothing reads the user's words to pick a view, and nothing orders the model to use one.

The one thing the code still knows better than the model is **state**: whether a design is current. That stays, as a
fact rather than a guess (§5).

## 4. What the model sees

In the STATIC tier, byte-stable for the same Stage switch (1,481 characters with the Stage on, ≈ 370 tokens):

```
VIEWS in ARGUS (id — what it holds — the tool that writes there). Work that belongs in a view is made there with its
tool, and the reply says in a line or two what you made and where, so the user can open it; open_view opens any view
by id. Plain answers, explanations and a quick diagram or chart in the reply stay in chat.
- stage — live cards on the chat itself: a timer or pomodoro, music, a video, a quiz or flashcards to go through, a plan, the browser — stage_show
- learn — flashcard decks and quizzes to study, with spaced repetition — flashcards
- design — visual work to keep, change or export, with a live preview: 3D models, 2D art, pages and sites, UI mockups, posters, slides, floor plans, games — design
- schedule — reminders, routines, repeating or timed jobs and triggers — create_routine
- gallery — images, logos, wallpapers, beats and other media Argus makes — generate_image
- memory — lasting facts about the user, their people, preferences and decisions — memory_save
- skills — saved methods and workflows to reuse, and skill packs — save_workflow
- projects — ongoing efforts with their own threads, brief and instructions — update_brief
- browser — websites Argus opens, fills in or signs in to, in background tabs — browser_open
- archive — past conversations; there is no screen, so answer in chat from what search_chat finds — search_chat
Other screens (Jobs, Accounts, Agents, Usage, Settings and more) only show things; open_view opens them too.
```

Choices, and why:

- **Ids, not names.** `open_view` takes the id, so the shelf teaches the exact argument.
- **One tool per line, the one that writes.** `open_view` only opens, so it's never the named tool. The rest of each
  view's tools are in their own schemas, which already say how to use them; the shelf doesn't repeat them.
- **"Plain answers … stay in chat" is in the head.** It is the counterweight that keeps "what's a slide deck?" a plain
  answer. It replaces the old in-chat-diagram EXCEPT clause and the HARD RULE, in one calm sentence.
- **No capitals, no absolutes, no "required".** `ViewShelfTest.byteStableSmallAndCalm` guards it.
- **The Stage line follows the Stage switch.** With the Stage off, `stage_show` would only fail, so its line is left
  out (two byte-stable variants, both cached).
- **A budget.** `ViewShelf.BUDGET_CHARS` = 1,600, tested. Ten views fit with room for two more.

## 5. The current design: a fact, not a guess

Changing the design you just made is the most common follow-on in ARGUS ("make it taller", "add fins"). The code
already knows, by structure, when a design is current: the last turn made or changed one, the Design view is showing,
or one was changed in the last 30 minutes (`Designs.recent()`). That check is unchanged.

What changes is what we do with it. Before: a regex decided whether the *message* was an edit and, if so, injected
"REQUIRED VIEW THIS TURN: Design … Don't start a new design". Now: while a design is current, the turn carries one
fact line, and the model decides whether the message is about it.

```
CURRENT DESIGN: "Tall rocket" (3d, /…/rocket/index.html), made or changed in your last turn. If the user is talking
about it, change it with design action=update (set_parts for parts to add or change by id, remove_parts for ids to
drop; action=read shows the part ids) and keep the rest as it is; design action=open shows it, and design action=create
is for something new.
```

So "the fins look wrong, can they be swept back a bit?" reaches a model that knows the rocket exists, while "what's
the weather?" right after a design is still a weather question; nothing has to guess which.

## 6. How it works for users

| The user says | Before | After |
|---|---|---|
| "remind me what we decided about the logo" | Ordered to make a routine. | Archive's line says past conversations are answered from search_chat. It looks it up. |
| "how much does it cost to fix the deck at my house?" | Ordered to make 10-25 flashcards. | A plain answer (web search), as the head says. |
| "show me how a car engine works" | Ordered to build a 3D model. | Usually an explanation with an inline diagram; a 3D model if the user wants one to look around. The model's call. |
| "remember to pay ZESCO every month" | No view; the model only knew the word "Schedule". | Schedule's line names repeating jobs and create_routine. A monthly routine. |
| "my sister's birthday is on Friday, don't let me forget" | No view. | A reminder in Schedule (and a memory, if it's worth keeping). |
| A message in Bemba or Nyanja | No view, ever. | The same shelf; the model understands the language. |
| "the fins look wrong…" after a rocket | No follow-on; a second rocket was possible. | CURRENT DESIGN names the rocket; the model updates it. |
| "make flashcards on photosynthesis" | Learn, correctly. | Learn, from the shelf and the flashcards tool's own description. |
| "make a picture of a red car" | Gallery, via a patch on top of Design. | Gallery's line holds images; Design's holds visual work to keep or change. No patch. |

What the user notices: fewer odd detours (a routine, a deck or a 3D model nobody asked for), and things said casually,
in their own words or their own language, land in the right place more often. When ARGUS does use a view, the reply
still says in a line where the work is.

## 7. What stays the same, on purpose

- **The views and their tools.** No tool, schema or screen changes. Tools a view needs are already in every turn
  (`stage_show` and `open_view` core; `design`, `generate_image`, `create_routine`, `browser_*` in the harness core;
  `flashcards` in an always-on group); anything else is one `load_tools` away, and calling an unsent tool widens the set.
- **ClaimGuard.** A reply that says "I've updated the design" with no succeeded design call is still re-prompted once,
  then made honest. It now reads the same specs from `ViewShelf`. The one thing that asked `route()` (is this claim
  about the request?) now asks what the model itself reached for this turn: `ClaimGuard.viewsTried(turnTools)`. That
  is structure, not words.
- **The views core skill** (on the skill shelf, read with use_skill) keeps its table and steps, now in the
  shelf's calm voice, with a Stage row and the CURRENT DESIGN fact explained (version 4).
- **The VisualRoute turn hint** for in-chat diagrams. It is a word guess too, and the next candidate (§9); it was left
  alone so this change stays one idea.

## 8. Cost

| Turn | Before | After |
|---|---|---|
| Unrouted (most turns) | 312 chars, TURN tier | 1,481 chars, STATIC tier (cached) |
| Routed | 3,890 + 135-743 chars, uncached at every switch | 1,481 chars, cached |
| A design is current | + ≈ 510 chars, only when the message opened with an edit verb | + ≈ 370-420 chars every turn while it's current |

The shelf costs about 290 more tokens on an ordinary turn, inside the prefix providers cache, and saves up to 3,150
characters on the turns that used to route. It is a fraction of the skill shelf (≈ 1,743 tokens) for the same benefit:
the model knows what exists.

## 9. Next

1. **VisualRoute's turn hint** (`VisualRoute.inChat` → `VisualRoute.HINT`): the shelf head and `RichReplies.PROMPT`
   already say quick diagrams go in the reply; once a phone check shows inline diagrams hold up, delete the hint.
2. **ClaimGuard's noun regexes** decide whether a claim is "about the request" from the user's words. Part of the loop
   guards (MODEL-FIRST *Still to retire*, HARNESS MUSTS #4); `viewsTried` is the structural half already.
3. **InAppRoute** (`"pull up the browser card"` → Stage): an exact-phrase shortcut; check it is only used offline or as
   an explicit UI action, as MODEL-FIRST allows.
4. **Desktop / CLI**: the same shelf from their view lists, so every client places work the same way.

## 10. Code map

| File | Change |
|---|---|
| `agent/ViewShelf.kt` | New. `Spec(id, name, holds, tools, groups)`, `specs`, `byId`, `writer`, `promptBlock(stageOn)`, `currentDesign`, `why`. Pure. |
| `agent/ViewRouter.kt` | Deleted: `route`, the ten cues, `hint`, `PROMPT`, `isFollowOnEdit`, `followOnHint`, `routeAll`, and the Learn safety net that left the turn on 2026-11-18. |
| `agent/Agent.kt` | `s("views", ViewShelf.promptBlock(StageStore.enabled))` replaces the TURN-tier block; `turnView` and its groups are gone; `turnDesignNote` = the CURRENT DESIGN fact while a design is current; `ClaimGuard.relevant` gets `viewsTried(turnTools)`. |
| `agent/PromptProfile.kt` | `Plan.views`, `viewRouted` and `viewsShort` gone. |
| `agent/ClaimGuard.kt` | Reads `ViewShelf.specs`; `viewsTried(names)`; `relevant(spec, userText, tried)`. |
| the views core skill (`SKILL.md`) | v4, calm voice, Stage row, CURRENT DESIGN; `index.json` sha. |
| Tests | New `ViewShelfTest` (5); `ModelFirstTest.viewsAreAShelfNotAGuess`; route-based asserts in ViewsSkillsTest, DesignEverythingTest, IntentHonestyTest, CoreSkillsTest, VisualRouteTest, StageBrowserCardTest, ReliabilityP2Test and PromptCacheBenchTest now check the shelf and the fact line instead. |
