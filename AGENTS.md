# For agents working in this repo

Read **docs/MODEL-FIRST.md** before changing anything the model sees or anything that routes a user's message.
Build the system around the model. No keyword routers that guess intent, no shouting or contradictory prompt rules,
and no second lane answering beside the main one. `ModelFirstTest` guards this.

Skills and specialists reach the model as shelves (docs/SKILL-SHELF.md): each line is the first sentence of a skill's
description or a specialist's prompt, capped at 10 words after the dash, so write that sentence to say when to use it.

Architecture of the agent loop: docs/AGENTS.md. Current plan and handoff: overhaul.md, STATUS.md.
