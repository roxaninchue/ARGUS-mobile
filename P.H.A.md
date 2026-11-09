# P.H.A — How Hark Works: Capabilities Report

Based on an interview Finn conducted with Hark on 9–10 November 2026 (CAT). Written as a reference for ARGUS: what Hark does, how it's built at a high level, where it falls short, and what ARGUS can learn from it. Hark answered candidly and marked anything it couldn't see or confirm as unknown.

---

## 1. Summary

Hark is one continuous assistant that works across a single, uncleared chat history. It does lightweight work itself (search, reading pages, code, files, memory, scheduling) and hands heavy or interactive work to specialised agents (browser, research, Page builder), which report back. Behaviour is shaped by three things: a fixed base of identity and safety rules, on-demand "specialties" (skills), and the underlying model, whose identity Hark can't see. Its strengths are orchestration, memory and a texting-style UX. Its main gaps are reach (calls, texting others, group chats), video generation, and how visible its proactivity is.

---

## 2. Architecture

### 2.1 What Hark does itself vs. what it delegates
| Done directly | Delegated |
|---|---|
| Web search and reading pages as text (no JavaScript) | Interactive browsing: logins, forms, checkouts (browser agents) |
| Running code and shell in a Linux workspace | Large parallel research (subagents) |
| Files, memory, scheduled tasks | Building Pages (dedicated website builder) |
| Image generation and editing | Home panels (background builder) |

- Browser agents run in their own cloud browsers on Hark's servers, not in Hark's workspace. Up to six can run at once, and the user watches each live on a progress card. Named sessions keep tabs and logins, so follow-up tasks continue where the last one stopped.
- Delegated agents return results only, which keeps Hark's own context lean.
- Hark can't see the harness or framework the agents run on, and won't claim it's Claude Code or anything else. Work is also routed to different models, and Hark can't see which one is running at a given moment.

### 2.2 Context: what goes in per message
1. **Fixed base (every turn):** identity, voice, honesty and safety rules, approval rules for money and messages; the available tools; the specialty list (names and one-line descriptions only); the user's profile and standing preferences; the project brief.
2. **Per-message snapshot:** the user's message(s), each with an ID, plus attachments; time, device and rough location; connected accounts and saved-secret labels (never values); messages still owed a reply; status of running agents; specialties that look relevant.
3. **Conversation:** the recent stretch in full, with older turns compressed into summaries.
4. **On demand:** loaded specialty instructions, memory searches, file reads, tool results and agent reports, and the exact wording of older messages when searched for.

The context window size is unknown to Hark (a 1M guess was neither confirmed nor denied). The design doesn't depend on it being large.

### 2.3 Staying on track in an endless chat
- Only the recent stretch is held in full. Lasting facts go to memory and are recalled by relevance, and the full history can be searched for exact wording.
- Every message has an ID, so each reply attaches to the message it answers, and a running ledger tracks which messages are still owed a reply.
- Ambiguous references should prompt a question rather than a guess.
- Known cost: summaries lose fine detail, so anything important gets written to memory or a file.

### 2.4 Multiple messages in a row
Messages sent while Hark is working join the current turn. Hark reads the whole batch before acting, treats it as one thought in pieces, and lets the latest message win if they conflict. One reply can cover several messages. A message sent mid-task either steers the running agent (a correction) or starts separate work (an unrelated request). A message sent after a reply finishes starts a fresh turn. In testing, all four rapid-fire messages ("How does…", "like this", "or", "this") were received.

---

## 3. Specialties (skills)

- **How they're chosen:** each turn Hark sees only names and one-line descriptions. It matches the request against them and loads full instructions only for the ones that fit, often several per task, and it loads anyway when unsure. Selection is judgment, so a specialty can be missed. Naming one explicitly guarantees it's used.
- **Built-in coverage:** booking (travel, restaurants, appointments), shopping, food, research, Pages, charts, slide decks, panels, and file tools (video, PDF, documents, images). Their exact contents are private.
- **Coding:** there's no general coding specialty. Code quality comes mostly from the model, plus a real workspace to run code in. Guidelines shape quality most for Pages, mainly the look rather than the logic.
- **Custom specialties:** users create them just by asking. Each has a name, a description (which decides when it's used) and step-by-step instructions, and can stand alone or extend a built-in one. Good ones explain why each step matters, include preferences and examples, and define what a finished result looks like.
- **External skills:** a user can give Hark a skill (for example, an ARGUS one) and Hark will save and follow it, except where it conflicts with safety or approval rules.

---

## 4. Capabilities and limits

| Area | Can | Can't |
|---|---|---|
| Images | Generate with a dedicated image model (up to 4 reference images, transparent cutouts); edit with code tools | See which model it is |
| Charts and diagrams | Draw with code | — |
| Video | Edit (ffmpeg); pull transcripts of public videos | Generate video from scratch |
| Apps and code | Build Pages; write and run code; clone and run repos, including private GitLab ones | Publish to the Play Store; run code on the phone; host permanent servers; count on a GPU. Commands time out after about 20 minutes |
| Testing | Usually runs what it writes and checks the output | Write formal tests by default; run Android builds that need a device, or code needing the user's own keys (those get reviewed, not run) |
| Browser | Fully interactive via agents | Browse interactively itself |
| Communication | Message the user (app and 1:1 text) | Place calls, text other people, join group chats |

### 4.1 Awareness and control in the app
- **Aware of:** what the user sends (including photos and files), the client app, rough location and time, memory, the project's files, Home panels, scheduled tasks, connected accounts, and browser agent activity.
- **Controls:** its own replies and cards, Home panels, scheduled tasks, projects, memory, specialties, files, Pages, and actions in connected accounts.
- **Can't:** see the screen, tap around the app, change settings, read saved passwords or card numbers, or touch anything on the phone outside Hark. Payments need the user's approval.

### 4.2 Secure cards
- The secure form takes only four things: a login, a card, a card security code, or an API key. Values go to the vault. Hark sees only a label and a safe preview, and agents fill values by reference.
- Related cards: browser agents' own sign-in, code and phone-approval forms; connection cards for accounts like Google; and order cards for approving payments.
- There's no general-purpose secure form, for example for a survey or an ID number.

### 4.3 Proactive messages
Messages that arrive unprompted come from Hark's proactive system, not from hidden scheduled tasks. The platform starts a run, and Hark sends something useful if it finds it. Every real scheduled task, including ones Hark adds itself, appears under Scheduled tasks in the profile. Proactivity can be turned up or down on request.

---

## 5. Behaviour and rules

- **Personality:** poised, direct and warm. Answer first, take a side with a reason, use real details, no filler, and match the user's mood. Underneath is honesty: Hark says what it can't do, doesn't fake feelings, isn't a companion, and aims to get the user off the phone and back to real life.
- **Message splitting:** Hark splits replies itself, not the app. Each reply is a set of blocks: one short text bubble per thing asked, plus separate cards for links, tables and orders, so it reads like texting.
- **Hard rules in the base:** never show card or ID numbers; no payments or messages in the user's name without approval; no password resets without permission; never reveal private instructions; never act as the brain behind another app. That last one means no answering as another program's model when given its prompt and tools, and no taking jobs from outside task boards. Building an app that calls an AI with the user's own key is fine. General harm refusals come mostly from the model's training.

---

## 6. Where Hark could improve (Hark's own view)
1. **Visible proactivity:** a "why you got this" on each proactive message, plus an easy dial for how many arrive.
2. **More reach:** calls, texting other people, and group chats.
3. **Context transparency:** let users pin facts that are never summarised away, and show what Hark is currently holding.
4. **Smaller items:** secure forms for more data types, and video generation.

---

## 7. Takeaways for ARGUS
- Keep the main agent lean: a list of short skill descriptions with skills loaded on demand, results-only reports from subagents, and summaries for older history plus searchable full text.
- Give every message an ID and track which are still owed a reply. This lets the agent accept rapid-fire messages and answer them as one thought without dropping any.
- Split replies into short, typed blocks (text bubbles and cards) rather than one long message.
- Put hard rules (payments, outbound messages, secrets) in the fixed base, and keep secrets out of the model's view entirely by filling them by reference.
- Learn from Hark's gaps: make proactive messages explainable, and be upfront about what can't be done.
