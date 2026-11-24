# Lean tools rule (owner rule, 2026-11-24)

Finn: "don't just start adding tools for every turn. Fold the tools into the discoverable tools; don't congest or slow
down the model's reply."

A new feature gets its tools in the **catalog**, never in the per-turn tool list. Every tool in a turn's list costs
input tokens on every step, slows the first token, and makes small models choose worse. The model finds catalog tools
with `find_tools` when the user's request needs them (docs/LEAN-HARNESS.md).

## The rule
1. **Do not add to `LeanTurn.CORE`.** It holds the 10 tools that must answer with no lookup (alarm, timer, message, …).
   Growing it needs Finn's explicit go, recorded here with the date.
2. **Register the tool in `Agent.tools()` and stop there.** Registration puts it in the phone's catalog (`LeanCatalog`),
   where `find_tools` searches it. That is all a feature needs to be usable.
3. **Write the description for search.** `find_tools` scores name words x3 and description words x1. Start the
   description with what the user would say it is for ("Argus Movie sources: add a provider repo by URL …"), use the
   words people use, and keep it short. No "ALWAYS" / "MUST", no routing instructions.
4. **One tool per area, with an `action` enum,** rather than one tool per verb. `movies` and `movie_sources` cover the
   whole Movie feature with two catalog rows.
5. **No keyword routing to reach a tool.** No regex on the user's text that picks a tool, forces it into the list or
   gates what it may do (docs/MODEL-FIRST.md, `ModelFirstTest`). The model decides from the tool's description.
6. **No prompt text per tool.** Do not add lines to `LeanTurn.PROMPT` or the stable rules to advertise a feature. What
   the model needs to know about a feature goes in the tool's description or in a skill (`load_skill`), read only
   when needed.
7. **Shelves stay one line.** If a feature also has a skill or a view, its shelf line follows docs/SKILL-SHELF.md
   (first sentence, at most 10 words after the dash).
8. **Gateway catalog:** after adding or renaming a tool, regenerate the catalog (`node tools/gen-catalog.mjs`) and copy
   it into Blaze-agent `gateway/src/catalog-data.ts`, so the plan's catalog and the direct lane know it.

## Checklist for a reviewer
- `git diff` touches `LeanTurn.CORE`, `LeanTurn.PROMPT` or `ToolPick.core`? Reject unless Finn approved it here.
- A new `Regex(` over the user's message that leads to a tool? Reject.
- New tool description longer than ~400 characters? Trim it; put the detail in a skill.

## Approved exceptions
None yet.
