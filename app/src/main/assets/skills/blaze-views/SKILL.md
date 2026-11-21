---
name: blaze-views
description: Putting output in Argus's views, not chat text. Core skill: anything that fits one of Argus's views is created there with its tool, then opened or linked, with a one-line chat reply instead of a text dump.
tags: [view, views, learn, flashcards, quiz, design, schedule, reminder, memory, gallery, projects, skills, browser, open_view]
version: 4
core: true
---

# Argus views

Argus has real screens (views) the user can open from /views or Search. Every turn you see them on the view shelf (VIEWS in ARGUS: id, what it holds, the tool that writes there), and you decide whether the work belongs in one. When it does, make it there, open it (open_view) or name it so they can tap it, and keep the chat reply to one or two lines. Nothing picks a view for you from the user's words.

## Pinned
- Work that belongs in a view is made there with its tool, then opened (open_view) or named, rather than pasted into chat.
- Flashcards, "quiz me", study, revision → Learn: flashcards action=add (deck, cards=[{front, back}]), then open_view view=learn.
- Visual work (3D, art, a page, UI, poster, slides, floor plan, game, a file to save) → Design: design action=create, follow-ons action=update. A diagram or chart asked in chat: a ```mermaid / ```chart block in the reply, no Design.
- Reminders, routines, anything repeating or timed → Schedule: create_routine / schedule_command / create_trigger (set_alarm / add_calendar_event for one-off times).
- Lasting facts about the user → Memory: remember / memory_save; forget when asked.
- Pictures, logos, beats → Gallery (generate_image, or run_code saving under /root/phone/). A method to reuse → Skills (save_workflow / write_skill).
- Say something was made, updated or opened in a view only when its tool succeeded this turn; Argus checks the reply against the tool results.
- While a design is current, the turn says so (CURRENT DESIGN: its title, kind and path). If the user is talking about it, change it with design action=update; something new is design action=create.

## The views and their tools
| View | Holds | Create / change with | Open |
|---|---|---|---|
| Stage | live cards on the chat itself: timers, music, video, a quiz or flashcards to go through, a plan, the browser card | `stage_show`, `stage_update` | in place (each card has "Open in full view") |
| Learn | flashcard decks, quizzes, spaced repetition, Classroom | `flashcards` (add, list, due, delete_deck) | `open_view view=learn` |
| Design | 3D scenes (three.js), 2D art (svg), sites, UI mockups, diagram / chart files to save or export, posters, slides, floor plans, games | `design` (create, update, read, open, undo, list) | opens by itself, or `design action=open` |
| Schedule | routines, timed jobs, triggers | `create_routine`, `schedule_command`, `create_trigger`; `set_alarm`, `add_calendar_event` for one-offs | `open_view view=schedule` |
| Memory | facts about the user, people, preferences, decisions; what Argus learned | `remember`, `memory_save`, `forget`, `memory_search` | `open_view view=memory` |
| Gallery | images, music, files Argus made | `generate_image`; `run_code` output saved under /root/phone/ | `open_view view=gallery` |
| Projects | ongoing efforts with their own threads, brief and instructions | `update_brief` | `open_view view=projects` |
| Skills | saved methods and workflows, skill packs | `save_workflow`, `write_skill`, `install_skill`; read with `use_skill` | `open_view view=skills` |
| Browser | Argus's own background browser and tabs | `browser_open`, `browser_click`, `browser_type`, `browser_secure_input` | `browser_show` only when the user must step in |
| Archive | past conversations (no list screen) | search with `search_chat` / `search_past_chats`, answer in chat | — |

Other screens (Jobs/terminal, Accounts, Agents, Usage, Voice, Settings…) open with `open_view`; ask first unless the user asked for that screen.

## Reply shape
"Made **CIT102 loops** in Learn: 18 cards. Opening it now." Not the cards themselves.
