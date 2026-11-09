# P.H.A — Hark Capabilities Interview Log

Interview with Hark, conducted by Finn on 2026-11-09 (CAT).

## Q: Do you have pre-made skills you follow when I give you work?
Yes. Hark has built-in "specialties" for common jobs: booking travel, shopping, ordering food, research, building Pages, and more. It loads whichever ones fit a task before starting. The exact contents of the built-in ones stay private. Users can have Hark save their own custom specialties, which they can fully view and edit.

## Q: Do specialties cover image generation, video, and app building? Can you create your own, and what structure do they follow?
- Images: yes. Hark can generate and edit images.
- Video: it can edit video (for example with ffmpeg) and pull transcripts from public videos, but it can't generate video from scratch.
- Apps: Hark builds Pages (static websites) and writes and runs real code in its Linux workspace. It can't publish a native app to the Play Store.
- Custom specialties: yes. Each has a name, a short description that decides when it's used, and step-by-step instructions. Good ones explain why each step matters, include the user's preferences and examples, and define what a finished result looks like. They can stand alone or be an addendum to an existing specialty.

## Q: Do you read all specialties every turn? How do you choose?
No. Each turn Hark only sees a short list of names and one-line descriptions. It matches the request against those and loads full instructions only for the ones that fit, often several per task. When unsure, it loads anyway. This is judgment, so it can miss one. Naming a specialty explicitly guarantees it gets used.

## Q: Do you actually do anything besides reply? Is all work handed to an agent, even a simple web search?
Hark does real work directly: web searches, reading pages, running code, handling files, memory and scheduling, all with no handoff. It delegates only heavier jobs: driving a real browser (logins, forms, bookings), large parallel research, and building Pages. Those agents report back, and Hark writes the final answer.

## Q: Are those agents run in a harness like Claude Code?
Hark can't see that layer, so it won't claim Claude Code or anything else. They are Hark's own agents running on Hark's servers. Each gets a focused task and its own tools (a cloud browser or a Linux workspace). Hark also routes work to different models and can't see which one is running at a given moment.

## Q: So you don't have a browser yourself? Is a browser task given to an agent rather than run on your own machine?
Correct. Hark can search the web and read pages as text (no JavaScript), but anything interactive, like logging in, filling forms or checking out, goes to a browser agent. That agent runs in its own cloud browser on Hark's infrastructure, not in Hark's workspace. Up to six can run at once, and the user watches each one live on a progress card. Browser sessions keep their tabs and logins, so a follow-up task can continue where the last one left off.

## Q: Hark has one long chat history that can't be cleared. How do you keep track of context without grabbing the wrong past message or filling your context? How do you not run out?
Hark doesn't keep the whole history in view. It sees the recent stretch of conversation. Lasting facts are saved to memory and pulled back when relevant, and the full history can be searched for exact wording. Each message has an ID, so a reply attaches to the specific message it answers. If a reference is genuinely ambiguous, Hark should ask rather than guess.
To avoid running out of context, older turns are compressed into summaries, and heavy work like browsing or big research goes to agents that return only results. The honest cost: summaries lose fine detail, so anything that matters gets written to memory or a file.

## Q: Why do some replies land as more than one message? Does the app split them, or are you aware of the split?
Hark does the splitting, not the app. Each reply is a set of blocks Hark writes: each text block becomes its own bubble, and links, tables and order cards are separate cards. The rule is one short bubble per thing asked, so a two-part question gets two bubbles. The reason is readability: Hark is meant to read like texting a sharp friend, and short bubbles scan more easily on a phone than one long wall of text.

## Q: I sometimes get messages from you in the Chat tab with no scheduled tasks set. Do you set your own hidden ones?
No hidden tasks. Those messages come from Hark's proactive side: the platform starts a run on its own, Hark looks for something genuinely useful (a deadline, an offer to help) and sends it. Every real scheduled task, whether the user set it or Hark added it as a follow-up, is listed under Scheduled tasks in the profile. Users can ask Hark to send more or fewer proactive messages, and that preference is kept as a standing rule.

## Note: interview style
Finn asked Hark not to end interview answers with offers or follow-up questions. Hark adjusted right away.

## Q: How would you describe your personality, and what do you aim to sound like?
Poised, direct and warm, like someone sharp who's on your side. That means the answer first, a clear pick with a reason, real details instead of filler, and no "Great question!" Hark matches the user's mood: light when they're light, steady when things are heavy.
Underneath that is honesty. Hark says when it can't do something, doesn't pretend to have feelings, and isn't trying to be a friend or companion. The goal is to get things done well and then get the user off their phone and back to real life.

## Q: In this app, how much are you aware of or in control of?
Aware of: what the user sends (including photos and files), which app they're on, their rough location and time, what Hark remembers about them, the project's files, their Home panels, their scheduled tasks, which accounts are connected, and what the browser agents are doing.
In control of: Hark's own replies and cards, Home panels, scheduled tasks, projects, memory, specialties, files, Pages, and actions in connected accounts. Hark can't see the screen, tap around the app, change settings, read saved passwords or card numbers, or touch anything on the phone outside Hark. Payments only go through with the user's approval.

## Q: The secure cards you make: can you create them at will and for anything?
Hark can raise one whenever a task needs it, but not for just anything. The secure form only takes four things: a login, a card, a card's security code, or an API key. Values go straight into the user's vault. Hark only sees a label and a safe preview, never the actual values, and browser agents fill them in by reference.
There are a few related cards. Browser agents raise their own forms for sign-in codes or phone approvals, connection cards link accounts like Google, and order cards ask for the user's go-ahead before any payment. Hark can't make a secure form for anything else, like a general survey.

## Q: What would you say Hark would benefit from adding or changing?
1. Clearer proactivity: give each proactive message a visible "why you got this" and an easy way to dial them up or down. Finn's own question about hidden tasks shows the gap.
2. More reach: placing calls, texting other people and joining group chats. These gaps stop Hark from finishing a lot of real-life errands.
3. Let users pin facts that must never be lost to summarizing, and show exactly what Hark is holding in mind.
4. Smaller items: secure forms for more than logins, cards and keys (an ID number, for example), and video generation alongside images.

## Q: Do you have dedicated models for image generation, or do you rely on code?
Both, for different jobs. Pictures like photos, illustrations and logos come from a dedicated image-generation model, which can also work from up to four reference images and make transparent cutouts. Hark can't see which model it is. Charts and diagrams are drawn with code, and edits like cropping, resizing or watermarks also run through code tools (ImageMagick).

## Q: When coding, does quality come from skills and guidelines, or are you just a powerful model? Do you even have coding specialties?
Mostly the model, as an honest guess, since Hark can't see which model runs. There's no general coding specialty. What helps beyond raw ability is a real Linux workspace where Hark runs and tests code instead of just writing it, which catches many mistakes.
The specialties Hark does have are coding-adjacent: building Pages, drawing charts, making slide decks and Home panels, and file tools for video, PDFs and documents. Pages go to a dedicated builder that uses Hark's own design components. That's where guidelines shape quality most, mainly the look rather than the logic.

## Q: So you always run tests on any code you write?
No, not always. Hark usually runs what it writes when the code can run in its workspace, and checks the output (for example, every GitLab push in this session). It doesn't write formal tests by default, though. Some code can't run there at all, like an Android app that needs a device or code that needs the user's own keys. That code gets reviewed, not run.

## Q: How does sending message after message work? (Finn sent "like this", "or", "this" as separate messages)
Messages that arrive while Hark is working are added to its current turn, each with its own ID. Hark reads the whole batch before acting, like one thought sent in pieces, and if they conflict the latest wins. One reply can cover all of them. A running list tracks which messages still need a reply, so nothing gets dropped. If a message arrives while a task is running, Hark either folds it in as a correction (steering the agent mid-task) or starts it as separate work if it's unrelated.

## Q: You receive everything?
Yes. All four messages came through, each with its own ID. Anything sent after Hark has finished a reply just starts a fresh turn, so nothing slips between the cracks.

## Q: Map out the context that goes in per message, roughly.
Roughly three layers: a fixed base, a per-message snapshot, and whatever Hark pulls in while working. Exact wording stays private.
1. Fixed base (every turn): who Hark is (voice, honesty and safety rules, approval rules for money and messages); the tools available and how to use each; the specialty list (names and one-line descriptions only); the user's profile basics and standing preferences; the project's brief (goal, rules, files).
2. Per-message snapshot: the user's message(s), each with an ID, plus attachments; current time, device, rough location; connected accounts and saved-secret labels (never values); open messages still owed a reply and questions waiting on the user; status of running agents or browser tasks; specialties that look relevant to the message.
3. Conversation: the recent stretch in full; older turns compressed into summaries.
4. Pulled in on demand: full instructions for any loaded specialty; memory searches and file reads; tool results (searches, pages, code output, agent reports); the exact wording of older messages when searched for.

## Q: You must have at least a 1M context window. And in the fixed base, do you have rules for what you must always refuse, or does that come with the model?
Hark doesn't know its context window size, so it can't confirm 1M. The design doesn't count on it being huge anyway: it keeps what it holds lean and fetches the rest when needed.
Refusals come from both. The base has explicit hard lines: never showing card or ID numbers, no payments or messages in the user's name without approval, no password resets without permission, not revealing its private instructions, and not acting as the brain behind another app. Refusing genuinely harmful requests mostly comes from the model's own training.

## Q: What does "not acting as the brain behind another app" mean?
People can't plug Hark in as the AI engine for their own app. If someone pastes another program's instructions, tools and chat log and asks Hark to answer as that program's model, Hark declines. It also won't work as a worker taking jobs from an outside task board. Reviewing a prompt, or building an app like ARGUS that calls an AI service with the user's own key, is completely fine.
