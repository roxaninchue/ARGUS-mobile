# Skill shelf: ARGUS picks its own skills

Status: implemented on main, 20 Nov 2026 (not built yet). Follows [MODEL-FIRST.md](MODEL-FIRST.md), where
"`SkillMatch` … still pick … skill packs … from the message's words" is listed under *Still to retire*, with the
suggested fix "list them in the prompt and let the model load one". This doc is that fix, reasoned out.

---

## 1. What is wrong today

A skill is a SKILL.md: a written-down way of doing something (make flashcards, build an Android app, reply to the
latest WhatsApp). ARGUS ships 57 of them, the user can install or write more, and ARGUS learns its own (recorded
phone steps). The same files sync through the Argus account to Desktop and the CLI. Skills are one of the most
valuable things in the ecosystem, because they are how ARGUS gets *better at the user's own tasks* over time.

But the model never sees the shelf. Before the first model call, code guesses which skills the message is about, and
the model only sees the guess. The guess has six layers, each added to patch the one before:

| Layer | File | What it does |
|---|---|---|
| Task-intent regex | `SkillIntent.taskIntent` | ~40 verbs + "can you / please / how do I" patterns decide whether the message "asks for a task". If not, no skills at all. |
| Word score | `SkillMatch.selectForTurn` / `score` | Name ×3, keywords ×2, description ×1 word overlap; full SKILL.md bodies (up to 6,000 chars) pasted in on a "strong" match. |
| Stray-word filter | `SkillGuard.strongHit`, `weakName` | A list of "weak" words ("note", "page", "model", "app"…) that may not pull a skill in alone. |
| Proper-name stripper | `SkillGuard.unnamed` | Removes runs of capitalised words so "Dots 3 Note Preview" doesn't load lecture-notes. |
| Lookup block | `SkillMatch.lookupBlock` → `SkillIndex.promptBlock` | A BM25 "best matches for this request" list of ≤ 3, re-filtered by all of the above. |
| Refusal gate | `SkillGuard.gate` (Agent `skillGate`) | When the *model* calls `use_skill` on a skill the code thinks is unrelated, the tool refuses ("Not loaded… call again with reason=…"). |

Plus side switches: `VisualRoute.filter`, `AppFinder.isAppsAsk`, `FastActions.plainDevice` (only learned skills on a
"strong" match), and "state / self question" flags that blank the skills.

**Each layer exists because of a real incident** (they are cited in the KDoc: a casual "I'll try and sleep soon" showed
`Skills: android-app-builder`; "official benchmarks for Dots 3 Note Preview" loaded lecture-notes; "stop GitHub opening
links" pulled in five skills). The fixes were all the same shape: another word rule. That is the pattern MODEL-FIRST.md
says to stop.

### Why it fails both ways

- **Wrong skills in.** Word overlap is not meaning. Every new skill adds new words that collide with everyday
  messages, so every new skill makes the guesser worse. The patches only ever subtract.
- **Right skills out.** The intent regex reads statements as "not a task". *"I've got a biology exam on Friday and I'm
  lost"* is a first-person statement, so `taskIntent` is false, and the model never learns that `study-planner`,
  `tutor` and `study-flashcards` exist. *"my landlord wants the form done by tomorrow"* never reaches
  `paperwork-mode`. A message in Bemba or Nyanja matches no English verbs at all.
- **The model is overruled.** When the model does find the right skill (with `find_skill`), the gate can refuse it
  because the user's last three messages don't share the right words ("yes do it" after a long plan). The model is
  then told the skill is "unrelated" — a false fact in a tool result, which is the exact thing MODEL-FIRST forbids.
- **The prompt changes every message.** The skills block is a TURN-tier prompt part whose text depends on the
  message, so it can't be cached, and the full bodies it pastes in (up to ~1,500 tokens) are paid on every step of
  the turn whether the model needed them or not. Measured on today's library: "stop GitHub opening links in the GitHub
  app" pasted android-app-builder and wf-do-it-on-screen in full, 4,494 characters of the wrong skills (SkillGateTest).
- **Users can't predict it.** A skill the user wrote works only if they happened to describe it with the words they
  later type. "It didn't use my skill" has no fix the user can see.

## 2. The idea

**Show the model the shelf, and let it pick.**

Every turn, the prompt carries one short, stable list of every skill on the phone that's switched on: its name and
what it is for, one line each. The model reads the request, the conversation and that list, and when a skill fits,
it reads it with `use_skill` (or replays a learned one with `run_skill`). Nothing guesses before the model; nothing
refuses after it.

That's the whole mechanism. The code gets smaller, not bigger.

## 3. How it works for users

| The user says | Before | After |
|---|---|---|
| "I've got a biology exam on Friday and I'm lost" | No skills (a statement, not a "task"). ARGUS chats. | ARGUS sees `study-planner`, `tutor`, `study-flashcards` on its shelf, reads `study-planner`, and offers a revision plan with reminders before Friday. |
| "official benchmarks for Dots 3 Note Preview" | Needed `unnamed()` to stop `lecture-notes` loading. | Nothing is loaded for it. The model knows what `lecture-notes` is for and doesn't read it for a benchmark search. |
| "use my airtime skill" / a skill they wrote last week | Only if the description shared the right words. | Their skill is on the shelf, listed first. The model reads it. |
| A skill they just installed or wrote | Found only through word overlap. | On the shelf from the next message (the list re-reads at most every 20 s). |
| A skill switched off in Settings › Skills | Excluded. | Excluded: not on the shelf, and `use_skill` still says it's off. |
| "turn off the GitHub links thing" (plain phone action) | Special case: only learned skills on a strong match. | No special case. A learned skill for it is on the shelf marked as replayable; the model replays it with `run_skill` or just does it. |
| "What is ARGUS holding?" (Settings › What Argus is holding) | Listed the skills the *guesser* attached, read or not. | Lists the skills ARGUS actually **read** for the last request. True by construction. |

What the user notices: ARGUS uses the right playbook more often, especially for things they say casually or in their
own language, and their own skills "just work" without careful wording. When ARGUS reads one, the step row says
*Reading skill "study-planner"* as it always has, so it's visible in Chat and on the Living canvas.

## 4. What the model sees

One prompt part, in the SLOW tier (it only changes when skills are installed, switched or learned):

```
SKILLS on this phone (name: what it's for). When one fits what the user wants, read it with use_skill before you
start; one marked [replay] is the user's recorded phone steps and run_skill replays it. find_skill searches them.
- airtime-topup: Top up Airtel airtime with the USSD menu [replay]
- my-weekly-report: Make my weekly sales report from the store emails
- ad-lab: Write 5 ad variants for a product or offer and score each on hook, clarity and…
- android-app-builder: Build a real Android app (Kotlin + Jetpack Compose) from the phone and install…
…
```

- **Facts, not orders.** It says what is there and what the tools do. No "ALWAYS", no "only if relevant", no
  "unrelated skills are refused". Deciding relevance is the model's job and it is good at it.
- **The user's own skills first** (learned, then written / installed), then the bundled library, each group
  alphabetical. The user's skills are the most specific to them, and alphabetical order keeps the text byte-stable.
- **One line each**, from the skill's own `description`: a leading "Workflow. Use when:" or one-to-three-word title
  sentence ("Price Scout.") is dropped, then the first sentence, cut at a word boundary near 80 characters. No new
  metadata, no second description to maintain.
- **The reader rides with the list.** With the lean chat kit on (the default), the main chat carries `AgentKit.CHAT_CORE`
  only, which had no skill tool. `use_skill` joins it (40 of the 40 allowed); `run_skill` and `find_skill` join the
  turn when the model calls them, like any tool outside the kit.

## 5. Decisions and why

1. **A list, not a router.** A list is information; a router is a decision made without the model. The model already
   reads the whole conversation; it is better placed than a regex to know that "I'm lost" before an exam means study
   help. This is MODEL-FIRST's "give the model what it needs to act, let the model decide".
2. **Names and one line, not bodies.** Bodies are 1–12 KB each. The model reads the one it needs with `use_skill`;
   that costs one visible step, only when useful. Today's pasted bodies cost tokens on every step of every matching
   turn.
3. **SLOW tier, so it caches.** The shelf only changes when the skill set changes. In the SLOW tier it sits in the
   cached prefix (`PromptParts`), unlike today's per-message TURN block.
4. **A budget with an honest overflow.** 6,500 characters (≈ 1,600 tokens). The user's own skills are always listed;
   if bundled lines don't fit, the shelf ends with "+N more bundled skills: find_skill lists them". Today's 57 bundled
   skills take 5,550 characters, header included.
5. **No refusals.** `SkillGuard.gate` is removed. If the model reads a skill that doesn't help, the cost is one step,
   and the step is visible. A tool that lies ("unrelated") to steer the model is worse than an occasional extra read.
6. **`use_skill` reads learned skills too.** The shelf lists learned skills, so `use_skill <learned name>` now returns
   its description, when-to-use and steps (it used to say "No skill"). `run_skill` still replays the recorded taps.
7. **`find_skill` stays.** It's the model's own search, used when it decides to search. That's a tool, not a router.
8. **Kept on purpose (not word-guessing the user's message):**
   - `SkillGuard.Freq`: counts tasks done often with no skill, to suggest writing one. It looks at finished work.
   - The pinned coding core skill on coding turns (`CoreSkills`, `PromptProfile`).
   - `SpecialistMatch` and the packs a matched specialist brings. That *is* still a word router, but it's a separate
     system (specialists, delegate, extras group) and gets its own change; see §10.
9. **Learned skills on plain device actions.** The old "plain device action → learned skills only, strong match" path
   goes away. A learned skill is on the shelf with `[replay]`, so the model can replay it; nothing else changes for
   device actions.

## 6. The ecosystem

Skills are shared across ARGUS: the phone, Argus Desktop and the CLI read the same SKILL.md format, and the user's own
skills follow their Argus account (`SkillSync`, the Argus auth Worker's `/v1/skills`). The gateway's catalog lists Apps and Skills.

- **The description is the contract.** On every client, a skill is found by its name and description. With the
  shelf, the first ~80 characters of the description are literally what the model reads to decide. The bundled
  `skill-creator` and `blaze-workflow-builder` skills (which the model reads when writing skills) now say so: start the
  description with what the skill does, in the user's words.
- **A skill written on Desktop shows on the phone's shelf** as soon as it syncs, with no keyword tuning.
- **Same format for Desktop and the CLI.** `SkillShelf.block` is pure Kotlin with no Android code; Desktop / the CLI
  can render the same block from their own skill stores (Desktop's FTS store is still right for `find_skill`). Not
  done in this change.

## 7. Cost

| | Before | After |
|---|---|---|
| Skills text per turn | 0 (casual) to ~6,500 chars (3 bodies + lookup) | 5,550 chars, same every turn |
| Cacheable | No (TURN tier, per message) | Yes (SLOW tier) |
| Code before the first call | 6 layers, 2 async lookups | One cached list |
| Extra model calls | 0 | 0 (a `use_skill` read is a tool step the model chooses) |

## 8. Code

- **New** `agent/SkillShelf.kt` (pure): `summary(description)`, `entries(cands)`, `block(cands, budget)`,
  `promptBlock()` cached on a signature of the skill set.
- **Agent.kt**: `slow("skills", SkillShelf.promptBlock())` replaces the per-message `turn("skills", lookupBlock…)`;
  the `forTurn` / `learnedOnly` lookups, the state/self/plain-device skill switches and `skillGate` are removed;
  `turnSkills` now only carries a matched specialist's packs; the session records `skillsRead` (names passed to
  `use_skill` / `run_skill`) and Holding shows those.
- **SkillPacks**: `use_skill` / `find_skill` / `save_workflow` descriptions say what the tools do (no refusal
  wording, no `reason` parameter); `use()` falls back to a learned skill.
- **Learning**: `run_skill` loses the `reason` parameter (it only existed for the gate).
- **AgentKit**: `use_skill` joins the lean chat kit (`CHAT_CORE`).
- **Bundled skills the model reads when writing skills**: `skill-creator` (v3) and `blaze-workflow-builder` (v2) say
  that the description's first sentence is the skill's line on the list; index.json versions and sha256 updated so
  phones refresh them.
- **Deleted** (no callers left): `SkillMatch.forTurn`, `selectForTurn`, `lookupBlock`, `learnedOnly`;
  `SkillIndex.promptBlock`; `SkillGuard.gate`, `relevant`, `strongHit`, `Verdict`.
- **Kept**: `SkillIntent` and `SkillGuard.unnamed` (still used by specialists), `SkillMatch.match / block / cached /
  find`, `SkillIndex` search, `SkillGuard.Freq`.

## 9. Risks, and how we'd notice

- **A small model ignores the shelf.** Then it does the task with its tools, as it does today on any message the
  guesser skipped. Visible in Holding ("skills read"). Fix what the model is given (a
  clearer description line) before adding any rule.
- **A model reads skills it doesn't need.** One tool step each, shown as "Reading skill …". If it becomes a pattern,
  improve the descriptions that mislead it.
- **Libraries far larger than 57.** The budget keeps the user's own skills and cuts bundled lines with an honest
  "+N more: find_skill". If users reach hundreds of their own skills, group by folder or show the most used; not
  needed now.
- **Prompt size on low-RAM phones.** ~1,400 tokens, cached; about what two pasted bodies cost today on matching turns.

## 10. Tests

- `SkillShelfTest` (new, JVM): summary trimming; order (learned, user, bundled; alphabetical); disabled skills left
  out; `[replay]` mark; budget overflow keeps every user skill and says "+N more"; the real bundled library fits the
  budget; the block has no shouting / absolutes; the block is byte-identical for the same set (cache).
- `ModelFirstTest.skillsAreAShelfNotAGuess`: Agent.kt no longer calls `SkillMatch.forTurn(`, `lookupBlock(`,
  `learnedOnly(` or `SkillGuard.gate(`, and sends `slow("skills", SkillShelf.promptBlock())`.
- Updated: SkillGuardTest, SkillGateTest, SkillIntentTest, SkillIndexTest, PromptBudgetTest, PromptCacheBenchTest
  (the bench's skills part is SLOW and stable now), AgentKitTest (`use_skill` in the chat kit).

## 11. Undo

One revert of the implementation commit. No data, schema or settings change: skills, learned skills and the Freq
store are untouched.

## 12. Next

1. **Specialists the same way:** list specialists (name + one line) and let the model `delegate` / load one, instead
   of `SpecialistMatch.match` on the message's words. Then `SkillIntent` and `SkillGuard.unnamed` can go.
2. **Views:** `ViewRouter.route` still picks a view by words; the model already has the view tools.
3. **Desktop / CLI shelf:** render the same block from their stores so every client chooses skills the same way.
