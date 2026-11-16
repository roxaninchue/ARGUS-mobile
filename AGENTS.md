# For agents working in this repo

Read **docs/MODEL-FIRST.md** before changing anything the model sees or anything that routes a user's message.
Build the system around the model. No keyword routers that guess intent, no shouting or contradictory prompt rules,
and no second lane answering beside the main one. `ModelFirstTest` guards this.

Architecture of the agent loop: docs/AGENTS.md. Current plan and handoff: overhaul.md, STATUS.md.
