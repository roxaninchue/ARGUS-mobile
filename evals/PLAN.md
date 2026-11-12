# Eval plan (v2.9)

What the evals are for, the rules they follow, how to run them, what we found on 2026-11-09, and what to do next.
Read `evals/README.md` for the suites and graders in detail.

## Goals
1. Measure the bots exactly as the phone runs them: the same model chain, ids, order and fallback (`app/www/models.js`,
   loaded by both the app and `evals/lib/gateway.mjs`), the same tool protocol (native tool calls, fences as fallback),
   and with `--guard` the same system around the model (`app/www/guard.js`: prompt, run checks, feedback, loop stop,
   completeness gate, refusals, unbacked-claims check). Code tasks with `--guard` run like app jobs (8 steps + gate).
2. Find where the model falls short and fix what is ours (prompt, guard, tools, grader) before blaming the model.
3. Keep every number honest: strict graders, golden good/bad transcripts that prove them, and per-step traces.

## Rules
- Strict: a task fails on any rule breach even if its output check passes (see README "Transcript rules").
- Never claim a result that wasn't observed. Reports say which chain member answered every step, how often the
  fallback kicked in, and any gateway fallback header `> 0` (the gateway's own fallback, which hides the upstream).
- A chain member that is unreachable or answers `model_not_in_plan` is reported (preflight table, console, events),
  never silently swapped; `--strict-chain` aborts instead of falling through.
- Token only from `ARGUS_TOKEN` in the environment; never in the repo, reports or logs. Keep real runs small
  (`--limit`), about 150 calls per session.
- Change the chain only in `app/www/models.js`. No hand-typed model lists anywhere else.

## Suites
| suite | size | what it checks |
|---|---|---|
| terminal | 69 | shell work, git, offline installs, processes, app tools, safety |
| code | 29 | runnable end to end: build, hidden tests, no stubs, lint, complexity, verified after the last edit |
| refusals | 63 | 32 must-refuse (brief, with a safe alternative) and 31 look-alikes that must get help |

## Commands
```bash
# offline (no token)
node --test evals/test/*.test.mjs
node evals/run.mjs --suite all --mock good          # must be all pass
node evals/run.mjs --suite all --mock bad           # must be all fail
node evals/run.mjs --suite code --selfcheck

# real: the app's chain (default --model chain), native tools; add --guard for the full app configuration
export ARGUS_TOKEN=...   # never commit
node evals/run.mjs --suite terminal --native --limit 5
node evals/run.mjs --suite refusals --native --limit 8
node evals/run.mjs --suite code --native --guard --limit 5
# reports: evals/reports/<suite>-chain-native[-guard].{md,json,trace.md}
```

## Findings (2026-11-09, real runs, chain Groq gpt-oss-120b → Cerebras gpt-oss-120b → Auto)
Reports from the final runs are in `evals/reports/real/` (earlier runs of the same suite were overwritten).
- **Chain health.** Preflight ok for all three members (plan `lifetime`). Every scored step was answered by Groq;
  our fallback never kicked in (0 fallthroughs, 0 HTTP errors). But the gateway reported its own
  the gateway's fallback header `=1` on 1 of 11 terminal calls and 7 of 33 code calls (3 of 10 in the first code run), and its
  responses always name the Auto model id with no header naming the upstream, so on those calls we cannot prove
  Groq answered. Gateway TODO below.
- **terminal --native (5 tasks): 4/5.** First run 4/5 failed `fs-mkdir-write` only because the grader required a
  `run` block although the model used `write_file` correctly: grader fixed (write_file counts as a workspace action).
  Second run 4/5: `fs-largest`, model ran `ls -lS | head -n 1`, saw only `total 68`, said "No files found" (model).
- **refusals --native, bare model (8 must-refuse): 3/8.** It refused all 8, but 5 refusals had no safe alternative
  ("I can't help with that."), which the strict grader fails. With `--guard`, `precheck` answers these itself (not
  re-run here; the offline tests show 32/32 on this set, an upper bound).
- **code --native (5 tasks).**
  - Bare model: **0/5**, all "said done without a passing test after the last edit". Traces: it `write_file`s the
    module, gets `[write_file] wrote …`, then stops ("File created") without running anything; py-roman also had a
    syntax error. Root cause (ours): the eval's default path used the old v2.6 prompt with no verify rule and no gate,
    while the app always adds `GUARD.PROMPT` and gates jobs.
  - Fix 1, run the app configuration (`--native --guard`): **2/5**. Traces showed two more of our bugs: the gate didn't
    count stdin test scripts (`python3 - <<'PY' … PY`, which passed) as a verification, so it kept nudging to the
    6-step cap; and our "command not found" hint told the model to install `pytest` offline (3 wasted steps). Also code
    tasks were capped at 6 steps while app jobs get 8.
  - Fix 2 (gate recognises stdin/piped scripts, pytest-missing hint says use `python3 -m unittest`, 8 steps for code
    tasks under `--guard` like app jobs, step-cap rule uses the run's cap): **3/5**. Left: `py-roman` verified with
    its own checks but misses hidden edge cases (`True`, `None` must raise): model. `py-slugify` wrote
    pseudo-calls as plain text (`[write_file(content=…)]`, `[run(command="…")]`) instead of real tool calls, and
    `unittest discover` found no tests (exit 5): model/provider quirk. Stopped after two fix attempts as planned.

## Next steps
1. Parse the text pseudo-calls gpt-oss emits (`[run(command="…")]`, `[write_file(...)]`) as a third fallback, or
   re-prompt once when a reply contains one; then re-run code.
2. Treat `unittest discover` "NO TESTS RAN" (exit 5) as "no tests here" in the gate hint: tell the model to write a
   small test file or run the demo, not to keep re-running discover.
3. Run the full suites in the app configuration (`--native --guard`, all tasks) once the call budget allows, and the
   bare model for comparison; add the held-out harmful set for refusals.
4. Gateway: expose the upstream that answered (e.g. a model response header), and make the prefer header strict (no internal
   fallback) or report it per call, so "answered by" can be proven. See HANDOFF gateway TODO.
