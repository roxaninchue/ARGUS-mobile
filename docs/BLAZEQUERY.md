# BlazeQuery (0.51.0) — automatic, judged facts for every reply

BlazeQuery runs **automatically the moment the user sends a message** ("involuntary omniscience"). It is not a tool the
reply model decides to call. It fetches high-accuracy information, hyper-summarises it into a few scored facts and puts
them into the prompt through the tiered-memory cache tier (`cache.db`), so the model answers — and above all writes code
and runs commands — from current, trustworthy sources without spending its context on pages.

Code: `app/src/main/java/com/argus/assistant/agent/query/` (pure, JVM-tested) + the glue in `QueryRuntime.kt` and
`Agent.Session.runLoop`. Research mode and `/query` / `/research` are extras on the same machinery.

```
 user sends ─┬─► prompt assembly (tools, skills, memory, orient …)            ─┐
             └─► BlazeQuery.hot  (in parallel, budget HOT_MS = 1.2 s from send) │
                  need detection (no model, µs)                                 │
                   ├ NONE  (small talk, phone actions, writing, weather) → nothing
                   ├ LOCAL (Blaze, its / commands, this chat, the user's data) → this chat's cache only, never the web
                   └ EXTERNAL / CODE
                       reuse a live topic from cache.db?  ── yes → pack (instant)
                       no → plan 1–3 sub-queries (heuristic, from the conversation)
                            search them in parallel + package registries (HTTP, never the browser UI)
                            heuristic judge (relevance · authority · freshness · agreement)
                            ───────────── ready inside the budget? → evidence pack ──────────────► first model call
                  background (never blocks the reply)                            │
                       read the best trusted pages → extract passages (code kept whole)
                       judge again → the SELECTED TIER scores the evidence (Spark/Flare/Inferno)
                       store: cache.db document (kind "blazequery") + topic row with TTL
                       └► onLate: the finished pack joins the NEXT model steps of the same turn;
                          later turns reuse the topic; cache_search / cache_read reach the full text
```

## 1. Need detection (`NeedDetect`)
Pure rules, microseconds, no keyword trigger for *answering* from the web (0.50 `SearchRoute` stays the authority):

| Need | When | What happens |
|---|---|---|
| NONE | small talk, `/commands`, weather (get_weather has it), phone actions ("set an alarm", "open WhatsApp"), writing jobs ("write a poem") | nothing fetched, nothing injected |
| LOCAL | about Blaze, its / commands, this conversation or the user's own data (`SearchRoute.isLocal`) | the best BM25 hits in this chat's cached documents, ≤ 200 tokens; never the web |
| CODE | an error message / stack trace, a code fence, a CLI tool with flags, APIs, libraries, versions, Android/Linux commands | priority trigger; registries + official docs preferred; command verification |
| EXTERNAL | a question about the world (facts, how-to, news, prices, releases) | evidence pack |

Private chats are skipped entirely (nothing fetched, nothing stored). Background agents, specialists and routines skip it.

## 2. Query planning (`QueryPlanner`)
- Hot path: 0.50's conversation-aware query (`SearchRoute.query`: "it" / "that" resolved from earlier turns, filler
  dropped), plus intent variants: the error line itself (paths, addresses, line numbers stripped), "… release notes" for a
  version question, "… documentation" for API work, the year for news. Intent tags: docs, version, error, news, how-to, fact.
- Research mode / background: the model writes 1–4 sub-queries with intent tags (`modelPrompt` / `parseModel`, lenient JSON).
- `topicKey` = sorted content words: two wordings of one subject share a cache entry (reuse when Jaccard ≥ 0.6).

## 3. Retrieval — free sources only, terminal-first
`QueryRuntime.net` uses the app's existing keyless HTTP stack (no browser, no screen, no paid API):
- `Web.search`: DuckDuckGo HTML → Bing RSS → GitHub repository API (a bot check is reported as a block, not as "nothing").
- `Web.fetch`: the page as plain text (scripts / nav / footer stripped), at most 60k chars per page, 6 s per fetch.
- `Registries`: package registries' own JSON APIs for version facts — PyPI, npm, crates.io, Maven Central, GitHub
  releases (`pip install x`, `npm i x`, `cargo add x`, `group:artifact`, `github.com/o/r` in the message). Parsed with
  regexes so a body cut by the fetch limit still yields its version.
- Optional gateway proxy (setting `bq_gateway`, **default off**): `GET <gateway>/v1/query/search?q=` — written on gateway
  branch `feat/blazequery` (0.8.60), **not deployed**. Any failure falls back to on-phone search.

Limits: 3 sub-queries, 4 s per search, 3 pages per message, 6 s per page, registry bodies ≤ 2 MB.

## 4. Source policy (`SourcePolicy`)
Fixed list, identical on every phone and in the gateway proxy. Trust 0..1:

| Class | Trust | Examples |
|---|---|---|
| blocked | 0 | Wikipedia and mirrors (wikiwand, …), any open wiki / "-pedia", fandom, wikiHow, Quora, answers.com, Chegg / Course Hero / Studocu / Brainly, Scribd, Pinterest, scraped code mirrors (codegrepper, newbedev, …), AI aggregators (toolify, futurepedia, …), social feeds (X, Facebook, Instagram, TikTok, LinkedIn) |
| official | 1.0 | developer.android.com, kotlinlang.org, docs.python.org, PyPI, npm, crates.io, Maven Central, man7 / manpages.debian, gnu.org, kernel.org, IETF / RFC editor, W3C, WHATWG, MDN, git-scm, ffmpeg.org, sqlite.org, GitHub *release pages*, a project's own wiki (wiki.archlinux.org, wiki.alpinelinux.org …) |
| high | 0.85 | GitHub / GitLab repos, readthedocs, .gov / .edu / .ac sites, Reuters, AP, BBC, Guardian, Al Jazeera, Nature, arXiv |
| ok | 0.6 | Stack Overflow / Stack Exchange, Ars Technica, The Verge, LWN, Baeldung … |
| low | 0.3 | Medium, dev.to, W3Schools, GeeksforGeeks, Reddit, YouTube |
| unknown | 0.45 | everything else |

Blocked urls are never fetched (`QueryRuntime.net.fetch` refuses them) and never injected.

## 5. Extraction (`Extract`)
Boilerplate lines dropped (cookie banners, sign in, nav, ©…), consecutive code lines grouped into one block and never cut
mid-line (≤ 900 chars), prose in short paragraphs (≤ 520 chars, cut at a sentence end), blocks ranked by query-term hits
(a code block also counts the prose that introduces it), best 3 per page kept in page order. The page's newest past date
is its freshness date. The cache document keeps up to 8 passages per page with source url and date; the cache tier's own
`Chunker` sections it and FTS5/BM25 indexes it.

## 6. Quality judging (`Judge`) — the selected tier judges
1. **Heuristic (hot path)**: `0.40·relevance + 0.30·authority + 0.15·freshness + 0.15·agreement` (+0.15 for a registry
   answer, +0.05 for relevant code). Freshness depends on intent (news ages in days, versions in months, docs in years).
   Agreement = the same versions / numbers on independent hosts. Duplicates (normalised text) keep the most trusted copy.
   Items under 0.38 or with zero relevance are dropped. Version answers that differ between trusted sources are flagged
   as a **conflict** line instead of silently picking one.
2. **Model (background)**: the tier picked on the composer judges — **Auto → Flare**. Spark: one batch of 12, scores only,
   280-char snippets. Flare: batches of 8. Inferno: batches of 5, 480-char snippets, and it also checks commands / flags /
   APIs / versions against each other and names conflicts. The call carries the tier as its own `blaze_family` hint
   (`CallState.family`) without changing the user's pick. Scores 0–10 (or 0–1) parsed leniently; blended 50/50 with the
   heuristic; an item the model rates irrelevant (< 3/10) is dropped. Hot path never waits for a model.

## 7. Cache tier storage (TTL, provenance, dedupe)
- `cache.db` (the 0.49 tiered-memory cache, not blaze.db): one document per topic, kind `blazequery`, in the chat that
  asked (so `cache_search doc=all` finds it), plus a `bq` row: topic key, doc id, created, **expires**, the judged
  evidence as JSON (text, url, title, date, origin, code, intent, score, authority, judged, conflict), hit count.
- TTL: news 1 h, versions 3 h, everything else 6 h. Expired rows and their documents are purged (`bqExpire`, on ~1 in 5
  stores) and the cache's 48 MB LRU cap still applies.
- Dedupe: storing a topic again replaces its row and deletes the old document; evidence is de-duplicated before storing.

## 8. Context-efficient injection (`EvidencePack`)
Only a compact pack enters the prompt — never a page. One header line with the rules ("use these over older knowledge;
cite [q1] only when used; anything not covered is UNVERIFIED"), then one line per fact, `[q1] fact — host, date`, each
≤ 260 chars (code ≤ 420 chars in a fence), conflict / check lines, and the doc id for `cache_read` / `cache_search`.

| Route | Budget |
|---|---|
| chat lane (lite) | 220 tokens (the pack is cut at a line end at ~1,000 chars) |
| gateway auto (window unknown) | 380 |
| window < 16k | 300 |
| otherwise | window / 40, 300–900 |
| code turn | × 1.4, still ≤ 900 |

The pack sits in the per-turn tail of the system prompt (after the cached-documents TOC, before the `[context]` block), so
the stable prompt prefix stays cacheable.

## 9. Verification for code and commands (`Verify`)
Every `run_shell` result gets one line when this turn has evidence: `[BlazeQuery] verified against ffmpeg.org (-ss -t)`,
`partly verified …; unverified: --turbo`, or `unverified: the fetched docs don't cover apt-get -y`. The program must be
named in an evidence item and each flag must appear in an item about that program. It never blocks or changes a command
— CommandPolicy and the confirm card still decide what runs. The pack's header tells the model to call commands / flags /
APIs outside the evidence "unverified" (or check them with `cache_search`) before running or writing them.
`Verify.checkCode` does the same for API names in code.

## 10. Research mode (`Research`, tool `blaze_research`, `/query`, `/research`)
A reusable API, not tied to the reply path:
`plan (model; heuristic offline) → retrieve (search + trusted pages + registries) → judge → gap analysis → follow-up
queries … → judge with the selected tier → cited brief`.
- Limits: `QUICK` (/query) 1 round, 3 queries, 3 pages, 30 s; `DEEP` (/research) 3 rounds, 3 queries and 4 pages per
  round, 90 s, 14 facts kept. Stops early when the model says the question is covered, nothing new turned up, or time ran out.
- The brief cites `[q1]…` and keeps commands / versions verbatim; without a model it is the top facts as a cited list.
- Everything is stored in the cache tier (document "Research: …" + topic row), so later turns reuse it.

## 11. Speed and stability
- Starts in parallel with prompt assembly; the reply waits at most `HOT_MS` (1.2 s) from the send, counting the assembly
  that already happened, plus a 150 ms safety margin. A miss is named in the speed line ("late: blazequery") and logged
  (`BlazeQuery` in logcat with status and ms).
- Every network / disk call is on `Dispatchers.IO`; the background pass runs in `QueryRuntime.scope` (SupervisorJob, IO),
  which outlives the turn, so cancelling a turn never cancels the fetch that will serve the next one.
- No main-thread I/O; nothing here runs on the main thread.

## 12. Settings, offline, telemetry
- Settings › Personality › "BlazeQuery: fresh facts for every reply" (`blazequery`, **on** by default). Off = 0.50 behaviour.
  `bq_gateway` (off) = try the gateway proxy first.
- Offline: a live cached topic is still injected; otherwise nothing (status OFFLINE); research says it needs the internet.
- Telemetry: `BlazeQuery.Stats` counters on the phone only (skipped, local, reused, injected, late, empty, offline, pages,
  blocked_sources, judged, research_runs, errors). Never uploaded.

## 13. Tests
`BlazeQueryPureTest.kt` (SourcePolicyTest, NeedDetectTest, QueryPlannerTest, RegistriesTest, ExtractTest, JudgeTest,
EvidencePackTest, VerifyTest), `BlazeQueryEngineTest.kt` (hot path under a slow network returns within budget and the
background finishes on its own; fast path injects a judged pack without Wikipedia; topic reuse without a new search; TTL
expiry deletes row + document; dedupe keeps one document; LOCAL / small talk / private never touch the network; offline
degradation; the background judge uses the selected tier; ResearchTest: round bounds, early stop, no-model brief).

## 14. Limits and known gaps
- Search quality depends on DuckDuckGo HTML / Bing RSS from the phone; DuckDuckGo often answers phones with a bot check
  (then Bing RSS). The gateway proxy would help but is not deployed.
- 1.2 s is tight for a cold search on a slow network: the first message on a new topic often gets its pack only on the
  next model step (tool turns) or the next turn; chat-lane (single-call) replies then go without it.
- The heuristic planner can't resolve every reference; the model-written plan is used only in research mode.
- Snippets from search engines are short and sometimes stale; they are scored lower than page passages for freshness
  when they carry no date.
- `Verify` is lexical (names and flags), not semantic; it can't tell a flag that exists but is wrong for the task.
- JavaScript-only documentation pages yield little text over plain HTTP.
