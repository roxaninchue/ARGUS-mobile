---
name: blaze-coding-workflow
description: How Argus writes and changes code in the terminal, in repos and in apps, and how it builds a working thing the user asked for (a server, a page, a tool). Core skill, always on for code work: read first, small diffs, build and test, use it as the user will, commit cleanly.
tags: [code, coding, programming, bug, fix, script, repo, git, build, compile, test, refactor, python, kotlin, javascript]
version: 3
core: true
---

# Argus coding workflow

## Pinned
- Read the code before changing it: the files involved, how they're called, the project's conventions. Keep the diff as small as the fix allows.
- Match the existing style and structure. Don't invent APIs, flags or library functions: look them up (docs, source, --help) first.
- After every change, run, compile or test it, then fix until it's green. Don't hand over code you never ran when you could have.
- For something the user will use (a server, a page, a tool), use it the way they will and look at it before calling it done: see "Building something the user will use" (read this skill with use_skill).
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

## Building something the user will use (a server, a page, a script, a small app)
A task is done when the user can use the thing for what they asked, not when the code runs once.
1. **Spec in one line, then the hard parts.** Write down what done looks like from the user's side ("open the page, tap a song, it plays, it keeps playing with the screen off") and the parts most likely to break. For a media server those are: the file types the phone's browser can play, HTTP Range requests (audio and video seek and often won't start without `206 Partial Content`), correct `Content-Type` per extension (`.m4a` audio/mp4, `.opus` audio/ogg, `.mp3` audio/mpeg, `.flac` audio/flac), file names with spaces, brackets and non-ASCII (URL-encode links, decode on the server), and staying alive when the app or screen is off.
2. **Use a proper base.** Prefer a real server (Python `http.server` with a Range-aware handler, or a small framework already installed) over hand-rolled netcat or shell loops. Bind `0.0.0.0` only when other devices need it, else `127.0.0.1`. Start it with run_shell background=true and keep the job id.
3. **Exercise every promise with real requests.** Not just "the page loads": fetch the list, then fetch a real file the way the client will (`curl -sI` for headers, `curl -s -r 0-1023 -o /dev/null -w '%{http_code} %{content_type}'` for Range, one file with spaces or brackets in its name). Each check states what it expects and what it got. Fix and recheck until every one passes.
4. **Look at it.** For anything with a screen, open it with preview_server, then look_at_screen, and judge it as the user will: readable on a phone (meta viewport, 16px+ text, tap targets 44px+), clear hierarchy, what's playing or selected is obvious, controls work. Tidy names (drop extensions and "(Official Video)" noise in the label, keep the real file). Fix what looks broken, then look again.
5. **Say what holds and what doesn't.** Report what you verified, how to open it (the URL), and the limits that are real on this phone (for example: Android may stop a background process when the app is closed or the battery saver is on; the browser keeps playing with the screen off only while its tab is playing). Don't call it done while a check is failing; say which one and what you'd try next.

## Safety
- Destructive commands (rm -rf, force-push, dropping tables, rewriting history) need the user's yes.
- Data files, prefs and databases keep loading after your change; add fields, don't rename or drop them.
- Tokens and passwords come from the vault or environment, never pasted into files.
