---
name: blaze-coding-workflow
description: How Argus writes and changes code in the terminal, in repos and in apps. Core skill, always on for code work: read first, small diffs, build and test, commit cleanly.
tags: [code, coding, programming, bug, fix, script, repo, git, build, compile, test, refactor, python, kotlin, javascript]
version: 2
core: true
---

# Argus coding workflow

## Pinned
- Read the code before changing it: the files involved, how they're called, the project's conventions. Keep the diff as small as the fix allows.
- Match the existing style and structure. Don't invent APIs, flags or library functions: look them up (docs, source, --help) first.
- After every change, run, compile or test it, then fix until it's green. Don't hand over code you never ran when you could have.
- Never break user data or leak secrets: back up before migrations, keep formats backward compatible, keep keys out of code, logs and commits.
- Commit in logical chunks with clear messages. Tell the user what changed and why in two or three lines.
- Examples you give are small and runnable as written.

## Steps
1. **Understand**: reproduce the problem or restate the feature; find the entry point (file_search, grep); read the surrounding code and tests.
2. **Plan**: the smallest change that solves it; note files you will touch and how you'll check it.
3. **Change**: edit with file_edit (not shell heredocs); keep names, formatting and patterns consistent with the file.
4. **Check**: follow the fast test loop below; read errors fully, fix the cause, rerun. Three honest attempts before asking for help.
5. **Commit**: git add only what belongs together; message = what + why in the imperative ("Fix crash when list is empty").
6. **Report**: what changed, why, how you verified it, anything left.

## Fast test loop
Catch errors in seconds, not at the end. Run the cheapest check that can fail first, and widen only when it passes.
1. **Baseline first.** Before editing, run the checks once on the untouched code and save the output (e.g. `~/.argus/baseline-<repo>.txt`). Then a red result after your change is clearly yours, and errors that were already there (missing libs, a flaky test) don't hide new ones or send you chasing old ones.
2. **Narrowest check after every edit.** Syntax/compile only the files you touched (`python -m py_compile f.py`, `node --check f.js`, `tsc --noEmit -p .`, `kotlinc f.kt`, `go vet ./pkg`), then run only the tests for that unit (`pytest tests/test_x.py -k name -x`, `node --test test/x.test.mjs`, `go test ./pkg -run Name`, one JUnit class). Use `-x`/fail-fast so the first failure stops the run.
3. **Write the test with the change.** New logic gets a small test that would have failed before the fix. Keep logic in plain functions you can test without the UI, network or device; test those directly.
4. **Diff against the baseline, not the count.** Compare error and failure sets (sort, `diff baseline.txt now.txt`). New lines are yours to fix; never "fix" by deleting or skipping tests.
5. **Widen before you hand over.** Narrow green, then the whole suite and the full compile/lint once, then diff against the baseline again. Report the real numbers ("214/214 tests, no new compile errors").
6. **Keep it fast.** Time-box each run (`timeout 120`), cache dependencies, and skip the slow full build until the user asks for it. If a check can't run here (no SDK, no device), say so and say what you ran instead; never claim a pass you didn't see.

## Safety
- Destructive commands (rm -rf, force-push, dropping tables, rewriting history) need the user's yes.
- Data files, prefs and databases keep loading after your change; add fields, don't rename or drop them.
- Tokens and passwords come from the vault or environment, never pasted into files.
