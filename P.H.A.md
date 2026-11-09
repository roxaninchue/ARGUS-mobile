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
